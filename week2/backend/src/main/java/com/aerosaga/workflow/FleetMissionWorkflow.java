package com.aerosaga.workflow;

import com.aerosaga.domain.MissionSnapshot;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;
import java.util.Map;

/**
 * Fan out one mission per drone and wait for the whole fleet.
 *
 * <p>This is the "orchestrate multiple drones" part of the Week 2 plan. The implementation uses
 * {@code Promise.allOf(...)}, which is Temporal's way of saying <em>wait until all of these
 * asynchronous operations finish</em> — a durable {@code Promise.all}.</p>
 */
@WorkflowInterface
public interface FleetMissionWorkflow {

    @WorkflowMethod
    List<MissionSnapshot> dispatchFleet(List<com.aerosaga.domain.MissionCommand> missions);

    /** Live state of every child mission, keyed by mission id. */
    @QueryMethod
    Map<String, MissionSnapshot> fleetSnapshot();
}
