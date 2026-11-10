package com.aerosaga.workflow;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.domain.PatrolCommand;
import com.aerosaga.domain.PatrolStatus;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * The Week 3 patrol loop, written only with deterministic Temporal primitives.
 *
 * <h2>Three things to notice</h2>
 * <ol>
 *   <li><b>The sleep is a timer, not a thread.</b> {@code Workflow.await(Duration, condition)}
 *       parks the workflow in Temporal's history. A patrol waiting two hours between laps uses no
 *       memory beyond its own history.</li>
 *   <li><b>Each lap is a child workflow.</b> Laps are independent executions with their own
 *       histories, so lap 3 can be inspected, retried or aborted in the Web UI without touching
 *       laps 1 and 2.</li>
 *   <li><b>Mission ids are derived, never random.</b> {@code patrolId-c3} is the same on every
 *       replay, which is what stops a restart from launching a duplicate lap.</li>
 * </ol>
 *
 * <p>If a lap fails (storm, low battery, operator abort) the patrol records the outcome and keeps
 * patrolling. A recurring schedule that stops the first time one delivery goes wrong is not a
 * schedule, it is a demo.</p>
 */
public class PatrolWorkflowImpl implements PatrolWorkflow {

    private PatrolCommand command;
    private PatrolStatus status;
    private boolean stopRequested;
    private String stopReason;

    @Override
    public PatrolStatus runPatrol(PatrolCommand cmd) {
        this.command = cmd;
        this.status = PatrolStatus.initial(cmd);

        for (int cycle = 1; cycle <= cmd.cycles(); cycle++) {
            if (stopRequested) {
                return finish("STOPPED", "Stopped by operator before lap " + cycle
                        + (stopReason == null ? "" : ": " + stopReason));
            }

            MissionCommand mission = cmd.missionForCycle(cycle);
            status = status.flyingLap(cycle, mission.missionId(), Workflow.currentTimeMillis());

            try {
                // A synchronous child call. Temporal still runs it as a separate workflow with
                // its own history; the parent simply cannot take the next lap until this one
                // returns. No thread is blocked — this workflow is parked, not spinning.
                MissionSnapshot snapshot = deliver(mission);
                status = status.lapFinished(cycle, mission.missionId(), snapshot.phase(),
                        Workflow.currentTimeMillis(),
                        "Lap " + cycle + " finished as " + snapshot.phase() + " — "
                                + snapshot.detail());
            } catch (RuntimeException e) {
                // A failed lap does not end the patrol.
                status = status.lapFinished(cycle, mission.missionId(), MissionPhase.FAILED,
                        Workflow.currentTimeMillis(),
                        "Lap " + cycle + " failed: " + rootMessage(e));
            }

            if (cycle < cmd.cycles()) {
                long waitSeconds = cmd.intervalSeconds();
                status = status.waiting(waitSeconds, cycle + 1, Workflow.currentTimeMillis());
                // DURABLE TIMER + condition. Either the interval elapses or a stop signal arrives;
                // both are recorded events, so a restart resumes the countdown.
                Workflow.await(Duration.ofSeconds(waitSeconds), () -> stopRequested);
            }
        }

        return finish("COMPLETED", "Flew " + cmd.cycles() + " lap(s) of the patrol circuit");
    }

    @Override
    public PatrolStatus status() {
        return status;
    }

    @Override
    public void stopPatrol(String reason) {
        this.stopRequested = true;
        this.stopReason = reason;
    }

    // ------------------------------------------------------------------ helpers

    private MissionSnapshot deliver(MissionCommand mission) {
        DroneMissionWorkflow child = Workflow.newChildWorkflowStub(
                DroneMissionWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("mission-" + mission.missionId())
                        .setTaskQueue(Workflow.getInfo().getTaskQueue())
                        // Do not let the patrol ending kill a lap that is still in the air.
                        .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                        .setWorkflowRunTimeout(Duration.ofHours(6))
                        .build());
        return child.runMission(mission);
    }

    private PatrolStatus finish(String label, String detail) {
        status = status.finished(label, detail, Workflow.currentTimeMillis());
        return status;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor.getCause() != null && cursor.getCause() != cursor && depth++ < 20) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }
}
