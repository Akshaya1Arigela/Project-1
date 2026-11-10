package com.aerosaga.mission;

/** Raised when a mission id does not resolve to a workflow this process can reach. */
public class MissionNotFoundException extends RuntimeException {

    public MissionNotFoundException(String missionId) {
        super("No mission found with id '" + missionId + "'");
    }

    public MissionNotFoundException(String missionId, Throwable cause) {
        super("Mission '" + missionId + "' is not queryable", cause);
    }
}
