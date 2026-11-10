package com.aerosaga.supervisor;

import com.aerosaga.domain.SupervisorCommand;
import com.aerosaga.domain.SupervisorReport;
import com.aerosaga.workflow.FleetSupervisorWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts and talks to supervisors.
 *
 * <p>Structurally identical to the mission, route and patrol services: a map of stubs for the
 * supervisors this process started, plus a visibility query used after a restart to re-attach to
 * the ones it did not. Four services, one pattern — that repetition is the point: it is what
 * "Temporal survives restarts" actually looks like in application code.</p>
 */
@Service
public class FleetSupervisorService {

    private static final Logger log = LoggerFactory.getLogger(FleetSupervisorService.class);
    private static final String PREFIX = "supervisor-";
    private static final String WORKFLOW_TYPE = "FleetSupervisorWorkflow";

    private final WorkflowClient workflowClient;
    private final String taskQueue;
    private final String namespace;
    private final Map<String, FleetSupervisorWorkflow> supervisors = new ConcurrentHashMap<>();

    public FleetSupervisorService(
            WorkflowClient workflowClient,
            @Value("${aerosaga.temporal.task-queue}") String taskQueue,
            @Value("${aerosaga.temporal.namespace}") String namespace) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
        this.namespace = namespace;
    }

    public SupervisorReport start(SupervisorCommand command) {
        FleetSupervisorWorkflow workflow = workflowClient.newWorkflowStub(
                FleetSupervisorWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(taskQueue)
                        .setWorkflowId(PREFIX + command.supervisorId())
                        .build());

        try {
            WorkflowClient.start(workflow::supervise, command);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.warn("Supervisor {} was already running", command.supervisorId());
        }
        supervisors.put(command.supervisorId(), workflow);
        log.info("Started FleetSupervisorWorkflow {} — scan {}s, grace {}s, auto-intervene {}",
                command.supervisorId(), command.scanIntervalSeconds(),
                command.offlineGraceSeconds(), command.autoIntervene());

        // The first scan happens on the worker, not here, so a query immediately after start is a
        // race. Fall back to the starting report rather than failing the REST call.
        try {
            return workflow.report();
        } catch (RuntimeException e) {
            log.debug("Supervisor {} not queryable yet: {}", command.supervisorId(), e.getMessage());
            return SupervisorReport.starting(command, System.currentTimeMillis(), 1);
        }
    }

    public SupervisorReport report(String supervisorId) {
        try {
            return resolve(supervisorId).report();
        } catch (RuntimeException e) {
            throw new com.aerosaga.mission.MissionNotFoundException(supervisorId, e);
        }
    }

    public List<SupervisorReport> list() {
        List<SupervisorReport> result = new ArrayList<>();
        supervisors.forEach((id, workflow) -> {
            try {
                result.add(workflow.report());
            } catch (RuntimeException e) {
                log.debug("Supervisor {} not queryable: {}", id, e.getMessage());
            }
        });
        return result;
    }

    public SupervisorReport stop(String supervisorId, String reason) {
        FleetSupervisorWorkflow workflow = resolve(supervisorId);
        workflow.stop(reason == null ? "operator request" : reason);
        return report(supervisorId);
    }

    public SupervisorReport setAutoIntervene(String supervisorId, boolean enabled) {
        resolve(supervisorId).setAutoIntervene(enabled);
        return report(supervisorId);
    }

    /**
     * Re-attach after a restart.
     *
     * <p>The supervisor is itself a workflow, so "who is supervising the fleet?" has the same
     * answer as "which missions are flying?": ask Temporal, not a database.</p>
     */
    public void restore() {
        List<String> ids = new ArrayList<>();
        try {
            io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest request =
                    io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest.newBuilder()
                            .setNamespace(namespace)
                            .setPageSize(50)
                            .setQuery("WorkflowType = '" + WORKFLOW_TYPE
                                    + "' AND ExecutionStatus = 'Running'")
                            .build();
            var response = workflowClient.getWorkflowServiceStubs().blockingStub()
                    .listWorkflowExecutions(request);
            for (io.temporal.api.workflow.v1.WorkflowExecutionInfo info
                    : response.getExecutionsList()) {
                String workflowId = info.getExecution().getWorkflowId();
                if (workflowId.startsWith(PREFIX)) {
                    ids.add(workflowId.substring(PREFIX.length()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not list running supervisors from Temporal: {}", e.getMessage());
        }

        for (String supervisorId : ids) {
            supervisors.put(supervisorId, newTargetedStub(supervisorId));
        }
        if (!ids.isEmpty()) {
            log.info("Re-attached to {} running supervisor(s) after restart", ids.size());
        }
    }

    private FleetSupervisorWorkflow resolve(String supervisorId) {
        return supervisors.computeIfAbsent(supervisorId, this::newTargetedStub);
    }

    private FleetSupervisorWorkflow newTargetedStub(String supervisorId) {
        return workflowClient.newWorkflowStub(FleetSupervisorWorkflow.class, PREFIX + supervisorId);
    }
}
