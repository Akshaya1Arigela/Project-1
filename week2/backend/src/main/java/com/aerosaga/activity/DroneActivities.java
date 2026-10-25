package com.aerosaga.activity;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.Waypoint;
import io.temporal.activity.ActivityInterface;

/**
 * The side-effect boundary. Six activities, which together are the entire real world.
 *
 * <p>Temporal persists every result, so an activity that already succeeded is never re-run when
 * the worker restarts — it replays the stored result instead. That is what makes the
 * kill-and-restart audit in the README safe.</p>
 */
@ActivityInterface
public interface DroneActivities {

    /** Spool up and leave the pad. */
    TakeoffResult takeoff(MissionCommand command);

    /**
     * Fly to one waypoint, streaming telemetry as it goes.
     *
     * @throws com.aerosaga.activity.DroneOfflineException if the link drops mid-leg
     */
    NavigationResult navigate(MissionCommand command, Waypoint waypoint);

    /** Hover over the destination and release the parcel. */
    DropResult dropParcel(MissionCommand command);

    /** Fly home and land. */
    ReturnResult returnToBase(MissionCommand command);

    /**
     * COMPENSATION step. Runs when the mission has to be undone — low battery, abort, or a
     * failed delivery. In a real saga this would also reverse the payment, release the reserved
     * parcel back to inventory, and notify the customer. Here it flies the drone home and
     * cancels the delivery.
     */
    void compensateReturnToBase(MissionCommand command, String reason);

    /**
     * Report a phase transition. This is how the workflow — which may not touch I/O itself —
     * pushes state to the drone registry (for the dashboard) and to Supabase (for the audit
     * trail). Called at every phase change, so it must stay cheap and idempotent.
     */
    void reportPhase(MissionCommand command, String phase, String detail);

    record TakeoffResult(double batteryPct, double lat, double lng, double altitudeM, String message) {
    }

    record NavigationResult(double batteryPct, double lat, double lng, double altitudeM, String message) {
    }

    record DropResult(boolean delivered, double batteryPct, String message) {
    }

    record ReturnResult(double batteryPct, double lat, double lng, String message) {
    }
}
