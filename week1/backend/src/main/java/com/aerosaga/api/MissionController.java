package com.aerosaga.api;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.drone.DroneRegistry;
import com.aerosaga.mission.MissionService;
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
 * <p>Note what is <em>not</em> here: there is no "poll the status" loop, no job table, no
 * {@code WHERE status = 'IN_PROGRESS'} query. Starting a mission writes to Temporal's history,
 * and reading its state is a query against the live workflow object. That is the entire point of
 * the project.</p>
 */
@RestController
@RequestMapping("/api/missions")
public class MissionController {

    private static final Logger log = LoggerFactory.getLogger(MissionController.class);

    private final MissionService missions;
    private final DroneRegistry registry;

    public MissionController(MissionService missions, DroneRegistry registry) {
        this.missions = missions;
        this.registry = registry;
    }

    /** Start a mission. Returns immediately — the workflow runs on Temporal, not in this thread. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartMissionRequest request) {
        registry.findById(request.droneId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown drone: " + request.droneId()));

        String missionId = UUID.randomUUID().toString().substring(0, 8);
        MissionCommand command = request.toCommand(
                missionId,
                registry.homeBaseLabel(),
                registry.homeLat(),
                registry.homeLng());

        missions.start(command);
        log.info("Mission {} accepted for drone {}", missionId, request.droneId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("missionId", missionId);
        body.put("droneId", command.droneId());
        body.put("workflowId", "mission-" + missionId);
        body.put("status", "started");
        body.put("detail", "Workflow history written — the mission now lives inside Temporal");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * Live state of one mission, straight from the workflow's {@code @QueryMethod}.
     * Try hitting this repeatedly while a mission runs and watch {@code phase} and {@code step}
     * advance.
     */
    @GetMapping("/{missionId}")
    public MissionSnapshot getOne(@PathVariable String missionId) {
        return missions.snapshot(missionId);
    }

    @GetMapping
    public List<MissionSnapshot> list() {
        return missions.list();
    }

    /** Operator abort, delivered as a Temporal signal so it survives a process restart. */
    @PostMapping("/{missionId}/abort")
    public MissionSnapshot abort(@PathVariable String missionId) {
        missions.abort(missionId, "operator request from REST API");
        return missions.snapshot(missionId);
    }
}
