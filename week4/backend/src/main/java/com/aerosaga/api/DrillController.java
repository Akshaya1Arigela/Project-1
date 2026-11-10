package com.aerosaga.api;

import com.aerosaga.config.WorkerStatus;
import com.aerosaga.domain.MissionCommand;
import com.aerosaga.drone.DroneRegistry;
import com.aerosaga.domain.Drone;
import com.aerosaga.mission.MissionService;
import com.aerosaga.supabase.SupabaseClient;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Review-day drills, exposed as endpoints so they can be run from the dashboard instead of a
 * terminal.
 *
 * <p>Two things live here:</p>
 * <ul>
 *   <li><b>Load test</b> — dispatches N real missions at once, so the 3D globe is under the load
 *       the review plan asks for. These are not fake markers: each one is a Temporal workflow with
 *       its own event history.</li>
 *   <li><b>Readiness</b> — one call that answers "is this thing actually wired up?" (cluster,
 *       database, fleet). Handy at the start of a demo.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/drill")
public class DrillController {

    private static final Logger log = LoggerFactory.getLogger(DrillController.class);

    private final MissionService missions;
    private final DroneRegistry registry;
    private final WorkerStatus workerStatus;
    private final SupabaseClient supabase;

    public DrillController(
            MissionService missions,
            DroneRegistry registry,
            WorkerStatus workerStatus,
            SupabaseClient supabase) {
        this.missions = missions;
        this.registry = registry;
        this.workerStatus = workerStatus;
        this.supabase = supabase;
    }

    /**
     * Dispatch {@code count} missions across the fleet in one burst.
     *
     * <p>Destinations are laid out on a deterministic sunflower spiral around the hub, so no two
     * drones fly to the same point and the globe never looks like a single dot. Deterministic
     * because two runs of a demo should look like two runs of the same demo.</p>
     */
    @PostMapping("/load-test")
    public ResponseEntity<Map<String, Object>> loadTest(
            @Valid @RequestBody(required = false) StartLoadTestRequest request) {

        // Without a cluster every start call would spend its RPC timeout failing, one drone after
        // another — a 50-drone drill would appear to hang for minutes. Refuse once, immediately.
        if (!workerStatus.isPolling()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "message", "Temporal cluster unreachable — run `docker compose up -d` and retry",
                    "temporalDetail", workerStatus.describe()));
        }

        int count = request == null || request.count() == null ? 10 : request.count();
        double payload = request == null || request.payloadKg() == null || request.payloadKg() <= 0
                ? 1.2 : request.payloadKg();
        double spreadKm = request == null || request.spreadKm() == null || request.spreadKm() <= 0
                ? 6.0 : Math.min(request.spreadKm(), 40.0);

        List<Drone> fleet = registry.findAll();
        long startedAt = System.currentTimeMillis();
        List<String> missionIds = new ArrayList<>();
        int failed = 0;

        for (int i = 0; i < count && i < fleet.size(); i++) {
            Drone drone = fleet.get(i);
            double angle = Math.toRadians((i * 137.508) % 360);
            double radiusDeg = (spreadKm / 111.0) * (0.35 + 0.65 * ((i % 7) / 6.0));
            double destLat = registry.homeLat() + radiusDeg * Math.cos(angle);
            double destLng = registry.homeLng()
                    + radiusDeg * Math.sin(angle) / Math.cos(Math.toRadians(registry.homeLat()));

            MissionCommand command = MissionCommand.of(
                    "lt-" + UUID.randomUUID().toString().substring(0, 6),
                    drone.id(),
                    registry.homeBaseLabel(),
                    registry.homeLat(),
                    registry.homeLng(),
                    "Load-test waypoint " + (i + 1),
                    destLat,
                    destLng,
                    payload);

            try {
                missionIds.add(missions.start(command));
            } catch (RuntimeException e) {
                failed++;
                log.warn("Load test could not dispatch {}: {}", drone.id(), e.getMessage());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requested", count);
        body.put("dispatched", missionIds.size());
        body.put("failed", failed);
        body.put("elapsedMs", System.currentTimeMillis() - startedAt);
        body.put("missionIds", missionIds);
        body.put("detail", "Each id is a real DroneMissionWorkflow — the globe is showing "
                + missionIds.size() + " live Temporal executions, not fake markers");
        return ResponseEntity.ok(body);
    }

    /** One call that answers "is the demo actually wired up?". */
    @GetMapping("/readiness")
    public Map<String, Object> readiness() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("temporalPolling", workerStatus.isPolling());
        body.put("temporalDetail", workerStatus.describe());
        body.put("supabaseConfigured", supabase.isEnabled());
        body.put("fleetSize", registry.findAll().size());
        body.put("homeBase", registry.homeBaseLabel());
        body.put("checks", List.of(
                new Check("temporal", workerStatus.isPolling(),
                        "Worker polling the task queue"),
                new Check("supabase", supabase.isEnabled(),
                        "Audit trail and incident table will be written"),
                new Check("fleet", registry.findAll().size() > 0,
                        "At least one airframe is registered")));
        return body;
    }

    /** One readiness line. */
    public record Check(String name, boolean ok, String detail) {
    }
}
