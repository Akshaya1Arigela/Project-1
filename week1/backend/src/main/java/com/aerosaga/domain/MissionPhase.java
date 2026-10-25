package com.aerosaga.domain;

/**
 * The lifecycle of a mission as the UI sees it.
 *
 * <p>This is deliberately a flat enum rather than a nested state machine: the dashboard reads it
 * straight off the workflow's {@code @QueryMethod}, so it has to stay trivially serialisable and
 * trivially greppable in the Temporal Web UI.</p>
 */
public enum MissionPhase {

    /** Accepted but no worker has picked it up yet. */
    QUEUED,

    /** Rotors spooling, leaving the pad. */
    TAKEOFF,

    /** In transit between waypoints. */
    NAVIGATING,

    /** Hovering over the destination, releasing the parcel. */
    DROPPING,

    /** Flying back to the home base. */
    RETURNING_TO_BASE,

    /**
     * THE interesting state from the problem statement. The drone lost its link mid-flight.
     * The workflow is parked inside Temporal on a durable {@code Workflow.await(...)} — no thread
     * is blocked, no poller is running, no timeout is counting down. It can stay here for two
     * hours and resume exactly where it left off.
     */
    OFFLINE_SUSPENDED,

    /** Something went wrong; the saga is undoing earlier steps in reverse order. */
    COMPENSATING,

    /** Compensation finished — the drone is home, the parcel was never delivered. */
    COMPENSATED,

    /** Parcel delivered and drone returned. The happy path. */
    DELIVERED,

    /** Unrecoverable failure after retries were exhausted. */
    FAILED,

    /** An operator signalled an abort. */
    ABORTED
}
