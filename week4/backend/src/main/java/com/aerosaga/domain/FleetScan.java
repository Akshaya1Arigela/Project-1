package com.aerosaga.domain;

import java.util.List;

/**
 * One complete look at the fleet, taken by the {@code scanFleet} activity.
 *
 * <p>The supervisor workflow gets exactly one of these per tick. Because the scan is an activity
 * result, it is written into Temporal's history: after a replay the workflow sees the same fleet
 * it saw the first time, instead of silently scanning a different world and branching differently.
 * That is the same rule Week 3 used for route planning.</p>
 *
 * @param scannedAt   when the snapshot was taken, from the activity — never {@code System.currentTimeMillis()} in workflow code
 * @param stormActive whether the simulator's storm corridor is active
 * @param drones      one entry per registered airframe
 */
public record FleetScan(long scannedAt, boolean stormActive, List<DroneHealth> drones) {

    public FleetScan {
        drones = drones == null ? List.of() : List.copyOf(drones);
    }

    public int size() {
        return drones.size();
    }

    public long onMission() {
        return drones.stream().filter(DroneHealth::onMission).count();
    }

    public long offline() {
        return drones.stream().filter(d -> !d.linkUp()).count();
    }
}
