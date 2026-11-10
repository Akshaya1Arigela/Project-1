package com.aerosaga.domain;

import java.util.List;

/**
 * Dispatch several drones at once.
 *
 * <p>The workflow running this fans out to one child {@code DroneMissionWorkflow} per mission and
 * waits on {@code Promise.allOf(...)} — see {@code FleetMissionWorkflowImpl}.</p>
 *
 * @param missions the missions to run in parallel
 */
public record FleetCommand(List<MissionCommand> missions) {

    public FleetCommand {
        missions = missions == null ? List.of() : List.copyOf(missions);
        if (missions.isEmpty()) {
            throw new IllegalArgumentException("fleet dispatch needs at least one mission");
        }
    }
}
