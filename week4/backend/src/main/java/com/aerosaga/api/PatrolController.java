package com.aerosaga.api;

import com.aerosaga.domain.PatrolCommand;
import com.aerosaga.domain.PatrolStatus;
import com.aerosaga.drone.DroneRegistry;
import com.aerosaga.mission.PatrolService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Patrol endpoints.
 *
 * <p>{@code DELETE} does not destroy anything — it sends a {@code stopPatrol} signal, exactly like
 * the abort signal on a mission. Everything about the patrol stays in Temporal's history, which is
 * why "when did patrol 3 stop and why" is answerable weeks later.</p>
 */
@RestController
@RequestMapping("/api/patrols")
public class PatrolController {

    private final PatrolService patrols;
    private final DroneRegistry registry;

    public PatrolController(PatrolService patrols, DroneRegistry registry) {
        this.patrols = patrols;
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<PatrolStatus> start(@Valid @RequestBody StartPatrolRequest request) {
        registry.findById(request.droneId());

        String patrolId = "p" + UUID.randomUUID().toString().substring(0, 7);
        PatrolCommand command = request.toCommand(
                patrolId, registry.homeBaseLabel(), registry.homeLat(), registry.homeLng());

        PatrolStatus status = patrols.start(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    @GetMapping
    public List<PatrolStatus> list() {
        return patrols.list();
    }

    @GetMapping("/{patrolId}")
    public PatrolStatus status(@PathVariable String patrolId) {
        return patrols.status(patrolId);
    }

    /** Ask a patrol to stop. The lap already in the air is allowed to land first. */
    @DeleteMapping("/{patrolId}")
    public ResponseEntity<PatrolStatus> stop(@PathVariable String patrolId) {
        patrols.stop(patrolId, "operator request from REST API");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(patrols.status(patrolId));
    }
}
