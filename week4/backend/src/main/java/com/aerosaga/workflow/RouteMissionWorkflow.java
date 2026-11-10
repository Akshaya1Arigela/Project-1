package com.aerosaga.workflow;

import com.aerosaga.domain.RouteCommand;
import com.aerosaga.domain.RouteProgress;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Multi-stop delivery: take off once, visit an optimised order of stops, come home once.
 *
 * <p>Same three entry points as Week 2's mission, plus the offline suspension it inherits: a route
 * that loses its link mid-leg parks durably and continues from the stop it was heading to.</p>
 */
@WorkflowInterface
public interface RouteMissionWorkflow {

    @WorkflowMethod
    RouteProgress deliverRoute(RouteCommand command);

    @QueryMethod
    RouteProgress progress();

    /** Operator abort — compensates: fly home, leave the remaining parcels undelivered. */
    @SignalMethod
    void abort(String reason);

    /** The radio is back; leaves the durable suspension and resumes the current leg. */
    @SignalMethod
    void reconnect();
}
