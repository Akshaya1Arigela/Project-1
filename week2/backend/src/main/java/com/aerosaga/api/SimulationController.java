package com.aerosaga.api;

import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.mission.MissionService;
import com.aerosaga.telemetry.TelemetryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fault injection, so the resiliency demo is a button press rather than a manual code edit.
 *
 * <p>This is a <b>simulation</b> control plane, not part of the production design. In a real
 * deployment the link status would come from the airframe's own telemetry; here the dashboard
 * toggles it so you can reproduce the storm from the problem statement on demand.</p>
 */
@RestController
@RequestMapping("/api/simulate")
public class SimulationController {

    private static final Logger log = LoggerFactory.getLogger(SimulationController.class);

    private final TelemetryEngine telemetry;
    private final MissionService missions;

    public SimulationController(TelemetryEngine telemetry, MissionService missions) {
        this.telemetry = telemetry;
        this.missions = missions;
    }

    /**
     * Start or clear the storm.
     *
     * <p>While active, any drone physically inside the storm corridor reports a dead radio. The
     * {@code navigate} activity notices on its next tick and throws, which tips the workflow into
     * its durable OFFLINE_SUSPENDED state.</p>
     */
    @PostMapping("/storm")
    public Map<String, Object> storm(@RequestBody(required = false) StormRequest request) {
        boolean active = request == null || request.active() == null || request.active();
        telemetry.setStormActive(active);
        if (!active) {
            reconnectSuspended("storm cleared");
        }
        return status("Storm " + (active ? "activated" : "cleared"));
    }

    /** Force one specific drone offline, independent of the weather. */
    @PostMapping("/offline")
    public Map<String, Object> offline(@RequestBody OfflineRequest request) {
        telemetry.setManualOffline(request.droneId(), request.offline() == null || request.offline());
        return status("Drone " + request.droneId() + " link "
                + (request.offline() == null || request.offline() ? "LOST" : "restored"));
    }

    /**
     * Bring every suspended mission back.
     *
     * <p>This is the second half of the demo. Nothing was polling, nothing was retrying, no timer
     * was running — the workflows were parked in Temporal's database. These signals walk each one
     * out of {@code Workflow.await} and back into the navigation loop.</p>
     */
    @PostMapping("/reconnect")
    public Map<String, Object> reconnect() {
        telemetry.setStormActive(false);
        int awakened = reconnectSuspended("operator reconnect");
        return status("Storm cleared, " + awakened + " suspended mission(s) signalled to resume");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stormActive", telemetry.isStormActive());
        body.put("suspendedDrones", telemetry.suspendedDroneIds());
        body.put("openMissions", missions.runningMissionIds());
        return body;
    }

    private int reconnectSuspended(String reason) {
        int signalled = 0;
        // Driven by Temporal, not by this process's memory: after a restart the in-memory
        // drone/mission index is empty, and a reconnect-all that consults it would silently do
        // nothing while three workflows sit parked. The workflow's own query is the truth.
        for (String missionId : missions.runningMissionIds()) {
            try {
                MissionSnapshot snapshot = missions.snapshot(missionId);
                if (snapshot.phase() != MissionPhase.OFFLINE_SUSPENDED) {
                    continue;
                }
                telemetry.setManualOffline(snapshot.droneId(), false);
                telemetry.setPhase(snapshot.droneId(), MissionPhase.NAVIGATING);
                missions.reconnect(missionId);
                signalled++;
                log.info("Woke mission {} for drone {} ({})", missionId, snapshot.droneId(), reason);
            } catch (RuntimeException e) {
                log.warn("Could not wake mission {}: {}", missionId, e.getMessage());
            }
        }
        return signalled;
    }

    private static Map<String, Object> status(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return body;
    }

    record StormRequest(Boolean active) {
    }

    record OfflineRequest(String droneId, Boolean offline) {
    }
}
