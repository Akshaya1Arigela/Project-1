package com.aerosaga.domain;

import java.util.List;

/**
 * One drone delivering to several stops in a single flight.
 *
 * <p>Week 2's {@link MissionCommand} has exactly one destination, which is fine for a single
 * parcel. A real depot run drops six parcels on one battery, so Week 3 needs a route: take off
 * once, visit the stops in an optimised order, come home once.</p>
 *
 * <p>Like every object that crosses into a workflow, this is a record — immutable and
 * serialisable, because Temporal writes it into the event history.</p>
 *
 * @param missionId            unique mission id, also the Temporal workflow id suffix
 * @param droneId              airframe, e.g. {@code DRN-07}
 * @param originLabel          depot name
 * @param originLat            depot latitude
 * @param originLng            depot longitude
 * @param stops                unordered delivery stops; the workflow asks an activity to sort them
 * @param payloadKg            total parcel mass on board
 * @param lowBatteryThresholdPct below this the workflow abandons the remaining stops and flies home
 */
public record RouteCommand(
        String missionId,
        String droneId,
        String originLabel,
        double originLat,
        double originLng,
        List<Waypoint> stops,
        double payloadKg,
        double lowBatteryThresholdPct) {

    public RouteCommand {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("missionId is required");
        }
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("droneId is required");
        }
        stops = stops == null ? List.of() : List.copyOf(stops);
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one stop");
        }
        if (payloadKg <= 0) {
            payloadKg = 1.0;
        }
        if (lowBatteryThresholdPct <= 0) {
            lowBatteryThresholdPct = 25.0;
        }
    }

    /**
     * A single-stop command view, so the existing Week 2 activities (takeoff, navigate, drop,
     * return, compensate) can be reused unchanged for every leg of the route.
     */
    public MissionCommand missionFor(Waypoint stop) {
        return new MissionCommand(
                missionId,
                droneId,
                originLabel,
                originLat,
                originLng,
                List.of(),
                stop.label(),
                stop.lat(),
                stop.lng(),
                payloadKg,
                lowBatteryThresholdPct,
                7200);
    }
}
