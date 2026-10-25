package com.aerosaga.activity;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.Waypoint;
import io.temporal.activity.ActivityInterface;

/**
 * The boundary between deterministic workflow code and the real world.
 *
 * <p>Anything with a side effect — moving a drone, calling Supabase, sleeping, touching the
 * registry the dashboard reads — happens here. Activities may block, throw, and take as long as
 * they like; Temporal retries them and persists their results, so a retry after a JVM restart
 * returns the <em>original</em> result rather than doing the work twice.</p>
 *
 * <p>Nested records are the return types on purpose: they are immutable, serializable, and they
 * show up verbatim in the Temporal Web UI's activity result panel.</p>
 */
@ActivityInterface
public interface DroneActivities {

    /** Spool up and leave the pad. */
    TakeoffResult takeoff(MissionCommand command);

    /** Fly to a waypoint, updating telemetry as it goes. */
    NavigationResult navigate(MissionCommand command, Waypoint waypoint);

    /**
     * @param batteryPct state of charge when the rotors stopped
     * @param lat        where the drone ended up
     * @param lng        where the drone ended up
     * @param altitudeM  hover altitude
     * @param message    log line for the workflow detail string
     */
    record TakeoffResult(double batteryPct, double lat, double lng, double altitudeM, String message) {
    }

    record NavigationResult(double batteryPct, double lat, double lng, double altitudeM, String message) {
    }
}
