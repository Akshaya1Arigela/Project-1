package com.aerosaga.mission;

import com.aerosaga.domain.PatrolCommand;
import com.aerosaga.domain.PatrolStatus;
import com.aerosaga.workflow.PatrolWorkflow;
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
 * Owns patrol workflows: start one, ask how it is doing, stop it.
 *
 * <p>The important thing a patrol demonstrates is that a "schedule" does not have to live outside
 * the system. There is no cron, no Quartz table, no {@code @Scheduled} method that fires on one
 * machine and forgets everything if that machine reboots. The interval is a timer inside the
 * workflow's history.</p>
 */
@Service
public class PatrolService {

    private static final Logger log = LoggerFactory.getLogger(PatrolService.class);
    private static final String PREFIX = "patrol-";
    private static final String WORKFLOW_TYPE = "PatrolWorkflow";

    private final WorkflowClient workflowClient;
    private final String taskQueue;
    private final String namespace;
    private final Map<String, PatrolWorkflow> patrols = new ConcurrentHashMap<>();

    public PatrolService(
            WorkflowClient workflowClient,
            @Value("${aerosaga.temporal.task-queue}") String taskQueue,
            @Value("${aerosaga.temporal.namespace}") String namespace) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
        this.namespace = namespace;
    }

    public PatrolStatus start(PatrolCommand command) {
        PatrolWorkflow workflow = workflowClient.newWorkflowStub(
                PatrolWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(taskQueue)
                        .setWorkflowId(PREFIX + command.patrolId())
                        .build());
        try {
            WorkflowClient.start(workflow::runPatrol, command);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.warn("Patrol {} was already started", command.patrolId());
        }
        patrols.put(command.patrolId(), workflow);
        log.info("Started PatrolWorkflow {} for drone {} — {} lap(s) every {}s",
                command.patrolId(), command.droneId(), command.cycles(),
                command.intervalSeconds());
        return status(command.patrolId());
    }

    public PatrolStatus status(String patrolId) {
        try {
            return patrols.computeIfAbsent(patrolId, this::newTargetedStub).status();
        } catch (RuntimeException e) {
            throw new MissionNotFoundException(patrolId, e);
        }
    }

    public void stop(String patrolId, String reason) {
        patrols.computeIfAbsent(patrolId, this::newTargetedStub)
                .stopPatrol(reason == null ? "operator request" : reason);
        log.info("Stop signal sent to patrol {}", patrolId);
    }

    public List<PatrolStatus> list() {
        List<PatrolStatus> result = new ArrayList<>();
        patrols.forEach((patrolId, workflow) -> {
            try {
                PatrolStatus status = workflow.status();
                if (status.active()) {
                    result.add(status);
                }
            } catch (RuntimeException e) {
                log.debug("Patrol {} not queryable: {}", patrolId, e.getMessage());
            }
        });
        return result;
    }

    /** Re-attach to patrols after a restart, exactly like missions and routes. */
    public void restore() {
        List<String> ids = new ArrayList<>();
        try {
            io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest request =
                    io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest.newBuilder()
                            .setNamespace(namespace)
                            .setPageSize(200)
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
            log.warn("Could not list open patrols from Temporal: {}", e.getMessage());
        }

        for (String patrolId : ids) {
            patrols.put(patrolId, newTargetedStub(patrolId));
        }
        if (!ids.isEmpty()) {
            log.info("Re-attached to {} patrol(s) after restart — timers survived the restart",
                    ids.size());
        }
    }

    private PatrolWorkflow newTargetedStub(String patrolId) {
        return workflowClient.newWorkflowStub(PatrolWorkflow.class, PREFIX + patrolId);
    }
}
