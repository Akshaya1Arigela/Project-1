package com.aerosaga.workflow;

import com.aerosaga.activity.DroneActivities;
import com.aerosaga.activity.DroneActivities.NavigationResult;
import com.aerosaga.activity.DroneActivities.TakeoffResult;
import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.domain.Waypoint;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * WEEK 1 version — deliberately small.
 *
 * <p>Two steps only: {@code Takeoff} then a single {@code Navigate} leg to the delivery point.
 * Everything that could go wrong is still handled here (retries, aborts, a thrown activity
 * failure), but there is no compensation and no offline suspension yet — that is Week 2's job.</p>
 *
 * <p>The important lesson this class teaches: <b>this object's fields are the durable state.</b>
 * Temporal serialises them into the event history after every step. Kill the JVM right now and
 * these exact values come back when you restart.</p>
 */
public class DroneMissionWorkflowImpl implements DroneMissionWorkflow {

    /**
     * Stubs are created once, at the top of the class, with {@link Workflow} factory methods —
     * never with {@code new}. Creating it inside the method body would break replay.
     */
    private final DroneActivities activities = Workflow.newActivityStub(
            DroneActivities.class,
            ActivityOptions.newBuilder()
                    // One attempt may run for up to 2 minutes (the navigate activity animates the
                    // drone across the map, so it legitimately takes a while).
                    .setStartToCloseTimeout(Duration.ofMinutes(3))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            // 5 attempts total, then the workflow sees the failure.
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    private MissionCommand command;
    // Initialised at construction time, before runMission() is ever called.
    private MissionSnapshot snapshot = MissionSnapshot.initial();
    private boolean abortRequested;
    private String abortReason;

    @Override
    public MissionSnapshot runMission(MissionCommand cmd) {
        this.command = cmd;
        enter(MissionPhase.TAKEOFF, "Spooling rotors at " + cmd.originLabel(), 1);

        try {
            TakeoffResult airborne = activities.takeoff(cmd);
            update(airborne.batteryPct(), airborne.lat(), airborne.lng(), airborne.altitudeM());

            enter(MissionPhase.NAVIGATING, "Cruising to " + cmd.destinationLabel(), 2);
            Waypoint destination = new Waypoint(
                    cmd.destinationLabel(), cmd.destLat(), cmd.destLng(), 120.0);
            NavigationResult nav = activities.navigate(cmd, destination);
            update(nav.batteryPct(), nav.lat(), nav.lng(), nav.altitudeM());

            if (nav.batteryPct() < cmd.lowBatteryThresholdPct()) {
                // Week 1: report it and stop. Week 2: this is where compensation goes.
                enter(MissionPhase.FAILED,
                        "Battery " + nav.batteryPct() + "% is below the "
                                + cmd.lowBatteryThresholdPct() + "% threshold", 3);
            } else {
                enter(MissionPhase.DELIVERED, "Arrived at " + cmd.destinationLabel(), 3);
            }
        } catch (ActivityFailure e) {
            // Retries are exhausted. The workflow survives; the mission does not.
            // NOTE the type: an activity stub throws io.temporal.failure.ActivityFailure, not
            // io.temporal.client.ActivityFailedException — catching the latter compiles but never
            // matches, and the raw failure escapes the workflow instead of being recorded.
            Workflow.getLogger(getClass()).error(
                    "Mission {} failed at phase {}", cmd.missionId(), snapshot.phase(), e);
            enter(MissionPhase.FAILED, "Activity failed: " + rootMessage(e), snapshot.step() + 1);
        }

        return snapshot;
    }

    @Override
    public MissionSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void abort(String reason) {
        this.abortRequested = true;
        this.abortReason = reason == null ? "operator request" : reason;
        enter(MissionPhase.ABORTED, "Aborted: " + abortReason, snapshot.step() + 1);
    }

    // ------------------------------------------------------------------ helpers

    /** Reads a snapshot field and timestamps it with Temporal's clock, never the system clock. */
    private void enter(MissionPhase phase, String detail, int step) {
        snapshot = new MissionSnapshot(
                command == null ? "" : command.missionId(),
                command == null ? "" : command.droneId(),
                phase,
                snapshot.batteryPct(),
                snapshot.lat(),
                snapshot.lng(),
                snapshot.altitudeM(),
                detail,
                step,
                Workflow.currentTimeMillis());
    }

    private void update(double battery, double lat, double lng, double altitudeM) {
        snapshot = new MissionSnapshot(
                snapshot.missionId(),
                snapshot.droneId(),
                snapshot.phase(),
                battery,
                lat,
                lng,
                altitudeM,
                snapshot.detail(),
                snapshot.step(),
                Workflow.currentTimeMillis());
    }

    private static String rootMessage(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return cursor.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // Keep the unused-field warnings honest for the abort flag until Week 2 wires it up.
    @SuppressWarnings("unused")
    private boolean isAborted() {
        return abortRequested;
    }
}
