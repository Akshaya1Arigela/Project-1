package com.aerosaga.activity;

import com.aerosaga.domain.FleetScan;
import com.aerosaga.domain.Incident;
import io.temporal.activity.ActivityInterface;

/**
 * The supervisor's entire view of the world.
 *
 * <p>Three activities, chosen so the workflow itself stays pure:</p>
 * <ul>
 *   <li>{@link #scanFleet()} reads the live telemetry engine. An activity, because a workflow may
 *       not read ambient state — otherwise a replay could see a different fleet and take a
 *       different branch.</li>
 *   <li>{@link #recordIncident(Incident)} writes the incident to the log and, when configured, to
 *       Supabase.</li>
 *   <li>{@link #orderReturnHome(String, String, String)} is the intervention: it sends the Week 2
 *       {@code abort} signal to the stranded mission workflow, which makes that workflow run its
 *       own compensation and fly home. The supervisor does not fly anything itself.</li>
 * </ul>
 */
@ActivityInterface
public interface FleetActivities {

    /** Take one snapshot of the whole fleet. */
    FleetScan scanFleet();

    /** Persist an incident (memory + Supabase when configured). */
    void recordIncident(Incident incident);

    /**
     * Tell an in-flight mission to abandon its remaining work and return to base.
     *
     * @param workflowId Temporal workflow id of the mission to signal
     * @param droneId    airframe, for the log line
     * @param reason     why the supervisor intervened
     */
    void orderReturnHome(String workflowId, String droneId, String reason);
}
