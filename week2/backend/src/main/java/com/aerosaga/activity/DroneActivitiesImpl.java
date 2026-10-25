package com.aerosaga.activity;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.Waypoint;
import com.aerosaga.mission.MissionRecorder;
import com.aerosaga.telemetry.TelemetryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Where the real world happens.
 *
 * <p>Every method here is allowed to block, sleep, throw and do I/O. Compare that with the
 * workflow class, which may do none of those things. That contrast is the single most important
 * idea in Temporal, and this class is the "allowed" half of it.</p>
 *
 * <p>The {@code navigate} method is the interesting one: it walks the drone across the map in
 * 500 ms steps and checks the radio on every step. When the radio is gone it throws
 * {@link DroneOfflineException}, which is what tips the workflow into its durable suspension.</p>
 */
@Component
public class DroneActivitiesImpl implements DroneActivities {

    private static final Logger log = LoggerFactory.getLogger(DroneActivitiesImpl.class);

    private static final long LEG_TICK_MS = 500;
    private static final double CRUISE_SPEED_MPS = 24.0;
    private static final double BURN_PER_SECOND = 0.55;
    /** Simulated cells never report below this — a negative battery would look broken on screen. */
    private static final double BATTERY_FLOOR_PCT = 4.0;

    private final TelemetryEngine telemetry;
    private final MissionRecorder recorder;

    public DroneActivitiesImpl(TelemetryEngine telemetry, MissionRecorder recorder) {
        this.telemetry = telemetry;
        this.recorder = recorder;
    }

    @Override
    public TakeoffResult takeoff(MissionCommand command) {
        log.info("[ACTIVITY] takeoff drone={} origin=({}, {})",
                command.droneId(), command.originLat(), command.originLng());
        telemetry.claimForMission(command.droneId(), command.missionId(), MissionPhase.TAKEOFF);

        double battery = currentBattery(command.droneId(), 100.0);

        // Climb to hover altitude in a few visible steps so the marker lifts off the ground.
        for (int step = 1; step <= 8; step++) {
            requireLink(command.droneId());
            double altitude = 70.0 * step / 8.0;
            battery -= 0.15 * telemetry.weatherDrainFactor(command.droneId());
            telemetry.updatePosition(command.droneId(), command.originLat(), command.originLng(),
                    altitude, battery);
            sleep(350);
        }

        log.info("[ACTIVITY] takeoff complete drone={} altitude=70m", command.droneId());
        return new TakeoffResult(battery, command.originLat(), command.originLng(), 70.0,
                "Airborne at 70 m");
    }

    @Override
    public NavigationResult navigate(MissionCommand command, Waypoint waypoint) {
        TelemetryEngine.Telemetry current = telemetry.get(command.droneId());
        double fromLat = current == null ? command.originLat() : current.lat();
        double fromLng = current == null ? command.originLng() : current.lng();
        double battery = current == null ? 100.0 : current.batteryPct();

        double distanceM = haversineMetres(fromLat, fromLng, waypoint.lat(), waypoint.lng());
        long legMillis = Math.min(Math.max((long) (distanceM / CRUISE_SPEED_MPS * 1000L), 6_000L), 40_000L);
        int ticks = (int) Math.max(2, legMillis / LEG_TICK_MS);

        log.info("[ACTIVITY] navigate drone={} -> {} ({} m, {} ticks)",
                command.droneId(), waypoint.label(), (long) distanceM, ticks);

        telemetry.setPhase(command.droneId(), MissionPhase.NAVIGATING);

        for (int i = 1; i <= ticks; i++) {
            // THE check. A lost link here is what parks the whole workflow durably.
            requireLink(command.droneId());

            double t = (double) i / ticks;
            double lat = fromLat + (waypoint.lat() - fromLat) * t;
            double lng = fromLng + (waypoint.lng() - fromLng) * t;
            double altitude = 70.0 + 40.0 * Math.sin(Math.PI * t); // gentle cruise arc

            // The 6x storm drain makes the low-battery compensation path reachable during a demo,
            // but a battery of -42% is nonsense. Floor it: the reading is believable and the
            // workflow still crosses its 25% threshold and compensates.
            battery = Math.max(BATTERY_FLOOR_PCT,
                    battery - (BURN_PER_SECOND * LEG_TICK_MS / 1000.0)
                            * telemetry.weatherDrainFactor(command.droneId()));
            telemetry.updatePosition(command.droneId(), lat, lng, altitude, battery);
            sleep(LEG_TICK_MS);
        }

        requireLink(command.droneId());
        log.info("[ACTIVITY] navigate complete drone={} battery={}%", command.droneId(), round(battery));
        return new NavigationResult(battery, waypoint.lat(), waypoint.lng(), waypoint.altitudeM(),
                "Arrived at " + waypoint.label());
    }

    @Override
    public DropResult dropParcel(MissionCommand command) {
        telemetry.setPhase(command.droneId(), MissionPhase.DROPPING);
        log.info("[ACTIVITY] dropParcel drone={} payload={}kg",
                command.droneId(), command.payloadKg());
        requireLink(command.droneId());
        sleep(2_500);

        double battery = currentBattery(command.droneId(), 60.0) - 0.5;
        TelemetryEngine.Telemetry hovering = telemetry.get(command.droneId());
        if (hovering != null) {
            telemetry.updatePosition(command.droneId(), hovering.lat(), hovering.lng(), 70.0, battery);
        }

        log.info("[ACTIVITY] parcel delivered for drone={}", command.droneId());
        return new DropResult(true, battery, "Parcel released at " + command.destinationLabel());
    }

    @Override
    public ReturnResult returnToBase(MissionCommand command) {
        telemetry.setPhase(command.droneId(), MissionPhase.RETURNING_TO_BASE);
        log.info("[ACTIVITY] returnToBase drone={}", command.droneId());

        TelemetryEngine.Telemetry current = telemetry.get(command.droneId());
        double battery = current == null ? 50.0 : current.batteryPct();

        double distanceM = haversineMetres(
                current == null ? command.originLat() : current.lat(),
                current == null ? command.originLng() : current.lng(),
                telemetry.homeLat(), telemetry.homeLng());
        long legMillis = Math.min(Math.max((long) (distanceM / CRUISE_SPEED_MPS * 1000L), 5_000L), 40_000L);
        int ticks = (int) Math.max(2, legMillis / LEG_TICK_MS);

        double fromLat = current == null ? command.originLat() : current.lat();
        double fromLng = current == null ? command.originLng() : current.lng();

        for (int i = 1; i <= ticks; i++) {
            double t = (double) i / ticks;
            double lat = fromLat + (telemetry.homeLat() - fromLat) * t;
            double lng = fromLng + (telemetry.homeLng() - fromLng) * t;
            double altitude = Math.max(0, 70.0 * (1 - t));
            battery = Math.max(BATTERY_FLOOR_PCT,
                    battery - (BURN_PER_SECOND * LEG_TICK_MS / 1000.0)
                            * telemetry.weatherDrainFactor(command.droneId()));
            telemetry.updatePosition(command.droneId(), lat, lng, altitude, battery);
            sleep(LEG_TICK_MS);
        }

        log.info("[ACTIVITY] landed at base drone={} battery={}%", command.droneId(), round(battery));
        return new ReturnResult(battery, telemetry.homeLat(), telemetry.homeLng(), "Landed at base");
    }

    /**
     * COMPENSATION. In a real logistics saga this also cancels the payment, releases the reserved
     * parcel back to inventory and texts the customer. The structure is what matters: this runs
     * <em>instead of</em> the delivery, in reverse order of the steps that already happened.
     */
    @Override
    public void compensateReturnToBase(MissionCommand command, String reason) {
        telemetry.setPhase(command.droneId(), MissionPhase.COMPENSATING);
        log.warn("[ACTIVITY] COMPENSATING drone={} reason={}", command.droneId(), reason);

        ReturnResult home = returnToBase(command);
        telemetry.release(command.droneId(), "charging");

        log.warn("[ACTIVITY] compensation complete drone={} parcel={} delivered={} battery={}%",
                command.droneId(),
                command.destinationLabel(),
                "NO",
                round(home.batteryPct()));
    }

    @Override
    public void reportPhase(MissionCommand command, String phase, String detail) {
        MissionPhase phaseEnum;
        try {
            phaseEnum = MissionPhase.valueOf(phase);
        } catch (RuntimeException e) {
            phaseEnum = MissionPhase.FAILED;
        }
        TelemetryEngine.Telemetry current = telemetry.get(command.droneId());
        recorder.recordPhase(
                command,
                phaseEnum,
                current == null ? null : current.batteryPct(),
                current == null ? null : current.lat(),
                current == null ? null : current.lng(),
                detail);
        telemetry.setPhase(command.droneId(), phaseEnum);
        if (MissionRecorder.isTerminal(phaseEnum)) {
            telemetry.release(command.droneId(),
                    phaseEnum == MissionPhase.DELIVERED ? "idle" : "charging");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Throw if this airframe's radio is down. Called on every animation tick so the workflow
     * learns about the outage within half a second.
     */
    private void requireLink(String droneId) {
        if (!telemetry.isLinkUp(droneId)) {
            TelemetryEngine.Telemetry state = telemetry.get(droneId);
            telemetry.setPhase(droneId, MissionPhase.OFFLINE_SUSPENDED);
            throw new DroneOfflineException(droneId,
                    "last known position "
                            + (state == null ? "unknown" : round(state.lat()) + "," + round(state.lng())));
        }
    }

    private double currentBattery(String droneId, double fallback) {
        TelemetryEngine.Telemetry state = telemetry.get(droneId);
        return state == null ? fallback : state.batteryPct();
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Activity interrupted", e);
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** Great-circle distance in metres. */
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
