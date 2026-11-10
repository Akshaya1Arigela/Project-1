package com.aerosaga.workflow;

import com.aerosaga.domain.PatrolCommand;
import com.aerosaga.domain.PatrolStatus;
import com.aerosaga.domain.Waypoint;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Patrol behaviour, verified without Docker.
 *
 * <p>The test server skips the interval instantly, so a patrol that waits sixty seconds between
 * laps finishes in milliseconds of wall-clock time while still creating a real timer event in its
 * history. That is the property being tested: the wait is durable, not a {@code Thread.sleep}.</p>
 */
class PatrolWorkflowTest {

    private TestWorkflowEnvironment env;
    private FakeDroneActivities activities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        activities = new FakeDroneActivities();

        Worker worker = env.newWorker("test-tq");
        // The patrol starts real child missions, so both workflow types are registered.
        worker.registerWorkflowImplementationTypes(PatrolWorkflowImpl.class,
                DroneMissionWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void fliesEveryLapAndCompletes() {
        PatrolStatus status = runPatrol("p-complete", 2, 2);

        assertEquals("COMPLETED", status.phaseLabel());
        assertFalse(status.active());
        assertEquals(2, status.cyclesCompleted());
        assertEquals(2, activities.takeoffs, "one takeoff per lap, not one per patrol");
    }

    @Test
    void aStopSignalEndsThePatrolWithoutKillingTheLapInTheAir() {
        PatrolWorkflow patrol = newStub("p-stop");
        PatrolCommand command = command("p-stop", 4, 1);
        WorkflowClient.start(patrol::runPatrol, command);

        // The signal arrives while lap 1 is airborne; it must not cut the flight short.
        patrol.stopPatrol("test: stand down");

        PatrolStatus status = WorkflowStub.fromTyped(patrol).getResult(PatrolStatus.class);
        assertFalse(status.active());
        assertEquals("STOPPED", status.phaseLabel());
        assertTrue(activities.takeoffs <= 1,
                "no new lap should start after the stop signal, got " + activities.takeoffs);
    }

    // ------------------------------------------------------------------ helpers

    private PatrolStatus runPatrol(String patrolId, int cycles, int intervalSeconds) {
        return newStub(patrolId).runPatrol(command(patrolId, cycles, intervalSeconds));
    }

    private PatrolWorkflow newStub(String patrolId) {
        return env.getWorkflowClient().newWorkflowStub(
                PatrolWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("test-tq")
                        .setWorkflowId("patrol-" + patrolId)
                        .build());
    }

    private static PatrolCommand command(String patrolId, int cycles, int intervalSeconds) {
        return new PatrolCommand(
                patrolId, "DRN-02", "Hyderabad Hub", 17.3850, 78.4867,
                intervalSeconds, cycles,
                List.of(
                        new Waypoint("North Loop", 17.4030, 78.4907, 110),
                        new Waypoint("East Dock", 17.3890, 78.5107, 110)),
                0.8);
    }
}
