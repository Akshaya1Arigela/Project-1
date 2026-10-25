package com.aerosaga.activity;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.Waypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Week 1 activity implementation.
 *
 * <p>There is no real drone here, so the "hardware" is a stub that sleeps and returns an
 * interpolated position. That is deliberate: the shape of the class is exactly what a real
 * integration would be, and it shows the important property of activities —
 * <b>they are allowed to block, sleep and do I/O</b>. Workflow code is not.</p>
 *
 * <p>When you hook up real hardware this is the only class that changes. The workflow above it
 * stays byte-for-byte identical.</p>
 */
@Component
public class DroneActivitiesImpl implements DroneActivities {

    private static final Logger log = LoggerFactory.getLogger(DroneActivitiesImpl.class);

    /** Simulated cruise speed in m/s, used only to give the demo a believable duration. */
    private static final double CRUISE_SPEED_MPS = 22.0;

    @Override
    public TakeoffResult takeoff(MissionCommand command) {
        log.info("[ACTIVITY] takeoff drone={} origin=({}, {}) payload={}kg",
                command.droneId(), command.originLat(), command.originLng(), command.payloadKg());

        // Real version: arm motors, wait for GPS lock, climb to hover altitude.
        sleepQuietly(4_000);

        double hoverAltitude = 60.0;
        log.info("[ACTIVITY] takeoff complete drone={} altitude={}m", command.droneId(), hoverAltitude);
        return new TakeoffResult(
                98.0,
                command.originLat(),
                command.originLng(),
                hoverAltitude,
                "Airborne at " + hoverAltitude + "m");
    }

    @Override
    public NavigationResult navigate(MissionCommand command, Waypoint waypoint) {
        double distanceM = haversineMetres(
                command.originLat(), command.originLng(), waypoint.lat(), waypoint.lng());

        // Capped so a long demo route does not turn into a 10-minute test.
        long flightMillis = Math.min(Math.max((long) (distanceM / CRUISE_SPEED_MPS * 1000L), 5_000L), 45_000L);

        log.info("[ACTIVITY] navigate drone={} -> {} ({} m, ~{} s)",
                command.droneId(), waypoint.label(), (long) distanceM, flightMillis / 1000);

        // Simulate the cruise. A real deployment streams telemetry back from the airframe here
        // and returns when the drone reports "arrived".
        sleepQuietly(flightMillis);

        double battery = Math.max(12.0, 98.0 - (flightMillis / 1000.0) * 0.35);
        log.info("[ACTIVITY] navigate complete drone={} battery={}", command.droneId(), battery);

        return new NavigationResult(
                battery,
                waypoint.lat(),
                waypoint.lng(),
                waypoint.altitudeM(),
                "Arrived at " + waypoint.label());
    }

    // ------------------------------------------------------------------ helpers

    private static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Activity interrupted", e);
        }
    }

    /** Great-circle distance in metres, so the simulated flight time is roughly realistic. */
    static double haversineMetres(double lat1, double lng1, double lat2, double lng2) {
        final double radius = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * radius * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
