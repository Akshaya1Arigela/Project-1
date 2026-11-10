package com.aerosaga.workflow;

import com.aerosaga.domain.PatrolCommand;
import com.aerosaga.domain.PatrolStatus;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * A recurring patrol that outlives any single mission — and any single JVM.
 *
 * <p>Rather than an external cron that fires blind, the patrol is one long-running workflow. It
 * starts a child delivery, waits out the interval on a <b>durable timer</b>, then starts the next
 * lap. Restart the backend halfway through the interval and the countdown continues where it was,
 * because the timer lives in Temporal's history, not in a thread.</p>
 */
@WorkflowInterface
public interface PatrolWorkflow {

    @WorkflowMethod
    PatrolStatus runPatrol(PatrolCommand command);

    @QueryMethod
    PatrolStatus status();

    /**
     * Ask the patrol to stop. The lap that is already airborne is allowed to finish — cutting a
     * drone off mid-flight to satisfy an operator click would be a worse outcome than waiting
     * forty seconds.
     */
    @SignalMethod
    void stopPatrol(String reason);
}
