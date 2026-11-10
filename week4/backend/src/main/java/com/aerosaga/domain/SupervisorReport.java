package com.aerosaga.domain;

import java.util.List;

/**
 * What the supervisor currently knows — returned by its {@code @QueryMethod}.
 *
 * <p>Exactly like Week 2's mission snapshot: this is read from the live workflow object a worker
 * holds in memory, not from a database. Polling it once a second is cheap and cannot fail as long
 * as the workflow is running.</p>
 *
 * @param supervisorId    supervisor id
 * @param startedAt       epoch millis this run started (resets on continue-as-new)
 * @param lastScanAt      epoch millis of the most recent scan
 * @param run             which event history this is: 1, 2, 3… after continue-as-new
 * @param ticks           total scans across every run
 * @param dronesWatched   airframes seen in the last scan
 * @param online          of those, how many had a working radio
 * @param offline         of those, how many were silent
 * @param onMission       of those, how many had a mission in flight
 * @param stormActive     simulator storm state at the last scan
 * @param incidents       total incidents raised across every run
 * @param interventions   total return-home signals sent across every run
 * @param autoIntervene   current policy switch (can be toggled with a signal)
 * @param status          {@code STARTING}, {@code WATCHING}, {@code DEGRADED}, {@code STOPPED}…
 * @param detail          one sentence explaining the status
 * @param scanIntervalSeconds policy in force
 * @param offlineGraceSeconds policy in force
 * @param lowBatteryFloorPct  policy in force
 * @param recentIncidents the newest incidents, newest first, capped for the dashboard
 */
public record SupervisorReport(
        String supervisorId,
        long startedAt,
        long lastScanAt,
        int run,
        int ticks,
        int dronesWatched,
        int online,
        int offline,
        int onMission,
        boolean stormActive,
        int incidents,
        int interventions,
        boolean autoIntervene,
        String status,
        String detail,
        int scanIntervalSeconds,
        int offlineGraceSeconds,
        double lowBatteryFloorPct,
        List<Incident> recentIncidents) {

    public SupervisorReport {
        recentIncidents = recentIncidents == null ? List.of() : List.copyOf(recentIncidents);
    }

    public static SupervisorReport starting(SupervisorCommand command, long startedAt, int run) {
        return new SupervisorReport(
                command.supervisorId(),
                startedAt,
                0L,
                run,
                command.carriedTicks(),
                0, 0, 0, 0,
                false,
                command.carriedIncidents(),
                command.carriedInterventions(),
                command.autoIntervene(),
                "STARTING",
                "Supervisor starting — scanning every " + command.scanIntervalSeconds() + "s",
                command.scanIntervalSeconds(),
                command.offlineGraceSeconds(),
                command.lowBatteryFloorPct(),
                List.of());
    }
}
