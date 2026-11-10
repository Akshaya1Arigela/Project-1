package com.aerosaga.domain;

import java.util.List;

/**
 * A recurring patrol: fly the same circuit every {@code intervalSeconds}, for {@code cycles} laps,
 * then stop.
 *
 * <p>This is the Week 3 answer to "how does a fleet work without a cron job?". A cron entry would
 * live outside the system, fire blind, and forget everything between ticks. Here the whole patrol
 * is one long-running Temporal workflow whose waits are durable timers in the event history — a
 * restart resumes the countdown instead of resetting it.</p>
 *
 * @param patrolId        stable id; child missions are named {@code patrolId-c1}, {@code -c2}, ...
 * @param droneId         the airframe flying the circuit
 * @param originLabel     depot name
 * @param originLat       depot latitude
 * @param originLng       depot longitude
 * @param intervalSeconds gap between two laps
 * @param cycles          how many laps to fly before stopping
 * @param stops           the circuit; each lap delivers to exactly one stop, round-robin
 * @param payloadKg       parcel mass per drop
 */
public record PatrolCommand(
        String patrolId,
        String droneId,
        String originLabel,
        double originLat,
        double originLng,
        int intervalSeconds,
        int cycles,
        List<Waypoint> stops,
        double payloadKg) {

    public PatrolCommand {
        if (patrolId == null || patrolId.isBlank()) {
            throw new IllegalArgumentException("patrolId is required");
        }
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("droneId is required");
        }
        stops = stops == null ? List.of() : List.copyOf(stops);
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a patrol needs at least one stop");
        }
        if (intervalSeconds < 5) {
            // A five-second floor keeps a demo from flooding the task queue with missions that
            // cost ~40 seconds of animated flight each.
            intervalSeconds = 5;
        }
        if (cycles < 1) {
            cycles = 3;
        }
        if (payloadKg <= 0) {
            payloadKg = 0.8;
        }
    }

    /** The stop for lap {@code cycle} (1-based), round-robin through the circuit. */
    public Waypoint stopForCycle(int cycle) {
        return stops.get(Math.floorMod(cycle - 1, stops.size()));
    }

    /**
     * The child mission for one lap. The id is derived, not random: a replay must produce the same
     * id, otherwise a restart would start a second copy of the same lap.
     */
    public MissionCommand missionForCycle(int cycle) {
        Waypoint stop = stopForCycle(cycle);
        return new MissionCommand(
                patrolId + "-c" + cycle,
                droneId,
                originLabel,
                originLat,
                originLng,
                List.of(),
                stop.label(),
                stop.lat(),
                stop.lng(),
                payloadKg,
                25.0,
                7200);
    }
}
