package com.aerosaga.api;

import com.aerosaga.domain.Incident;
import com.aerosaga.domain.SupervisorReport;
import com.aerosaga.supervisor.FleetSupervisorService;
import com.aerosaga.supervisor.IncidentLog;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The fleet supervisor, from the outside.
 *
 * <p>Note what is <em>not</em> here: no "start supervisor thread", no scheduler, no daemon flag.
 * Starting a supervisor starts a workflow; stopping it sends a signal; asking how it is doing is a
 * query. If this process dies, the supervisor keeps scanning — Workers pick it up when they come
 * back.</p>
 */
@RestController
@RequestMapping("/api/supervisor")
public class SupervisorController {

    private static final Logger log = LoggerFactory.getLogger(SupervisorController.class);

    private final FleetSupervisorService supervisors;
    private final IncidentLog incidents;

    public SupervisorController(FleetSupervisorService supervisors, IncidentLog incidents) {
        this.supervisors = supervisors;
        this.incidents = incidents;
    }

    /** Start watching the fleet. Returns the supervisor's first report. */
    @PostMapping
    public ResponseEntity<SupervisorReport> start(
            @Valid @RequestBody(required = false) StartSupervisorRequest request) {
        StartSupervisorRequest policy = request == null
                ? new StartSupervisorRequest(null, null, null, null, null)
                : request;
        String supervisorId = "s" + UUID.randomUUID().toString().substring(0, 7);

        SupervisorReport report = supervisors.start(policy.toCommand(supervisorId));
        log.info("Supervisor {} started", supervisorId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(report);
    }

    @GetMapping
    public List<SupervisorReport> list() {
        return supervisors.list();
    }

    /** The live report for one supervisor. */
    @GetMapping("/{supervisorId}")
    public SupervisorReport report(@PathVariable String supervisorId) {
        return supervisors.report(supervisorId);
    }

    /** Signal: finish the loop and return the final report. */
    @PostMapping("/{supervisorId}/stop")
    public SupervisorReport stop(@PathVariable String supervisorId) {
        return supervisors.stop(supervisorId, "operator request from REST API");
    }

    /** Signal: flip automatic intervention while the supervisor keeps running. */
    @PostMapping("/{supervisorId}/auto-intervene")
    public SupervisorReport autoIntervene(
            @PathVariable String supervisorId,
            @RequestParam(defaultValue = "true") boolean enabled) {
        return supervisors.setAutoIntervene(supervisorId, enabled);
    }

    /**
     * The incident feed, newest first.
     *
     * <p>Served from the in-memory log written by the {@code recordIncident} activity, so it works
     * without Supabase. The durable copy lives in the {@code incidents} table.</p>
     */
    @GetMapping("/incidents")
    public List<Incident> incidents(@RequestParam(defaultValue = "50") int limit) {
        return incidents.recent(Math.max(1, Math.min(limit, 500)));
    }
}
