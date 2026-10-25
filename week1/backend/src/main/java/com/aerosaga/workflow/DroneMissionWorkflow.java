package com.aerosaga.workflow;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionSnapshot;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * The contract for one drone delivery mission.
 *
 * <p>Temporal turns this interface into a durable state machine. Three things are exposed to the
 * outside world and three things only:</p>
 * <ul>
 *   <li>{@link #runMission} — start it</li>
 *   <li>{@link #snapshot}   — ask it where it is (a query: hits live workflow state, no database)</li>
 *   <li>{@link #abort}      — tell it to stop (a signal: delivered through Temporal's history,
 *       so it survives your process being killed)</li>
 * </ul>
 *
 * <p><b>Determinism rules.</b> The implementation runs inside Temporal's replay engine and may be
 * re-executed thousands of times to rebuild state. So: no {@code System.currentTimeMillis()}, no
 * {@code new Random()}, no I/O, no {@code Thread.sleep()}. Side effects belong in
 * {@link com.aerosaga.activity.DroneActivities}.</p>
 */
@WorkflowInterface
public interface DroneMissionWorkflow {

    /** Fire-and-forget entry point; the controller never blocks on this. */
    @WorkflowMethod
    MissionSnapshot runMission(MissionCommand command);

    /**
     * Read-only projection of the workflow's private fields. Temporal executes this against the
     * in-memory workflow instance — there is no database round trip, so it is safe to poll
     * several times a second from the dashboard.
     */
    @QueryMethod
    MissionSnapshot snapshot();

    /** Operator-initiated stop, delivered through the durable event history. */
    @SignalMethod
    void abort(String reason);
}
