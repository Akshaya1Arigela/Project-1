package com.aerosaga.mission;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.RouteCommand;
import com.aerosaga.domain.RouteProgress;
import com.aerosaga.supabase.SupabaseClient;
import com.aerosaga.workflow.RouteMissionWorkflow;
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
 * Starts and reads multi-stop route missions.
 *
 * <p>Structurally identical to {@link MissionService}: a stub map for the missions this process
 * knows about, plus a visibility lookup used after a restart. Route workflows live under their own
 * workflow-id prefix ({@code route-...}) and their own workflow type, so they never collide with
 * single-destination missions in the Web UI's filters.</p>
 */
@Service
public class RouteMissionService {

    private static final Logger log = LoggerFactory.getLogger(RouteMissionService.class);
    private static final String PREFIX = "route-";
    private static final String WORKFLOW_TYPE = "RouteMissionWorkflow";

    private final WorkflowClient workflowClient;
    private final MissionIndex index;
    private final MissionRecorder recorder;
    private final SupabaseClient supabase;
    private final String taskQueue;
    private final String namespace;

    private final Map<String, RouteMissionWorkflow> running = new ConcurrentHashMap<>();

    public RouteMissionService(
            WorkflowClient workflowClient,
            MissionIndex index,
            MissionRecorder recorder,
            SupabaseClient supabase,
            @Value("${aerosaga.temporal.task-queue}") String taskQueue,
            @Value("${aerosaga.temporal.namespace}") String namespace) {
        this.workflowClient = workflowClient;
        this.index = index;
        this.recorder = recorder;
        this.supabase = supabase;
        this.taskQueue = taskQueue;
        this.namespace = namespace;
    }

    public String start(RouteCommand command) {
        RouteMissionWorkflow workflow = workflowClient.newWorkflowStub(
                RouteMissionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(taskQueue)
                        .setWorkflowId(PREFIX + command.missionId())
                        .build());

        try {
            WorkflowClient.start(workflow::deliverRoute, command);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.warn("Route {} was already started", command.missionId());
        }

        running.put(command.missionId(), workflow);
        // The audit row is keyed on the route's mission id; the first stop names the flight so a
        // history list stays readable.
        MissionCommand first = command.missionFor(command.stops().get(0));
        index.put(command.missionId(), first, PREFIX + command.missionId());
        recorder.recordRouteStart(first, taskQueue, command.stops().size());

        log.info("Started RouteMissionWorkflow {} for drone {} with {} stops",
                command.missionId(), command.droneId(), command.stops().size());
        return command.missionId();
    }

    public RouteProgress progress(String missionId) {
        try {
            return resolve(missionId).progress();
        } catch (RuntimeException e) {
            throw new MissionNotFoundException(missionId, e);
        }
    }

    public void abort(String missionId, String reason) {
        resolve(missionId).abort(reason);
    }

    public void reconnect(String missionId) {
        resolve(missionId).reconnect();
    }

    /** Routes this process believes are still flying. */
    public List<RouteProgress> list() {
        List<RouteProgress> result = new ArrayList<>();
        running.forEach((missionId, workflow) -> {
            try {
                RouteProgress progress = workflow.progress();
                if (!isTerminal(progress)) {
                    result.add(progress);
                }
            } catch (RuntimeException e) {
                log.debug("Skipping route {} — {}", missionId, e.getMessage());
            }
        });
        return result;
    }

    /**
     * Re-attach to routes that were already flying when this process died.
     *
     * <p>Called from the worker starter, after the worker is actually polling. Same visibility
     * query as Week 2, one different workflow type — proof that the recovery pattern is about
     * Temporal, not about one particular workflow class.</p>
     */
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
            log.warn("Could not list open routes from Temporal: {}", e.getMessage());
        }

        for (String missionId : ids) {
            running.put(missionId, newTargetedStub(missionId));
        }
        if (!ids.isEmpty()) {
            log.info("Re-attached to {} open route mission(s) after restart", ids.size());
        }
    }

    private RouteMissionWorkflow resolve(String missionId) {
        return running.computeIfAbsent(missionId, this::newTargetedStub);
    }

    private RouteMissionWorkflow newTargetedStub(String missionId) {
        return workflowClient.newWorkflowStub(RouteMissionWorkflow.class, PREFIX + missionId);
    }

    public static boolean isTerminal(RouteProgress progress) {
        return switch (progress.phase()) {
            case DELIVERED, COMPENSATED, FAILED, ABORTED -> true;
            default -> false;
        };
    }
}
