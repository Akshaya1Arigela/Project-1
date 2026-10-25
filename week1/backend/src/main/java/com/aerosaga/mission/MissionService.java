package com.aerosaga.mission;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.workflow.DroneMissionWorkflow;
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
 * The only place in the app that talks to Temporal.
 *
 * <p>Two facts about Temporal worth internalising here:</p>
 * <ol>
 *   <li><b>Starting a workflow is not like running a thread.</b> {@code WorkflowClient.start}
 *       writes the first events into the durable history and returns immediately. If your JVM
 *       dies one millisecond later, the mission still runs.</li>
 *   <li><b>The typed stub is a handle, not a connection.</b> You can recreate it at any time from
 *       the workflow id — this map is a convenience, not a requirement.</li>
 * </ol>
 */
@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    private final WorkflowClient workflowClient;
    private final String taskQueue;

    /** missionId -> typed stub, so the REST layer can query and signal without re-resolving. */
    private final Map<String, DroneMissionWorkflow> running = new ConcurrentHashMap<>();

    public MissionService(
            WorkflowClient workflowClient,
            @Value("${aerosaga.temporal.task-queue}") String taskQueue) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
        log.info("MissionService bound to task queue '{}'", taskQueue);
    }

    /** Fire-and-forget. Returns as soon as the history row is written. */
    public String start(MissionCommand command) {
        DroneMissionWorkflow workflow = workflowClient.newWorkflowStub(
                DroneMissionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(taskQueue)
                        // Workflow id must be unique per namespace; the mission id gives us that
                        // for free and makes the Web UI greppable.
                        .setWorkflowId(workflowId(command.missionId()))
                        .build());

        try {
            WorkflowClient.start(workflow::runMission, command);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.warn("Mission {} was already started", command.missionId());
        }

        running.put(command.missionId(), workflow);
        log.info("Started DroneMissionWorkflow {} for drone {}",
                command.missionId(), command.droneId());
        return command.missionId();
    }

    /**
     * Calls the workflow's {@code @QueryMethod}. This runs against the live in-memory workflow
     * instance held by a worker — there is no database read anywhere in this call path, which is
     * exactly why the dashboard can poll it aggressively.
     */
    public MissionSnapshot snapshot(String missionId) {
        DroneMissionWorkflow workflow = resolve(missionId);
        try {
            return workflow.snapshot();
        } catch (RuntimeException e) {
            throw new MissionNotFoundException(missionId, e);
        }
    }

    public void abort(String missionId, String reason) {
        resolve(missionId).abort(reason);
        log.info("Signalled abort to mission {}: {}", missionId, reason);
    }

    /** Every mission this process knows about, completed ones filtered out. */
    public List<MissionSnapshot> list() {
        List<MissionSnapshot> result = new ArrayList<>();
        running.forEach((missionId, workflow) -> {
            try {
                MissionSnapshot snapshot = workflow.snapshot();
                if (!isTerminal(snapshot)) {
                    result.add(snapshot);
                }
            } catch (RuntimeException e) {
                // The workflow finished and its query was closed, or it lives on another worker.
                log.debug("Skipping mission {} — {}", missionId, e.getMessage());
            }
        });
        return result;
    }

    public boolean isRunning(String missionId) {
        DroneMissionWorkflow workflow = running.get(missionId);
        return workflow != null && !isTerminal(workflow.snapshot());
    }

    /** Terminal phases mean Temporal has nothing left to schedule. */
    public static boolean isTerminal(MissionSnapshot snapshot) {
        return switch (snapshot.phase()) {
            case DELIVERED, COMPENSATED, FAILED, ABORTED -> true;
            default -> false;
        };
    }

    /** Recreate a stub from a workflow id alone — useful after the process has restarted. */
    public DroneMissionWorkflow resolve(String missionId) {
        DroneMissionWorkflow cached = running.get(missionId);
        if (cached != null) {
            return cached;
        }
        DroneMissionWorkflow resolved = workflowClient.newWorkflowStub(
                DroneMissionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(taskQueue)
                        .setWorkflowId(workflowId(missionId))
                        .build());
        running.putIfAbsent(missionId, resolved);
        return resolved;
    }

    private static String workflowId(String missionId) {
        return "mission-" + missionId;
    }
}
