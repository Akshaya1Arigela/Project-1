package com.aerosaga.domain;

/**
 * One airframe as the supervisor sees it.
 *
 * <p>Produced by the {@code scanFleet} activity from the telemetry engine, so a workflow can look
 * at the fleet without touching Spring, a socket or a database. Everything here is a value taken
 * at one instant — the workflow is not allowed to ask the world for "now" twice.</p>
 *
 * @param droneId        airframe id, e.g. {@code DRN-07}
 * @param callsign       display name
 * @param phase          mission phase the airframe last reported
 * @param missionId      mission in flight, or {@code null} when parked
 * @param workflowId     the Temporal workflow that owns that mission, when known
 * @param linkUp         is the radio working?
 * @param batteryPct     state of charge
 * @param lastSeenMillis epoch millis of the last telemetry write
 * @param status         registry status: {@code idle}, {@code charging}, {@code on-mission}…
 */
public record DroneHealth(
        String droneId,
        String callsign,
        MissionPhase phase,
        String missionId,
        String workflowId,
        boolean linkUp,
        double batteryPct,
        long lastSeenMillis,
        String status) {

    public boolean onMission() {
        return missionId != null && !missionId.isBlank();
    }

    /** How long the radio has been silent, at the instant the scan was taken. */
    public long silentForMillis(long scanTimeMillis) {
        return Math.max(0L, scanTimeMillis - lastSeenMillis);
    }
}
