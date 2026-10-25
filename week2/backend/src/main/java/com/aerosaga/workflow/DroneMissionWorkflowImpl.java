package com.aerosaga.workflow;

import com.aerosaga.activity.DroneActivities;
import com.aerosaga.activity.DroneActivities.DropResult;
import com.aerosaga.activity.DroneActivities.NavigationResult;
import com.aerosaga.activity.DroneActivities.ReturnResult;
import com.aerosaga.activity.DroneActivities.TakeoffResult;
import com.aerosaga.activity.DroneOfflineException;
import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.domain.Waypoint;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * THE saga: Takeoff → Navigate → Drop → Return, with compensation when things go wrong.
 *
 * <h2>The three things that make this durable</h2>
 * <ol>
 *   <li><b>State lives in fields.</b> Temporal serialises this object after every step. Kill the
 *       JVM and these exact values come back on restart.</li>
 *   <li><b>Waiting is {@code Workflow.await}, not {@code Thread.sleep}.</b> A blocked thread
 *       dies with the process; {@code Workflow.await} is recorded in history and re-evaluated on
 *       replay. That is why a mission can sit OFFLINE_SUSPENDED for two hours and resume
 *       precisely where it stopped.</li>
 *   <li><b>Side effects are activities.</b> Activities are at-least-once and their results are
 *       cached, so a restart re-uses the original result instead of flying the drone twice.</li>
 * </ol>
 */
public class DroneMissionWorkflowImpl implements DroneMissionWorkflow {

    /**
     * Built once with {@link Workflow} factory methods, never with {@code new}. Anything created
     * with {@code new} is invisible to the replay engine.
     */
    private final DroneActivities activities = Workflow.newActivityStub(
            DroneActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(3))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(4)
                            .build())
                    .build());

    // Both fields are initialised when Temporal instantiates the class, which happens BEFORE
    // runMission() delivers the real command. That is why they cannot be built from one.
    private MissionCommand command;
    private MissionSnapshot snapshot = MissionSnapshot.initial();

    private boolean reconnectRequested;
    private boolean abortRequested;
    private String abortReason;

    @Override
    public MissionSnapshot runMission(MissionCommand cmd) {
        this.command = cmd;

        try {
            // ---------------------------------------------------------- 1. TAKEOFF
            enter(MissionPhase.TAKEOFF, "Spooling rotors at " + cmd.originLabel(), 1);
            TakeoffResult airborne = retryOffline(() -> activities.takeoff(cmd), cmd, "takeoff");
            if (airborne == null || abortRequested) {
                return compensate(cmd, "aborted by operator after takeoff");
            }
            record(MissionPhase.TAKEOFF, "Airborne at " + (int) airborne.altitudeM() + " m",
                    airborne.batteryPct(), airborne.lat(), airborne.lng(), airborne.altitudeM());

            // ---------------------------------------------------------- 2. NAVIGATE
            for (Waypoint waypoint : flightPlan(cmd)) {
                NavigationResult nav = retryOffline(
                        () -> activities.navigate(cmd, waypoint), cmd, "leg to " + waypoint.label());
                if (nav == null || abortRequested) {
                    return compensate(cmd, "aborted by operator mid-flight");
                }
                if (nav.batteryPct() < cmd.lowBatteryThresholdPct()) {
                    return compensate(cmd, "battery fell to " + nav.batteryPct()
                            + "% below the " + cmd.lowBatteryThresholdPct() + "% threshold");
                }
                enter(MissionPhase.NAVIGATING, nav.message() + " (" + nav.batteryPct() + "% battery)", 2);
            }

            // ---------------------------------------------------------- 3. DROP
            enter(MissionPhase.DROPPING, "Hovering over " + cmd.destinationLabel(), 3);
            DropResult drop = retryOffline(() -> activities.dropParcel(cmd), cmd,
                    "parcel drop over " + cmd.destinationLabel());
            if (drop == null) {
                // The parcel was never released, so there is nothing to undo but the flight.
                return compensate(cmd, "aborted by operator before the parcel was released");
            }
            if (!drop.delivered()) {
                return compensate(cmd, "delivery could not be completed");
            }
            if (abortRequested) {
                return compensate(cmd, "aborted by operator after drop");
            }

            // ---------------------------------------------------------- 4. RETURN
            enter(MissionPhase.RETURNING_TO_BASE, "Returning to " + cmd.originLabel(), 4);
            ReturnResult home = retryOffline(() -> activities.returnToBase(cmd), cmd,
                    "return to " + cmd.originLabel());
            if (home == null || abortRequested) {
                // Aborted while already flying home: the compensating action is the same flight.
                return compensate(cmd, "aborted by operator while returning to base");
            }
            record(MissionPhase.DELIVERED,
                    "Parcel delivered to " + cmd.destinationLabel()
                            + ", drone home with " + home.batteryPct() + "% battery",
                    home.batteryPct(), home.lat(), home.lng(), 0.0);

            log().info("Mission {} DELIVERED", cmd.missionId());
        } catch (ActivityFailure e) {
            // Retries are exhausted. The workflow survives — that is the point.
            log().error("Mission {} exhausted its retries at {}", cmd.missionId(), snapshot.phase(), e);
            enter(MissionPhase.FAILED, "Activity failed after retries: " + rootMessage(e),
                    snapshot.step() + 1);
        }

        return snapshot;
    }

    // ------------------------------------------------------------- the interesting bit

    /**
     * Run one side-effecting step, and if the radio drops, <b>park durably</b> until it comes back.
     *
     * <p>All four phases go through this, not just navigation. Losing the link while the parcel
     * is being released is exactly the scenario in the problem statement, and treating that as a
     * permanent failure would break the story. Read the {@code catch}: it does not retry, does not
     * fail, does not spin. It calls {@link #suspendUntilReconnect}, which is a
     * {@code Workflow.await} — Temporal records "waiting for a reconnect signal" in the history
     * and frees the worker thread. Two hours later the signal arrives and the step re-runs from
     * the top of this loop, at the exact point it stalled.</p>
     *
     * @return the activity result, or {@code null} when an abort interrupted the step
     */
    private <T> T retryOffline(java.util.function.Supplier<T> step, MissionCommand cmd, String what) {
        while (true) {
            if (abortRequested) {
                return null;
            }
            try {
                return step.get();
            } catch (ActivityFailure e) {
                if (abortRequested) {
                    return null;
                }
                if (DroneOfflineException.isCausedBy(e)) {
                    suspendUntilReconnect(cmd, what);
                    if (abortRequested) {
                        return null;
                    }
                    // Re-run the step from wherever the drone actually is right now.
                    continue;
                }
                throw e;
            }
        }
    }

    /**
     * Durable suspension.
     *
     * <p>Between the two lines below there is no thread, no timer, no poller and no database
     * query. If you kill Spring Boot right here, restart it two hours later, and send
     * {@code reconnect()}, execution resumes on the line after {@code Workflow.await} with all of
     * this object's fields exactly as they were. That is the Mid-Project Review demonstration.</p>
     */
    private void suspendUntilReconnect(MissionCommand cmd, String what) {
        enter(MissionPhase.OFFLINE_SUSPENDED,
                "Link lost during " + what
                        + " — workflow suspended durably inside Temporal", 2);

        reconnectRequested = false;
        Workflow.await(() -> reconnectRequested || abortRequested);

        if (abortRequested) {
            return;
        }
        enter(MissionPhase.NAVIGATING,
                "Link restored after suspension — resuming " + what, 2);
        log().info("Mission {} resumed after offline suspension", cmd.missionId());
    }

    /**
     * COMPENSATION — undo the mission in reverse order.
     *
     * <p>In a real logistics saga each already-completed step gets its own compensating action
     * (release the reserved parcel, cancel the payment, notify the customer), executed newest
     * first. Here they collapse into one, because only one real thing happened: the parcel was
     * never released, so all that is left is to bring the drone home.</p>
     */
    private MissionSnapshot compensate(MissionCommand cmd, String reason) {
        enter(MissionPhase.COMPENSATING, "Compensating: " + reason, snapshot.step() + 1);

        activities.compensateReturnToBase(cmd, reason);

        enter(MissionPhase.COMPENSATED,
                "Mission compensated (" + reason + "). Parcel NOT delivered, drone returned to base.",
                snapshot.step() + 1);
        log().warn("Mission {} COMPENSATED: {}", cmd.missionId(), reason);
        return snapshot;
    }

    // ------------------------------------------------------------- signals & queries

    @Override
    public MissionSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void reconnect() {
        reconnectRequested = true;
    }

    @Override
    public void abort(String reason) {
        abortRequested = true;
        abortReason = reason == null ? "operator request" : reason;
    }

    @SuppressWarnings("unused")
    private String currentAbortReason() {
        return abortReason;
    }

    // ------------------------------------------------------------- internals

    /** The full flight plan: every en-route waypoint, then the destination. */
    private static List<Waypoint> flightPlan(MissionCommand cmd) {
        List<Waypoint> plan = new ArrayList<>(cmd.route());
        plan.add(new Waypoint(cmd.destinationLabel(), cmd.destLat(), cmd.destLng(), 110.0));
        return plan;
    }

    /**
     * Update the snapshot AND publish it to the outside world.
     *
     * <p>These two things are deliberately bundled: every state change the workflow makes becomes
     * exactly one activity call ({@code reportPhase}), which updates the drone registry for the
     * dashboard and upserts the Supabase audit row. One transition, one write path — a second
     * writer would produce duplicate audit rows.</p>
     *
     * <p>Note {@code Workflow.currentTimeMillis()}: calling {@code System.currentTimeMillis()}
     * here would break replay, because the value would differ on every reconstruction and the
     * recorded history would no longer match.</p>
     */
    private void enter(MissionPhase phase, String detail, int step) {
        snapshot = new MissionSnapshot(
                missionId(),
                droneId(),
                phase,
                snapshot.batteryPct(),
                snapshot.lat(),
                snapshot.lng(),
                snapshot.altitudeM(),
                detail,
                step,
                Workflow.currentTimeMillis());
        safeReport(phase, detail);
    }

    /** Same contract as {@link #enter}, but also carries a fresh position and battery reading. */
    private void record(MissionPhase phase, String detail,
                        double battery, double lat, double lng, double altitudeM) {
        snapshot = new MissionSnapshot(
                missionId(), droneId(), phase,
                battery, lat, lng, altitudeM, detail, snapshot.step(),
                Workflow.currentTimeMillis());
        safeReport(phase, detail);
    }

    /** Null-safe: these fields are only populated once runMission has been given a command. */
    private String missionId() {
        return command == null ? "" : command.missionId();
    }

    private String droneId() {
        return command == null ? "" : command.droneId();
    }

    /**
     * Push a phase to the outside world (registry + Supabase) through an activity.
     *
     * <p>Wrapped defensively: reporting a phase is bookkeeping, and bookkeeping must never be the
     * reason a mission fails.</p>
     */
    private void safeReport(MissionPhase phase, String detail) {
        if (command == null) {
            return;
        }
        try {
            activities.reportPhase(command, phase.name(), detail);
        } catch (RuntimeException e) {
            log().warn("Could not report phase {} for mission {}: {}",
                    phase, command.missionId(), e.getMessage());
        }
    }

    /**
     * A deterministic logger. Use {@code Workflow.getLogger} rather than a static SLF4J logger:
     * Temporal's logger marks messages with the workflow id and run attempt, so you can tell which
     * replay pass produced a line.
     */
    private static org.slf4j.Logger log() {
        return Workflow.getLogger(DroneMissionWorkflowImpl.class);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor.getCause() != null && cursor.getCause() != cursor && depth++ < 20) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return cursor.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
