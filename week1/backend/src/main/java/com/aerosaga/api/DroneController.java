package com.aerosaga.api;

import com.aerosaga.domain.Drone;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.drone.DroneRegistry;
import com.aerosaga.config.WorkerStatus;
import com.aerosaga.mission.MissionService;
import com.aerosaga.supabase.SupabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the dashboard needs to render itself, in two calls.
 *
 * <p>{@code GET /api/fleet} drives the 3D markers; {@code GET /api/fleet/state} overlays the live
 * Temporal workflow phase on top of them. Splitting them means the map can render before any
 * mission exists.</p>
 */
@RestController
@RequestMapping("/api")
public class DroneController {

    private final DroneRegistry registry;
    private final MissionService missions;
    private final SupabaseClient supabase;
    private final WorkerStatus workerStatus;

    public DroneController(DroneRegistry registry, MissionService missions, SupabaseClient supabase,
                           WorkerStatus workerStatus) {
        this.registry = registry;
        this.missions = missions;
        this.supabase = supabase;
        this.workerStatus = workerStatus;
    }

    /** The drone registry. Sourced from Supabase, with an in-memory demo fallback. */
    @GetMapping("/drones")
    public List<Drone> drones() {
        return registry.findAll();
    }

    /** Live Temporal state for every mission this worker is holding. */
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
        return body;
    }

    /** Cheap readiness probe that also tells you whether Supabase and Temporal are wired up. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("temporal", workerStatus.describe());
        body.put("supabase", supabase.isEnabled() ? "connected" : "not-configured (using demo fleet)");
        body.put("droneCount", registry.findAll().size());
        body.put("runningMissions", missions.list().size());
        return body;
    }

    @GetMapping("/drones/{droneId}")
    public Drone drone(@PathVariable String droneId) {
        return registry.findById(droneId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown drone: " + droneId));
    }
}
