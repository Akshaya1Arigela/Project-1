package com.aerosaga.workflow;

import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.RouteCommand;
import com.aerosaga.domain.RouteProgress;
import com.aerosaga.domain.Waypoint;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Week 3 saga tests, run against Temporal's in-process (time-skipping) server.
 *
 * <p>The workflow under test is the real {@link RouteMissionWorkflowImpl}. Only the activities are
 * faked, because activities are the side-effect boundary — the thing Temporal explicitly lets you
 * replace in a test. That gives real replay, real timers and real history with no Docker
 * required, which is the honest way to verify a workflow on a machine without a cluster.</p>
 */
class RouteMissionWorkflowTest {

    private TestWorkflowEnvironment env;
    private FakeDroneActivities activities;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        activities = new FakeDroneActivities();

        Worker worker = env.newWorker("test-tq");
        worker.registerWorkflowImplementationTypes(RouteMissionWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void deliversEveryStopAndReturnsHome() {
        RouteProgress progress = runRoute(3, 60.0, 10.0);

        assertEquals(MissionPhase.DELIVERED, progress.phase(), progress.detail());
        assertEquals(3, progress.stopsDelivered());
        assertEquals(3, progress.stopsPlanned());
        assertEquals(3, activities.drops, "one parcel per stop");
        assertEquals(1, activities.takeoffs, "one takeoff for the whole route");
        assertEquals(1, activities.returns, "one return to base at the end");
    }

    @Test
    void lowBatteryCompensatesInsteadOfDroppingTheRest() {
        // 20% battery is below the 25% threshold carried in the command.
        RouteProgress progress = runRoute(3, 20.0, 25.0);

        assertEquals(MissionPhase.COMPENSATED, progress.phase(), progress.detail());
        assertEquals(0, activities.drops, "no parcel is dropped on a doomed flight");
        assertEquals(1, activities.compensations, "the saga undoes the flight exactly once");
        assertTrue(progress.detail().startsWith("COMPENSATED"),
                "detail must explain why: " + progress.detail());
    }

    @Test
    void aFailingActivityStillProducesATerminalOutcome() {
        activities.failDrop = true;
        RouteProgress progress = runRoute(2, 80.0, 10.0);

        assertEquals(MissionPhase.COMPENSATED, progress.phase(), progress.detail());
        assertEquals(1, activities.compensations);
    }

    // ------------------------------------------------------------------ helpers

    private RouteProgress runRoute(int stopCount, double battery, double threshold) {
        activities.battery = battery;

        List<Waypoint> stops = new ArrayList<>();
        for (int i = 1; i <= stopCount; i++) {
            stops.add(new Waypoint("Stop " + i, 17.40 + i * 0.005, 78.45 + i * 0.004, 110));
        }
        RouteCommand command = new RouteCommand(
                "test-" + UUID.randomUUID().toString().substring(0, 6),
                "DRN-01", "Hyderabad Hub", 17.3850, 78.4867, stops, 2.0, threshold);

        RouteMissionWorkflow workflow = env.getWorkflowClient().newWorkflowStub(
                RouteMissionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("test-tq")
                        .setWorkflowId("route-" + command.missionId())
                        .build());
        return workflow.deliverRoute(command);
    }
}
