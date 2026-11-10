package com.aerosaga.domain;

/**
 * A read-only view of a running mission.
 *
 * <p>Returned by the workflow's {@code @QueryMethod}, so it is what the dashboard polls to know
 * where every mission is. Everything the UI needs is denormalised into this one record — the UI
 * never has to ask Temporal two questions.</p>
 *
 * @param missionId  matches {@link MissionCommand#missionId()}
 * @param droneId    the airframe
 * @param phase      current lifecycle phase
 * @param batteryPct last known state of charge
 * @param lat        last known latitude
 * @param lng        last known longitude
 * @param altitudeM  last known altitude
 * @param detail     human-readable "what is it doing right now" line for the UI
 * @param step       which step of the saga we are on, 1-based
 * @param updatedAtEpochMs {@code Workflow.currentTimeMillis()} at the last state change
 */
public record MissionSnapshot(
        String missionId,
        String droneId,
        MissionPhase phase,
        double batteryPct,
        double lat,
        double lng,
        double altitudeM,
        String detail,
        int step,
        long updatedAtEpochMs) {

    /**
     * The snapshot a workflow holds before it has been given a command.
     *
     * <p>Built inline rather than from a {@code MissionCommand}, because a workflow's fields are
     * initialised when Temporal instantiates the class — before {@code runMission} runs. Passing a
     * fake command through the constructor would throw there.</p>
     */
    public static MissionSnapshot initial() {
        return new MissionSnapshot("", "", MissionPhase.QUEUED, 100.0, 0.0, 0.0, 0.0,
                "Workflow created, awaiting start", 0, 0L);
    }

    public static MissionSnapshot queued(MissionCommand cmd) {
        return new MissionSnapshot(
                cmd.missionId(),
                cmd.droneId(),
                MissionPhase.QUEUED,
                100.0,
                cmd.originLat(),
                cmd.originLng(),
                0.0,
                "Waiting for a worker to pick up the workflow task",
                0,
                0L);
    }
}
