package com.aerosaga.api;

import com.aerosaga.domain.RouteCommand;
import com.aerosaga.domain.RouteProgress;
import com.aerosaga.drone.DroneRegistry;
import com.aerosaga.mission.RouteMissionService;
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
 * Multi-stop route endpoints.
 *
 * <p>Separate from {@code /api/missions} on purpose: a route is a different workflow type with a
 * different progress shape, and pretending they are the same resource would make both APIs worse.</p>
 */
@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private static final Logger log = LoggerFactory.getLogger(RouteController.class);

    private final RouteMissionService routes;
    private final DroneRegistry registry;

    public RouteController(RouteMissionService routes, DroneRegistry registry) {
        this.routes = routes;
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody StartRouteRequest request) {
        registry.findById(request.droneId());

        String missionId = "r" + UUID.randomUUID().toString().substring(0, 7);
        RouteCommand command = request.toCommand(
                missionId, registry.homeBaseLabel(), registry.homeLat(), registry.homeLng());

        routes.start(command);
        log.info("Route {} accepted for drone {} ({} stops)",
                missionId, request.droneId(), command.stops().size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("missionId", missionId);
        body.put("droneId", command.droneId());
        body.put("workflowId", "route-" + missionId);
        body.put("stopsRequested", command.stops().size());
        body.put("status", "started");
        body.put("detail", "RouteMissionWorkflow started — the stop order is decided by the "
                + "planRoute activity and written into Temporal's history");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping
    public List<RouteProgress> list() {
        return routes.list();
    }

    @GetMapping("/{missionId}")
    public RouteProgress progress(@PathVariable String missionId) {
        return routes.progress(missionId);
    }

    @PostMapping("/{missionId}/abort")
    public RouteProgress abort(@PathVariable String missionId) {
        routes.abort(missionId, "operator request from REST API");
        return routes.progress(missionId);
    }

    @PostMapping("/{missionId}/reconnect")
    public RouteProgress reconnect(@PathVariable String missionId) {
        routes.reconnect(missionId);
        return routes.progress(missionId);
    }
}
