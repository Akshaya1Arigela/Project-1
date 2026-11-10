package com.aerosaga.workflow;

import com.aerosaga.domain.SupervisorCommand;
import com.aerosaga.domain.SupervisorReport;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * A long-running supervisor: the one workflow that watches all the others.
 *
 * <p>Weeks 1–3 produced workflows that fly. This one does not fly anything — it observes the
 * fleet, decides when something is wrong, and intervenes by signalling the workflow that is
 * already in trouble. That makes it the first piece of the system with <em>judgement</em> in it,
 * and Temporal is exactly the right place for judgement that has to survive a restart.</p>
 *
 * <p>Three things about the shape:</p>
 * <ul>
 *   <li>It <b>never returns</b> in the normal case — it loops until a stop signal arrives. A
 *       workflow that runs for weeks is normal in Temporal; the event history is what needs
 *       managing, which is what {@code continueAsNew} is for.</li>
 *   <li>{@link #report()} is a query, so the dashboard can read the supervisor's brain at any
 *       moment without disturbing its loop.</li>
 *   <li>{@link #setAutoIntervene(boolean)} is a signal, so an operator can turn the supervisor
 *       from "advise" into "act" (or back) while it is running.</li>
 * </ul>
 */
@WorkflowInterface
public interface FleetSupervisorWorkflow {

    /**
     * Watch the fleet until stopped.
     *
     * @param command scan policy plus the counters carried over from earlier runs
     * @return the final report, produced only after a stop signal
     */
    @WorkflowMethod
    SupervisorReport supervise(SupervisorCommand command);

    /** Live state: what the supervisor has seen and done so far. */
    @QueryMethod
    SupervisorReport report();

    /** Flip automatic intervention on or off without restarting the supervisor. */
    @SignalMethod
    void setAutoIntervene(boolean enabled);

    /** Ask the supervisor to finish its loop and return its final report. */
    @SignalMethod
    void stop(String reason);
}
