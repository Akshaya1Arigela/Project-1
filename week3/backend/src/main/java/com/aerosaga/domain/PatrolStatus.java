package com.aerosaga.domain;

/**
 * Live state of a patrol workflow, returned by its {@code @QueryMethod}.
 *
 * <p>Note there is no database anywhere behind this: a patrol that is currently waiting fifteen
 * minutes between laps is a parked workflow, and asking it for its status is a query against that
 * parked object.</p>
 */
public record PatrolStatus(
        String patrolId,
        String droneId,
        int cyclesRequested,
        int cyclesCompleted,
        boolean active,
        String phaseLabel,
        long nextCycleInSeconds,
        String lastMissionId,
        MissionPhase lastMissionPhase,
        String detail,
        long updatedAt) {

    public static PatrolStatus initial(PatrolCommand command) {
        return new PatrolStatus(
                command.patrolId(), command.droneId(), command.cycles(), 0, true,
                "STARTING", 0L, null, MissionPhase.QUEUED, "Patrol accepted", 0L);
    }

    public PatrolStatus flyingLap(int cycle, String missionId, long at) {
        return new PatrolStatus(
                patrolId, droneId, cyclesRequested, cyclesCompleted, true,
                "FLYING_LAP_" + cycle, 0L, missionId, MissionPhase.NAVIGATING,
                "Lap " + cycle + " airborne to " + "stop", at);
    }

    public PatrolStatus lapFinished(int cycle, String missionId, MissionPhase phase,
                                    long at, String detail) {
        return new PatrolStatus(
                patrolId, droneId, cyclesRequested, Math.max(cyclesCompleted, cycle), true,
                "BETWEEN_LAPS", 0L, missionId, phase, detail, at);
    }

    public PatrolStatus waiting(long seconds, int nextCycle, long at) {
        return new PatrolStatus(
                patrolId, droneId, cyclesRequested, cyclesCompleted, true,
                "WAITING_FOR_LAP_" + nextCycle, seconds, lastMissionId, lastMissionPhase,
                "Durable timer parked for " + seconds + "s", at);
    }

    public PatrolStatus finished(String label, String detail, long at) {
        return new PatrolStatus(
                patrolId, droneId, cyclesRequested, cyclesCompleted, false,
                label, 0L, lastMissionId, lastMissionPhase, detail, at);
    }
}
