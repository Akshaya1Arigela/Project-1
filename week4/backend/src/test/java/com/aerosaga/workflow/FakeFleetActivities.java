package com.aerosaga.workflow;

import com.aerosaga.activity.FleetActivities;
import com.aerosaga.domain.DroneHealth;
import com.aerosaga.domain.FleetScan;
import com.aerosaga.domain.Incident;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The supervisor's world, faked.
 *
 * <p>Only the activities are replaced. The workflow itself — the loop, the dedupe, the continue-as-
 * new arithmetic — is the real class, so these tests would catch a real regression in the thing
 * that matters.</p>
 *
 * <p>Every scan gets a fresh {@code scannedAt}, which is what lets a test say "this drone has been
 * silent for a minute" without waiting a minute.</p>
 */
class FakeFleetActivities implements FleetActivities {

    /** Airframes the next scan will report. Mutate between scans if a test needs a change. */
    final List<DroneHealth> drones = new ArrayList<>();

    /** Force the next scan to throw, to exercise the blind-supervisor path. */
    volatile boolean failScan;

    volatile boolean stormActive;

    final List<Incident> recorded = new CopyOnWriteArrayList<>();
    final List<String> returnHomeRequests = new CopyOnWriteArrayList<>();

    @Override
    public FleetScan scanFleet() {
        if (failScan) {
            throw new IllegalStateException("telemetry engine unreachable");
        }
        return new FleetScan(System.currentTimeMillis(), stormActive, new ArrayList<>(drones));
    }

    @Override
    public void recordIncident(Incident incident) {
        recorded.add(incident);
    }

    @Override
    public void orderReturnHome(String workflowId, String droneId, String reason) {
        returnHomeRequests.add(workflowId);
    }
}
