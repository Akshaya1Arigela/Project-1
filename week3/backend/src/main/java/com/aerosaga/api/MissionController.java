package com.aerosaga.api;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.config.WorkerStatus;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.drone.DroneRegistry;
import com.aerosaga.mission.MissionIndex;
import com.aerosaga.mission.MissionService;
import com.aerosaga.supabase.SupabaseClient;
import com.aerosaga.supabase.SupabaseMissionRow;
import com.aerosaga.telemetry.TelemetryEngine;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST surface for missions.
 *
 * <p>Note what is <em>not</em> here: no "poll the status" loop, no job table, no
 * {@code WHERE status = 'IN_PROGRESS'}. Starting a mission writes to Temporal's history; reading
 * its state is a query against the live workflow object. That absence is the entire point of the
 * project.</p>
 */
@RestController
@RequestMapping("/api")
public class MissionController {

    private static final Logger log = LoggerFactory.getLogger(MissionController.class);

    private final MissionService missions;
    private final MissionIndex index;
    private final DroneRegistry registry;
    private final TelemetryEngine telemetry;
    private final SupabaseClient supabase;
    private final WorkerStatus workerStatus;

    public MissionController(
            MissionService missions,
            MissionIndex index,
            DroneRegistry registry,
            TelemetryEngine telemetry,
            SupabaseClient supabase,
            WorkerStatus workerStatus) {
        this.missions = missions;
        this.index = index;
        this.registry = registry;
        this.telemetry = telemetry;
        this.supabase = supabase;
        this.workerStatus = workerStatus;
    }

    // ------------------------------------------------------------------ single mission

    /** Start one mission. Returns immediately — the workflow runs on Temporal, not in this thread. */
    @PostMapping("/missions")
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartMissionRequest request) {
        registry.findById(request.droneId());

        String missionId = UUID.randomUUID().toString().substring(0, 8);
        MissionCommand command = request.toCommand(
                missionId, registry.homeBaseLabel(), registry.homeLat(), registry.homeLng());

        missions.start(command);
        index.put(missionId, command);
        log.info("Mission {} accepted for drone {}", missionId, request.droneId());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "missionId", missionId,
                "droneId", command.droneId(),
                "workflowId", "mission-" + missionId,
                "status", "started",
                "detail", "Workflow history written — this mission now lives inside Temporal"));
    }

    /**
     * Live state of one mission, straight from the workflow's {@code @QueryMethod}.
     * Poll this while a mission runs and watch {@code phase} and {@code step} advance.
     */
    @GetMapping("/missions/{missionId}")
    public MissionSnapshot getOne(@PathVariable String missionId) {
        return missions.snapshot(missionId);
    }

    @GetMapping("/missions")
    public List<MissionSnapshot> list() {
        return missions.list();
    }

    /** Operator abort, delivered as a Temporal signal so it survives a process restart. */
    @PostMapping("/missions/{missionId}/abort")
    public MissionSnapshot abort(@PathVariable String missionId) {
        missions.abort(missionId, "operator request from REST API");
        return missions.snapshot(missionId);
    }

    /**
     * Walk a mission out of its OFFLINE_SUSPENDED state.
     *
     * <p>This is the "the drone reconnected two hours later" call from the problem statement. The
     * workflow was parked on a durable {@code Workflow.await(...)}; this signal wakes it and it
     * resumes at the exact waypoint it stalled on.</p>
     */
    @PostMapping("/missions/{missionId}/reconnect")
    public MissionSnapshot reconnect(@PathVariable String missionId) {
        missions.reconnect(missionId);
        return missions.snapshot(missionId);
    }

    // ------------------------------------------------------------------ fleet

    /** Fan out to many drones at once — one parent workflow, N children, Promise.allOf. */
    @PostMapping("/fleet/dispatch")
    public ResponseEntity<Map<String, Object>> dispatchFleet(
            @Valid @RequestBody DispatchFleetRequest request) {
        List<MissionCommand> commands = request.toCommands(
                registry.homeBaseLabel(), registry.homeLat(), registry.homeLng());
        for (MissionCommand command : commands) {
            registry.findById(command.droneId());
        }
        String fleetId = missions.dispatchFleet(commands);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fleetId", fleetId);
        body.put("missionIds", commands.stream().map(MissionCommand::missionId).toList());
        body.put("detail", "FleetMissionWorkflow started — Promise.allOf will wait for "
                + commands.size() + " child workflows");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * The audit trail, read straight from Supabase.
     *
     * <p>This is the one place Supabase and Temporal meet. Temporal knows a mission is running;
     * Supabase knows that it went OFFLINE_SUSPENDED at 14:02 and came back at 16:03.</p>
     */
    @GetMapping("/missions/history")
    public List<SupabaseMissionRow> history() {
        List<SupabaseMissionRow> rows = supabase.select(
                "missions", "select=*&order=started_at.desc&limit=100", SupabaseMissionRow.class);
        return rows == null ? List.of() : rows;
    }

    // ------------------------------------------------------------------ diagnostics

    @GetMapping("/fleet/state")
    public Map<String, Object> fleetState() {
        List<MissionSnapshot> running = missions.list();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("homeBase", Map.of(
                "label", registry.homeBaseLabel(),
                "lat", registry.homeLat(),
                "lng", registry.homeLng()));
        body.put("missionCount", running.size());
        body.put("missions", running);
        body.put("suspendedDrones", telemetry.suspendedDroneIds());
        body.put("stormActive", telemetry.isStormActive());
        return body;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("temporal", workerStatus.describe());
        body.put("supabase", supabase.isEnabled() ? "connected" : "not-configured (using demo fleet)");
        body.put("droneCount", telemetry.size());
        body.put("runningMissions", missions.list().size());
        body.put("stormActive", telemetry.isStormActive());
        return body;
    }
}
