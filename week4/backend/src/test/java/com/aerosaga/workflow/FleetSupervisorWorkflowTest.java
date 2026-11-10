package com.aerosaga.workflow;

import com.aerosaga.domain.DroneHealth;
import com.aerosaga.domain.Incident;
import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.SupervisorCommand;
import com.aerosaga.domain.SupervisorReport;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Week 4 supervisor tests.
 *
 * <p>The supervisor is a workflow that never ends on its own, so each test starts it, waits for
 * the scans it needs, sends the stop signal and then reads the final report. That is exactly how
 * an operator drives it, which makes the tests decent documentation of the REST endpoints too.</p>
 *
 * <p>Both wait loops are driven by the real 2-second scan timer, so a test takes a few seconds —
 * the price of testing the real loop instead of a mocked one.</p>
 */
class FleetSupervisorWorkflowTest {

    private static final String TASK_QUEUE = "test-supervisor-tq";

    private TestWorkflowEnvironment env;
    private FakeFleetActivities fleet;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        fleet = new FakeFleetActivities();

        Worker worker = env.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(FleetSupervisorWorkflowImpl.class);
        worker.registerActivitiesImplementations(fleet);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void silentDroneOnMissionRaisesOneIncidentAndSignalsItHome() {
        fleet.drones.add(health("DRN-07", "m-abc123", false, 64.0, 90_000L));

        SupervisorReport report = runSupervisor(command(5, true), 1);

        assertEquals(1, report.incidents(), "one incident for one stranded mission");
        assertEquals(1, report.interventions(), "auto-intervene is on, so one signal was sent");
        assertEquals(1, report.offline(), "the scan saw one silent airframe");
        assertEquals(1, report.onMission(), "and it was flying a mission");

        assertEquals(1, fleet.recorded.size(), report.detail());
        Incident incident = fleet.recorded.get(0);
        assertEquals("LINK_LOST", incident.kind());
        assertEquals("HIGH", incident.severity());
        assertEquals(Incident.RETURN_HOME_SIGNALLED, incident.action());
        assertEquals("mission-m-abc123", fleet.returnHomeRequests.get(0));
    }

    @Test
    void theSameOutageIsNotReportedOnEveryScan() {
        // Grace is 1 second and the drone has been silent for 90, so every tick qualifies. The
        // supervisor must still raise exactly one incident per outage.
        fleet.drones.add(health("DRN-07", "m-abc123", false, 64.0, 90_000L));

        SupervisorReport report = runSupervisor(command(1, true), 2);

        assertEquals(1, report.incidents(), "dedupe by mission id, not by scan");
        assertEquals(1, fleet.returnHomeRequests.size(), "one intervention, not one per scan");
        assertTrue(report.ticks() >= 2, "two scans actually happened");
    }

    @Test
    void observerModeRaisesTheIncidentButSendsNothing() {
        fleet.drones.add(health("DRN-07", "m-abc123", false, 64.0, 90_000L));

        SupervisorReport report = runSupervisor(command(5, false), 1);

        assertEquals(1, report.incidents());
        assertEquals(0, report.interventions(), "auto-intervene is off");
        assertEquals(Incident.NONE, fleet.recorded.get(0).action());
        assertTrue(fleet.returnHomeRequests.isEmpty(), "no signal may be sent in observer mode");
    }

    @Test
    void aHealthyFleetProducesNoNoise() {
        fleet.drones.add(health("DRN-01", "m-ok1", true, 82.0, 250L));
        fleet.drones.add(health("DRN-02", null, true, 97.0, 400L));

        SupervisorReport report = runSupervisor(command(5, true), 1);

        assertEquals(0, report.incidents(), report.detail());
        assertEquals(0, report.interventions());
        assertTrue(fleet.recorded.isEmpty());
        assertEquals(2, report.dronesWatched());
        assertEquals(2, report.online());
    }

    @Test
    void aFailedScanIsItselfAnIncident() {
        fleet.failScan = true;

        SupervisorReport report = runSupervisor(command(1, true), 1);

        // The final report always says STOPPED (that is the signal that ended it), so the evidence
        // of blindness is the incident itself plus the counter.
        assertTrue(report.incidents() >= 1, report.detail());
        assertEquals("SCAN_FAILED", fleet.recorded.get(0).kind());
        assertTrue(fleet.recorded.get(0).detail().contains("telemetry engine unreachable"),
                fleet.recorded.get(0).detail());
    }

    // ------------------------------------------------------------------ helpers

    private SupervisorReport runSupervisor(SupervisorCommand command, int ticksToWait) {
        FleetSupervisorWorkflow workflow = env.getWorkflowClient().newWorkflowStub(
                FleetSupervisorWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("supervisor-test-" + UUID.randomUUID())
                        .build());

        WorkflowClient.start(workflow::supervise, command);
        awaitTicks(workflow, ticksToWait);
        workflow.stop("test finished");

        SupervisorReport report = WorkflowStub.fromTyped(workflow)
                .getResult(SupervisorReport.class);

        assertEquals("STOPPED", report.status(), "the stop signal is what ends the loop");
        assertFalse(report.recentIncidents().isEmpty() && report.incidents() > 0,
                "recent incidents mirror the counter");
        return report;
    }

    private static void awaitTicks(FleetSupervisorWorkflow workflow, int ticks) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (workflow.report().ticks() >= ticks) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // worker has not picked the workflow up yet — keep waiting
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("supervisor did not reach " + ticks + " scan(s) in 20s");
    }

    private static SupervisorCommand command(int graceSeconds, boolean autoIntervene) {
        // Scan every 2s (the schema floor), grace 1–5s, never continue-as-new in a test.
        return new SupervisorCommand(
                "test-supervisor", 2, graceSeconds, 12.0, 0, autoIntervene, 0, 0, 0);
    }

    private static DroneHealth health(String droneId, String missionId, boolean linkUp,
                                      double batteryPct, long silentForMillis) {
        return new DroneHealth(
                droneId,
                "Test-" + droneId,
                missionId == null ? MissionPhase.QUEUED : MissionPhase.NAVIGATING,
                missionId,
                missionId == null ? null : "mission-" + missionId,
                linkUp,
                batteryPct,
                System.currentTimeMillis() - silentForMillis,
                missionId == null ? "idle" : "on-mission");
    }
}
