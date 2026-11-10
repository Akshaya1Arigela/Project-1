package com.aerosaga.workflow;

import com.aerosaga.activity.FleetActivities;
import com.aerosaga.domain.DroneHealth;
import com.aerosaga.domain.FleetScan;
import com.aerosaga.domain.Incident;
import com.aerosaga.domain.SupervisorCommand;
import com.aerosaga.domain.SupervisorReport;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The supervisor loop.
 *
 * <h2>What one tick does</h2>
 * <ol>
 *   <li>Ask the {@code scanFleet} activity for a snapshot of every airframe.</li>
 *   <li>For each drone that is <em>on a mission</em> and has been silent longer than the grace
 *       window, raise a {@code LINK_LOST} incident — once per mission, not once per tick.</li>
 *   <li>If automatic intervention is on, signal that mission's workflow to abort and return home.
 *       The supervisor does not fly the drone; the mission workflow compensates itself.</li>
 *   <li>Warn once about any flying drone below the battery floor.</li>
 *   <li>Park on a durable timer until the next scan, or until a stop signal arrives.</li>
 * </ol>
 *
 * <h2>Determinism rules obeyed here</h2>
 * <ul>
 *   <li>No {@code System.currentTimeMillis()} — time comes from {@code Workflow.currentTimeMillis()}
 *       or from the scan activity's {@code scannedAt}.</li>
 *   <li>No randomness: incident ids are derived from the tick number and the drone id.</li>
 *   <li>No collections iterated in a way that depends on hash order: dedupe sets are only used for
 *       membership, never for ordering, and incidents are kept in an explicit {@code ArrayList}.</li>
 * </ul>
 *
 * <h2>Why continue-as-new</h2>
 * <p>A supervisor scanning every ten seconds for a week is 60,000 events. Temporal will happily
 * keep them, but every replay gets slower and the history gets bigger than it should be. So every
 * {@code maxTicksPerRun} scans the workflow hands its totals to a fresh run and closes the current
 * history. To the outside world nothing changes: same workflow id, same queries, same signals.</p>
 */
public class FleetSupervisorWorkflowImpl implements FleetSupervisorWorkflow {

    /** How many incidents the query returns for the dashboard. */
    private static final int RECENT_LIMIT = 8;

    private final FleetActivities fleet = Workflow.newActivityStub(
            FleetActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(20))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(10))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    private SupervisorCommand command;
    private SupervisorReport report;
    private boolean autoIntervene;
    private boolean stopRequested;
    private String stopReason;

    private int run = 1;
    private int ticks;
    private int incidents;
    private int interventions;
    private long startedAt;
    private long lastScanAt;

    private int dronesWatched;
    private int online;
    private int offline;
    private int onMission;
    private boolean stormActive;

    /** Incidents already shown to the dashboard, oldest first. Capped, not the audit trail. */
    private final List<Incident> recent = new ArrayList<>();

    /** Missions already raised this run — the difference between one incident and one per scan. */
    private final Set<String> handled = new HashSet<>();

    /** Missions already warned about battery, so a long flight does not spam the feed. */
    private final Set<String> batteryWarned = new HashSet<>();

    @Override
    public SupervisorReport supervise(SupervisorCommand cmd) {
        this.command = cmd;
        this.autoIntervene = cmd.autoIntervene();
        this.ticks = cmd.carriedTicks();
        this.incidents = cmd.carriedIncidents();
        this.interventions = cmd.carriedInterventions();
        this.startedAt = Workflow.currentTimeMillis();
        this.run = cmd.maxTicksPerRun() <= 0
                ? 1
                : 1 + (cmd.carriedTicks() / cmd.maxTicksPerRun());
        this.report = SupervisorReport.starting(cmd, startedAt, run);

        int ticksThisRun = 0;

        while (!stopRequested) {
            ticksThisRun++;
            ticks++;

            FleetScan scan = null;
            String scanFailure = null;
            try {
                scan = fleet.scanFleet();
            } catch (ActivityFailure e) {
                scanFailure = rootMessage(e);
            }

            if (scan != null) {
                lastScanAt = scan.scannedAt();
                stormActive = scan.stormActive();
                inspect(scan);
                report = build("WATCHING", "Scan #" + ticks + " — " + dronesWatched
                        + " airframes, " + offline + " silent, " + onMission + " on mission");
            } else {
                // A failed scan is itself an incident: the supervisor is blind, which an operator
                // must know about. No auto-intervention, because we have nothing to act on.
                incidents++;
                remember(new Incident(
                        "inc-" + ticks + "-scan",
                        Workflow.currentTimeMillis(),
                        null, null,
                        "WARN", "SCAN_FAILED",
                        "Fleet scan failed: " + scanFailure,
                        Incident.NONE));
                report = build("DEGRADED", "Fleet scan failed: " + scanFailure);
            }

            if (cmd.maxTicksPerRun() > 0 && ticksThisRun >= cmd.maxTicksPerRun()) {
                report = build("CONTINUING",
                        "Event history reached " + ticksThisRun
                                + " scans — continuing as new with totals intact");
                Workflow.continueAsNew(command.forNextRun(
                        ticks, incidents, interventions, autoIntervene));
            }

            Workflow.await(Duration.ofSeconds(cmd.scanIntervalSeconds()), () -> stopRequested);
        }

        report = build("STOPPED", stopReason == null
                ? "Supervisor stopped by operator"
                : "Supervisor stopped: " + stopReason);
        return report;
    }

    @Override
    public SupervisorReport report() {
        return report;
    }

    @Override
    public void setAutoIntervene(boolean enabled) {
        this.autoIntervene = enabled;
        report = build(report == null ? "WATCHING" : report.status(),
                "Automatic intervention " + (enabled ? "ENABLED" : "DISABLED") + " by operator");
    }

    @Override
    public void stop(String reason) {
        this.stopRequested = true;
        this.stopReason = reason;
    }

    // ------------------------------------------------------------------ one tick

    private void inspect(FleetScan scan) {
        dronesWatched = scan.size();
        int up = 0;
        int down = 0;
        int flying = 0;
        for (DroneHealth drone : scan.drones()) {
            if (drone.linkUp()) {
                up++;
            } else {
                down++;
            }
            if (drone.onMission()) {
                flying++;
            }
        }
        online = up;
        offline = down;
        onMission = flying;

        long graceMillis = command.offlineGraceSeconds() * 1000L;

        for (DroneHealth drone : scan.drones()) {
            if (!drone.onMission()) {
                continue;
            }

            // Link came back: forget the mission so a *second* outage raises a fresh incident.
            if (drone.linkUp()) {
                handled.remove(drone.missionId());
                batteryWarned.remove(drone.missionId());
            } else {
                long silentMillis = drone.silentForMillis(scan.scannedAt());
                if (silentMillis >= graceMillis && handled.add(drone.missionId())) {
                    raiseLinkLost(drone, silentMillis);
                }
            }

            if (drone.linkUp() && drone.batteryPct() < command.lowBatteryFloorPct()) {
                if (batteryWarned.add(drone.missionId())) {
                    raiseLowBattery(drone);
                }
            }
        }
    }

    private void raiseLinkLost(DroneHealth drone, long silentMillis) {
        long silentSeconds = silentMillis / 1000L;
        String detail = "No radio for " + silentSeconds + "s while flying mission "
                + drone.missionId() + " with " + Math.round(drone.batteryPct()) + "% battery";
        String action = Incident.NONE;

        if (autoIntervene) {
            if (drone.workflowId() == null) {
                detail += " — cannot intervene: no workflow id for this mission";
            } else {
                try {
                    fleet.orderReturnHome(drone.workflowId(), drone.droneId(),
                            "supervisor: no radio for " + silentSeconds + "s");
                    interventions++;
                    action = Incident.RETURN_HOME_SIGNALLED;
                } catch (ActivityFailure e) {
                    action = Incident.INTERVENTION_FAILED;
                    detail += " — intervention failed: " + rootMessage(e);
                }
            }
        }

        incidents++;
        remember(new Incident(
                "inc-" + ticks + "-" + drone.droneId(),
                Workflow.currentTimeMillis(),
                drone.droneId(),
                drone.missionId(),
                "HIGH",
                "LINK_LOST",
                detail,
                action));
    }

    private void raiseLowBattery(DroneHealth drone) {
        incidents++;
        remember(new Incident(
                "inc-" + ticks + "-batt-" + drone.droneId(),
                Workflow.currentTimeMillis(),
                drone.droneId(),
                drone.missionId(),
                "WARN",
                "LOW_BATTERY",
                "Battery at " + Math.round(drone.batteryPct()) + "% — below the "
                        + Math.round(command.lowBatteryFloorPct())
                        + "% floor; the mission's own compensation will bring it home",
                Incident.NONE));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Keep the incident locally <em>and</em> persist it.
     *
     * <p>Persistence is an activity, so it is retried and its result is durable. If it still fails
     * after the retry policy, the incident is not lost from this run's report — the supervisor
     * keeps working rather than dying because a log write failed.</p>
     */
    private void remember(Incident incident) {
        recent.add(incident);
        while (recent.size() > RECENT_LIMIT) {
            recent.remove(0);
        }
        try {
            fleet.recordIncident(incident);
        } catch (ActivityFailure e) {
            // Deliberately swallowed: the incident is already in the workflow's own report.
            Workflow.getLogger(FleetSupervisorWorkflowImpl.class)
                    .warn("Could not persist incident {}: {}", incident.id(), rootMessage(e));
        }
    }

    private SupervisorReport build(String status, String detail) {
        List<Incident> newestFirst = new ArrayList<>(recent);
        java.util.Collections.reverse(newestFirst);
        return new SupervisorReport(
                command.supervisorId(),
                startedAt,
                lastScanAt,
                run,
                ticks,
                dronesWatched,
                online,
                offline,
                onMission,
                stormActive,
                incidents,
                interventions,
                autoIntervene,
                status,
                detail,
                command.scanIntervalSeconds(),
                command.offlineGraceSeconds(),
                command.lowBatteryFloorPct(),
                newestFirst);
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
