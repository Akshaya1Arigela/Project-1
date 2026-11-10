package com.aerosaga.activity;

import com.aerosaga.domain.DroneHealth;
import com.aerosaga.domain.FleetScan;
import com.aerosaga.domain.Incident;
import com.aerosaga.mission.MissionIndex;
import com.aerosaga.supabase.SupabaseClient;
import com.aerosaga.supervisor.IncidentLog;
import com.aerosaga.telemetry.TelemetryEngine;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The supervisor's hands.
 *
 * <p>The interesting method is {@link #orderReturnHome}: the supervisor does not know how to fly
 * a drone, and it should not. It sends the same {@code abort} signal an operator would send from
 * the dashboard, and the mission workflow — which already knows how to compensate — does the
 * flying. That is the difference between an orchestrator and a remote control.</p>
 */
@Component
public class FleetActivitiesImpl implements FleetActivities {

    private static final Logger log = LoggerFactory.getLogger(FleetActivitiesImpl.class);

    private final TelemetryEngine telemetry;
    private final MissionIndex index;
    private final IncidentLog incidents;
    private final SupabaseClient supabase;
    private final WorkflowClient workflowClient;

    public FleetActivitiesImpl(
            TelemetryEngine telemetry,
            MissionIndex index,
            IncidentLog incidents,
            SupabaseClient supabase,
            WorkflowClient workflowClient) {
        this.telemetry = telemetry;
        this.index = index;
        this.incidents = incidents;
        this.supabase = supabase;
        this.workflowClient = workflowClient;
    }

    @Override
    public FleetScan scanFleet() {
        List<TelemetryEngine.Telemetry> frames = telemetry.snapshot();
        List<DroneHealth> health = new ArrayList<>(frames.size());

        for (TelemetryEngine.Telemetry frame : frames) {
            String workflowId = null;
            if (frame.missionId() != null && !frame.missionId().isBlank()) {
                // Known missions have their exact workflow id in the index. Missions started by a
                // parent workflow (a patrol lap, a fleet child) were never seen by this process,
                // but they all follow the "mission-<id>" convention, so we can still reach them.
                workflowId = index.workflowId(frame.missionId());
                if (workflowId == null) {
                    workflowId = "mission-" + frame.missionId();
                }
            }
            health.add(new DroneHealth(
                    frame.id(),
                    frame.callsign(),
                    frame.phase(),
                    frame.missionId(),
                    workflowId,
                    frame.linkUp(),
                    frame.batteryPct(),
                    frame.updatedAt(),
                    frame.status()));
        }

        return new FleetScan(System.currentTimeMillis(), telemetry.isStormActive(), health);
    }

    @Override
    public void recordIncident(Incident incident) {
        incidents.record(incident);
        log.warn("[SUPERVISOR] {} {} drone={} mission={} action={} — {}",
                incident.severity(), incident.kind(), incident.droneId(),
                incident.missionId(), incident.action(), incident.detail());

        if (!supabase.isEnabled()) {
            return;
        }
        // LinkedHashMap, not Map.of: droneId and missionId are legitimately null on fleet-wide
        // incidents and Map.of would throw on them.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("incident_id", incident.id());
        row.put("drone_id", incident.droneId());
        row.put("mission_id", incident.missionId());
        row.put("severity", incident.severity());
        row.put("kind", incident.kind());
        row.put("detail", incident.detail());
        row.put("action", incident.action());
        row.put("raised_at", Instant.ofEpochMilli(incident.at()).toString());
        supabase.upsert("incidents", row);
    }

    @Override
    public void orderReturnHome(String workflowId, String droneId, String reason) {
        // Untyped on purpose: the target may be a DroneMissionWorkflow or a RouteMissionWorkflow,
        // and both expose the same abort signal. One signal name, two workflow types.
        WorkflowStub stub = workflowClient.newUntypedWorkflowStub(workflowId);
        stub.signal("abort", reason);
        log.info("[SUPERVISOR] Sent abort/return-home to workflow {} (drone {}) — {}",
                workflowId, droneId, reason);
    }
}
