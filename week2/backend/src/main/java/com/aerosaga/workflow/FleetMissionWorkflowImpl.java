package com.aerosaga.workflow;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.MissionSnapshot;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fifty drones, one workflow, {@code Promise.allOf}.
 *
 * <h2>Why this is not a thread pool</h2>
 * <p>A naive implementation would spawn 50 threads. Then your process restarts and the missions
 * are gone. Here, {@code dispatchFleet} starts 50 <em>child workflows</em>, each of which is a
 * separate durable execution in Temporal with its own history, its own state, and its own
 * independently resumable position in its own saga.</p>
 *
 * <h2>The pattern</h2>
 * <pre>
 *   Promise.allOf(promises).get();   // durable barrier
 * </pre>
 * <p>{@code Promise.allOf} is not a blocking join of threads. It is a Temporal construct: the
 * workflow task is completed and the worker thread is released, then Temporal re-schedules the
 * workflow the moment the last child reports completion. Fifty long-running missions cost you
 * one parked workflow object, not fifty threads.</p>
 *
 * <p>Note that this class contains no {@code sleep}, no {@code wait}, no executor. Every bit of
 * concurrency is expressed as Temporal primitives, which is exactly what keeps it deterministic.</p>
 */
public class FleetMissionWorkflowImpl implements FleetMissionWorkflow {

    private final Map<String, Promise<MissionSnapshot>> promises = new LinkedHashMap<>();
    private final Map<String, DroneMissionWorkflow> children = new LinkedHashMap<>();
    private final Map<String, MissionCommand> commands = new LinkedHashMap<>();

    @Override
    public List<MissionSnapshot> dispatchFleet(List<MissionCommand> missions) {
        List<Promise<MissionSnapshot>> pending = new ArrayList<>();

        for (MissionCommand command : missions) {
            DroneMissionWorkflow child = Workflow.newChildWorkflowStub(
                    DroneMissionWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            // One child workflow per mission, idempotently addressed.
                            .setWorkflowId("mission-" + command.missionId())
                            .setTaskQueue(Workflow.getInfo().getTaskQueue())
                            // IMPORTANT. Temporal's default ParentClosePolicy is TERMINATE: if
                            // this parent ever closes while a child is still flying, every child
                            // is killed with it ("reason: by parent close policy"). For a fleet
                            // demo that is exactly wrong — one finished or failed mission would
                            // silently vanish the rest. ABANDON lets each child live out its own
                            // life independently of the parent.
                            .setParentClosePolicy(
                                    io.temporal.api.enums.v1.ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                            .setRetryOptions(io.temporal.common.RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .build())
                            // Whole-child timeout. (There is no setExecutionStartToCloseTimeout
                            // on ChildWorkflowOptions — WorkflowRunTimeout is the right knob.)
                            .setWorkflowRunTimeout(Duration.ofHours(6))
                            .build());

            children.put(command.missionId(), child);
            commands.put(command.missionId(), command);
            // Async.function returns immediately with a Promise; the child starts running now.
            Promise<MissionSnapshot> promise = Async.start(child, command);
            promises.put(command.missionId(), promise);
            pending.add(promise);
        }

        // THE durable barrier. Everything above is now in flight in parallel.
        // Workflow.await semantics: this workflow is parked in Temporal's history until every
        // promise settles. Kill the JVM here and it resumes on the same line after restart.
        try {
            Promise.allOf(pending).get();
        } catch (RuntimeException e) {
            // One child mission failed. The FLEET must not fail with it — we still wait for the
            // rest below and report each mission's own outcome, which is what a fleet manager
            // actually wants to see (4 deliveries succeeded, 1 compensated).
            log().warn("Fleet {} saw a child failure while waiting for all missions: {}",
                    Workflow.getInfo().getWorkflowId(), e.getMessage());
        }

        List<MissionSnapshot> results = new ArrayList<>();
        for (Map.Entry<String, Promise<MissionSnapshot>> entry : promises.entrySet()) {
            MissionCommand command = commands.get(entry.getKey());
            try {
                MissionSnapshot snapshot = entry.getValue().get();
                if (snapshot != null) {
                    results.add(snapshot);
                }
            } catch (RuntimeException e) {
                // Promise.allOf can settle early on a failure; a per-promise get() waits for the
                // rest and turns a dead child into a reported outcome instead of an exception.
                results.add(new MissionSnapshot(
                        entry.getKey(),
                        command == null ? "" : command.droneId(),
                        MissionPhase.FAILED,
                        0, 0, 0, 0,
                        "Child workflow failed: " + e.getMessage(),
                        0,
                        Workflow.currentTimeMillis()));
            }
        }
        return results;
    }

    @Override
    public Map<String, MissionSnapshot> fleetSnapshot() {
        Map<String, MissionSnapshot> result = new LinkedHashMap<>();
        children.forEach((missionId, child) -> {
            try {
                result.put(missionId, child.snapshot());
            } catch (RuntimeException e) {
                // The child has not been created yet on this replay pass.
                Workflow.getLogger(getClass()).debug(
                        "Child {} not queryable yet: {}", missionId, e.getMessage());
            }
        });
        return result;
    }

    /**
     * A deterministic logger — {@code Workflow.getLogger} stamps messages with the workflow id
     * and run attempt, so you can tell which replay pass produced a line.
     */
    private static org.slf4j.Logger log() {
        return Workflow.getLogger(FleetMissionWorkflowImpl.class);
    }

    /**
     * Small local helper so the fan-out reads cleanly. {@code Async.function} is the SDK's way of
     * starting a workflow method without blocking the caller.
     */
    private static final class Async {
        private Async() {
        }

        static Promise<MissionSnapshot> start(DroneMissionWorkflow workflow, MissionCommand command) {
            return io.temporal.workflow.Async.function(workflow::runMission, command);
        }
    }
}
