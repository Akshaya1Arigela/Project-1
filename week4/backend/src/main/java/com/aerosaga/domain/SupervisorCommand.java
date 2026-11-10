package com.aerosaga.domain;

/**
 * The policy the fleet supervisor runs under.
 *
 * <p>Three numbers do all the work:</p>
 * <ul>
 *   <li><b>scanIntervalSeconds</b> — how often the supervisor looks at the fleet.</li>
 *   <li><b>offlineGraceSeconds</b> — how long a drone may be silent <em>on a mission</em> before it
 *       is treated as a problem rather than a blip. A storm is not an emergency in the first ten
 *       seconds; it is an emergency after thirty.</li>
 *   <li><b>maxTicksPerRun</b> — when the event history reaches this many scans the workflow
 *       continues as new, so a supervisor left running for a week does not grow without bound.</li>
 * </ul>
 *
 * <p>The {@code carried*} fields exist because continue-as-new starts a fresh event history: they
 * are how the totals survive the hand-over.</p>
 *
 * @param supervisorId        stable id; also the workflow id suffix
 * @param scanIntervalSeconds gap between scans
 * @param offlineGraceSeconds silence tolerated before an incident is raised
 * @param lowBatteryFloorPct  below this a warning incident is raised
 * @param maxTicksPerRun      scans per event history before continue-as-new (0 = never)
 * @param autoIntervene       may the supervisor signal a stranded mission to come home?
 * @param carriedTicks        scans already completed by earlier runs
 * @param carriedIncidents    incidents already raised by earlier runs
 * @param carriedInterventions interventions already sent by earlier runs
 */
public record SupervisorCommand(
        String supervisorId,
        int scanIntervalSeconds,
        int offlineGraceSeconds,
        double lowBatteryFloorPct,
        int maxTicksPerRun,
        boolean autoIntervene,
        int carriedTicks,
        int carriedIncidents,
        int carriedInterventions) {

    public SupervisorCommand {
        if (supervisorId == null || supervisorId.isBlank()) {
            throw new IllegalArgumentException("supervisorId is required");
        }
        scanIntervalSeconds = Math.max(2, scanIntervalSeconds);
        offlineGraceSeconds = Math.max(1, offlineGraceSeconds);
        if (lowBatteryFloorPct <= 0) {
            lowBatteryFloorPct = 12.0;
        }
        if (maxTicksPerRun < 0) {
            maxTicksPerRun = 0;
        }
        carriedTicks = Math.max(0, carriedTicks);
        carriedIncidents = Math.max(0, carriedIncidents);
        carriedInterventions = Math.max(0, carriedInterventions);
    }

    /** The default policy: glance every 10s, tolerate 20s of silence, intervene automatically. */
    public static SupervisorCommand of(String supervisorId) {
        return new SupervisorCommand(supervisorId, 10, 20, 12.0, 200, true, 0, 0, 0);
    }

    /** The hand-over object for {@code Workflow.continueAsNew}. */
    public SupervisorCommand forNextRun(int ticks, int incidents, int interventions,
                                        boolean interveneEnabled) {
        return new SupervisorCommand(
                supervisorId,
                scanIntervalSeconds,
                offlineGraceSeconds,
                lowBatteryFloorPct,
                maxTicksPerRun,
                interveneEnabled,
                ticks,
                incidents,
                interventions);
    }
}
