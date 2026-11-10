package com.aerosaga.workflow;

import com.aerosaga.activity.DroneActivities;
import com.aerosaga.activity.DroneActivities.DropResult;
import com.aerosaga.activity.DroneActivities.NavigationResult;
import com.aerosaga.activity.DroneActivities.RoutePlan;
import com.aerosaga.activity.DroneActivities.TakeoffResult;
import com.aerosaga.activity.DroneOfflineException;
import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.RouteCommand;
import com.aerosaga.domain.RouteProgress;
import com.aerosaga.domain.Waypoint;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.List;

/**
 * The Week 3 saga: one flight, many stops.
 *
 * <h2>What changed from Week 2</h2>
 * <p>The single-destination mission is untouched; this is a second workflow type registered on the
 * same task queue. The step list is the same shape — take off, move, drop, return — but the
 * middle repeats once per stop, and the stop <em>order</em> comes from an activity instead of
 * from the caller.</p>
 *
 * <h2>Why the ordering is an activity</h2>
 * <p>Route optimisation is a pure function today, but in production it is a call to a routing
 * service with traffic data. Doing it in an activity means the result is written into Temporal's
 * history, so a replay reuses yesterday's route instead of silently computing a different one.
 * Put that call inside the workflow and the same mission could visit stops in a different order
 * after a restart — a non-determinism bug that would be miserable to debug.</p>
 *
 * <h2>Compensation</h2>
 * <p>If the battery drops below the threshold at any stop, the remaining stops are abandoned and
 * the drone flies home. Parcels already dropped stay dropped; the rest go back to inventory. That
 * is a saga: partial success is a legal outcome, and it is reported honestly.</p>
 */
public class RouteMissionWorkflowImpl implements RouteMissionWorkflow {

    private static final int OFFLINE_TIMEOUT_SECONDS = 7200; // two hours, per the problem statement

    private final DroneActivities activities = Workflow.newActivityStub(
            DroneActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(3))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    private RouteCommand command;
    private RouteProgress progress;
    private boolean reconnectRequested;
    private boolean abortRequested;
    private String abortReason;

    @Override
    public RouteProgress deliverRoute(RouteCommand cmd) {
        this.command = cmd;
        this.progress = RouteProgress.initial(cmd.missionId(), cmd.droneId());
        int step = 1;

        MissionCommand first = cmd.missionFor(cmd.stops().get(0));

        // 1. Optimise the route. An activity, so the answer is durable and replay-stable.
        RoutePlan plan;
        try {
            plan = activities.planRoute(cmd);
        } catch (ActivityFailure e) {
            return fail(first, "Route planning failed: " + rootMessage(e), step);
        }
        List<Waypoint> ordered = plan.ordered();
        progress = progress.withPlannedRoute(ordered, plan.totalDistanceKm(), step++);
        activities.reportPhase(first, MissionPhase.NAVIGATING.name(),
                "Route optimised: " + ordered.size() + " stops, "
                        + String.format("%.1f", plan.totalDistanceKm()) + " km");

        try {
            // 2. One takeoff for the whole route.
            TakeoffResult airborne = activities.takeoff(first);
            progress = progress.terminal(MissionPhase.NAVIGATING, airborne.batteryPct(),
                    "Airborne with " + ordered.size() + " parcels", Workflow.currentTimeMillis());

            // 3. Visit every stop in the order the planner chose.
            int delivered = 0;
            for (int i = 0; i < ordered.size(); i++) {
                Waypoint stop = ordered.get(i);
                MissionCommand leg = cmd.missionFor(stop);

                if (abortRequested) {
                    return compensate(first, "Aborted by operator: " + abortReason, delivered);
                }

                progress = progress.atStop(stop, i + 1, MissionPhase.NAVIGATING,
                        progress.batteryPct(), "En route to stop " + (i + 1) + " — " + stop.label(),
                        Workflow.currentTimeMillis());

                NavigationResult nav = navigate(leg, stop);

                if (nav.batteryPct() < cmd.lowBatteryThresholdPct()) {
                    // Do not attempt the drop: the drone has to be able to get home.
                    return compensate(first,
                            "Battery " + round(nav.batteryPct()) + "% fell below the "
                                    + cmd.lowBatteryThresholdPct() + "% threshold after stop "
                                    + i + " of " + ordered.size(),
                            delivered);
                }

                DropResult drop = activities.dropParcel(leg);
                if (drop.delivered()) {
                    delivered++;
                    progress = progress.stopDelivered(stop, delivered, drop.batteryPct(),
                            Workflow.currentTimeMillis());
                    activities.reportPhase(leg, MissionPhase.NAVIGATING.name(),
                            "Delivered " + delivered + "/" + ordered.size() + " at " + stop.label());
                } else {
                    activities.reportPhase(leg, MissionPhase.NAVIGATING.name(),
                            "Drop refused at " + stop.label() + ": " + drop.message());
                }
            }

            // 4. Home.
            activities.returnToBase(first);
            String detail = "Delivered " + delivered + " of " + ordered.size()
                    + " stops and returned to base";
            activities.reportPhase(first, MissionPhase.DELIVERED.name(), detail);
            progress = progress.terminal(MissionPhase.DELIVERED, progress.batteryPct(), detail,
                    Workflow.currentTimeMillis());
            return progress;

        } catch (ActivityFailure e) {
            if (isOffline(e)) {
                // The suspension path returns only after a successful reconnect or a timeout
                // compensation, so it always produces a terminal progress object.
                return suspendAndResume(first, e);
            }
            return compensate(first, "Activity failed: " + rootMessage(e), progress.stopsDelivered());
        }
    }

    @Override
    public RouteProgress progress() {
        return progress;
    }

    @Override
    public void abort(String reason) {
        this.abortRequested = true;
        this.abortReason = reason == null ? "operator request" : reason;
        // Wake any suspended leg so the abort is not stuck behind an offline wait.
        this.reconnectRequested = true;
    }

    @Override
    public void reconnect() {
        this.reconnectRequested = true;
    }

    // ------------------------------------------------------------------ suspension

    /**
     * Fly one leg, parking durably if the radio drops.
     *
     * <p>{@code Workflow.await} is the whole trick: it suspends this workflow in Temporal's
     * history, so a two-hour outage costs zero threads, zero timers per second and zero polling.
     * When the reconnect signal lands, execution continues on the line after the await with every
     * variable intact.</p>
     */
    private NavigationResult navigate(MissionCommand leg, Waypoint stop) {
        while (true) {
            try {
                return activities.navigate(leg, stop);
            } catch (ActivityFailure e) {
                if (!isOffline(e)) {
                    throw e;
                }
                activities.reportPhase(leg, MissionPhase.OFFLINE_SUSPENDED.name(),
                        "Link lost en route to " + stop.label()
                                + " — workflow suspended durably inside Temporal");
                progress = progress.atStop(stop, progress.step(), MissionPhase.OFFLINE_SUSPENDED,
                        progress.batteryPct(), "Link lost — suspended, waiting for reconnect",
                        Workflow.currentTimeMillis());

                Workflow.await(Duration.ofSeconds(OFFLINE_TIMEOUT_SECONDS),
                        () -> reconnectRequested);

                if (!reconnectRequested) {
                    throw e; // timeout: the caller compensates
                }
                reconnectRequested = false;

                if (abortRequested) {
                    throw e;
                }
                activities.reportPhase(leg, MissionPhase.NAVIGATING.name(),
                        "Resumed after offline suspension — continuing to " + stop.label());
                progress = progress.atStop(stop, progress.step(), MissionPhase.NAVIGATING,
                        progress.batteryPct(), "Resumed to " + stop.label(),
                        Workflow.currentTimeMillis());
            }
        }
    }

    private RouteProgress suspendAndResume(MissionCommand leg, ActivityFailure failure) {
        // navigate() already handled the suspension and re-threw only on timeout or abort.
        return compensate(leg, "Offline for longer than "
                + OFFLINE_TIMEOUT_SECONDS + "s — abandoning the route", progress.stopsDelivered());
    }

    // ------------------------------------------------------------------ terminal helpers

    private RouteProgress compensate(MissionCommand leg, String reason, int delivered) {
        progress = progress.terminal(MissionPhase.COMPENSATING, progress.batteryPct(), reason,
                Workflow.currentTimeMillis());
        try {
            activities.compensateReturnToBase(leg, reason);
            String detail = "COMPENSATED: " + reason + " — delivered " + delivered + " stop(s), "
                    + "returned to base with the rest";
            activities.reportPhase(leg, MissionPhase.COMPENSATED.name(), detail);
            return progress.terminal(MissionPhase.COMPENSATED, progress.batteryPct(), detail,
                    Workflow.currentTimeMillis());
        } catch (RuntimeException e) {
            String detail = "Compensation failed: " + rootMessage(e);
            activities.reportPhase(leg, MissionPhase.FAILED.name(), detail);
            return progress.terminal(MissionPhase.FAILED, progress.batteryPct(), detail,
                    Workflow.currentTimeMillis());
        }
    }

    private RouteProgress fail(MissionCommand leg, String detail, int step) {
        try {
            activities.reportPhase(leg, MissionPhase.FAILED.name(), detail);
        } catch (RuntimeException ignored) {
            // Reporting is best effort; the workflow still has to return an outcome.
        }
        return progress.terminal(MissionPhase.FAILED, progress.batteryPct(), detail,
                Workflow.currentTimeMillis());
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isOffline(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth++ < 20) {
            if (cursor instanceof DroneOfflineException) {
                return true;
            }
            if (cursor instanceof ApplicationFailure failure
                    && failure.getType() != null
                    && failure.getType().contains("DroneOfflineException")) {
                return true;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor.getCause() != null && cursor.getCause() != cursor && depth++ < 20) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private static String round(double value) {
        return String.format("%.1f", value);
    }
}
