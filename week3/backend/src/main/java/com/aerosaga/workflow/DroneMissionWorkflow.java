package com.aerosaga.workflow;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionSnapshot;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One drone delivery mission, as a durable state machine.
 *
 * <p>Three entry points, and that is the entire public surface:</p>
 * <ul>
 *   <li>{@link #runMission} — start it (a workflow method)</li>
 *   <li>{@link #snapshot}   — read live state (a query: runs against the in-memory workflow
 *       instance held by a worker, so there is no database round trip)</li>
 *   <li>{@link #reconnect} / {@link #abort} — push an event in (signals: they travel through
 *       Temporal's durable history, so they survive your process being killed)</li>
 * </ul>
 *
 * <p><b>Determinism rules.</b> The implementation is replayed potentially thousands of times to
 * rebuild state after a restart. So: no {@code System.currentTimeMillis()}, no {@code new
 * Random()}, no I/O, no {@code Thread.sleep()}, no unbounded loops, no iteration over
 * non-deterministic collections. Time and randomness come from {@code Workflow.currentTimeMillis()}
 * and {@code Workflow.random()}; side effects come from {@link com.aerosaga.activity.DroneActivities}.
 * Violating this is the number one cause of "non-deterministic workflow" errors in production.</p>
 *
 * <p>{@code @WorkflowInterface} is not optional — Temporal refuses to register an implementation
 * whose interface lacks it, and fails fast at boot rather than at runtime.</p>
 */
@WorkflowInterface
public interface DroneMissionWorkflow {

    @WorkflowMethod
    MissionSnapshot runMission(MissionCommand command);

    @QueryMethod
    MissionSnapshot snapshot();

    /**
     * The drone's radio is back. Walks the workflow out of its durable suspension and back into
     * the navigation loop at the exact waypoint it stalled on.
     */
    @SignalMethod
    void reconnect();

    @SignalMethod
    void abort(String reason);
}
