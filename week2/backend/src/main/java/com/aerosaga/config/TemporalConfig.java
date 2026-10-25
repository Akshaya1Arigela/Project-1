package com.aerosaga.config;

import com.aerosaga.activity.DroneActivities;
import com.aerosaga.mission.MissionService;
import com.aerosaga.workflow.DroneMissionWorkflowImpl;
import com.aerosaga.workflow.FleetMissionWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Temporal wiring, written out longhand so the whole worker lifecycle is visible.
 *
 * <p>There is a {@code temporal-spring-boot-starter} that discovers {@code @WorkflowImpl} /
 * {@code @ActivityImpl} classes by annotation. We are not using it here on purpose: explicit
 * registration means that when the Mid-Project Review asks "where exactly does your code join the
 * Temporal cluster?", the answer is this one file.</p>
 *
 * <p><b>Worker vs workflow vs activity</b>, because the terms blur together:</p>
 * <ul>
 *   <li><b>Worker</b> — a set of polling threads in your JVM that ask Temporal for tasks.</li>
 *   <li><b>Workflow</b> — deterministic orchestration code. Registered by <em>class</em>, because
 *       Temporal instantiates one per execution and a shared instance would leak state between
 *       missions.</li>
 *   <li><b>Activity</b> — real-world side effects. Registered by <em>instance</em>, because it
 *       holds Spring collaborators (the telemetry engine, the Supabase recorder).</li>
 * </ul>
 */
@Configuration
public class TemporalConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

    @Value("${aerosaga.temporal.target:localhost:7233}")
    private String target;

    @Value("${aerosaga.temporal.namespace:default}")
    private String namespace;

    @Value("${aerosaga.temporal.task-queue:aero-tq}")
    private String taskQueue;

    /**
     * A lazy, shared gRPC channel. "Lazy" matters: the app can still boot (and its REST endpoints
     * answer) if the Docker cluster is not up yet. The channel retries in the background instead
     * of failing startup — which is what lets you do the kill-and-restart audit in any order.
     *
     * <p>{@code newInstance} builds the channel without blocking on a connection; the first RPC
     * is what actually dials.</p>
     */
    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs() {
        log.info("Connecting to Temporal at {} (namespace {})", target, namespace);
        return WorkflowServiceStubs.newInstance(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(target)
                        .setRpcTimeout(Duration.ofSeconds(10))
                        .build());
    }

    /**
     * Note: no {@code destroyMethod}. {@code WorkflowClient} is a lightweight handle over the
     * stubs and has no lifecycle of its own — the gRPC channel above owns that.
     */
    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        return WorkflowClient.newInstance(
                serviceStubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace)
                        .build());
    }

    /** One worker, one task queue, two workflow types. */
    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient workflowClient, DroneActivities activities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(taskQueue);

        // The single-mission saga...
        worker.registerWorkflowImplementationTypes(DroneMissionWorkflowImpl.class);
        // ...and the parent that fans out to many of them and waits on Promise.allOf.
        worker.registerWorkflowImplementationTypes(FleetMissionWorkflowImpl.class);

        worker.registerActivitiesImplementations(activities);

        log.info("Worker registered on task queue '{}' with 2 workflow types", taskQueue);
        return factory;
    }

    /**
     * Starts the polling threads — but never blocks or fails application startup.
     *
     * <p>{@code WorkerFactory.start()} issues a blocking {@code DescribeNamespace} RPC, so if the
     * Docker cluster is not up yet it throws and would take the whole app down with it. That is
     * a bad first-run experience: you naturally start the backend before remembering the
     * cluster. So the start happens on a background thread that retries every 5 seconds until it
     * succeeds, and {@link WorkerStatus} tells the dashboard whether we are actually polling yet.</p>
     */
    @Bean
    public ApplicationRunner startTemporalWorkers(
            WorkerFactory workerFactory, WorkerStatus workerStatus, MissionService missionService) {
        return args -> {
            Thread starter = new Thread(() -> {
                int attempt = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        workerFactory.start();
                        workerStatus.markPolling();
                        log.info("Temporal worker factory started — polling task queue '{}'", taskQueue);
                        // Only once we are actually polling can a query against a live workflow
                        // be answered, which is the right moment to re-attach to open missions.
                        missionService.restoreRunningMissions();
                        return;
                    } catch (RuntimeException e) {
                        attempt++;
                        String message = rootMessage(e);
                        workerStatus.markFailed(message);
                        log.warn(
                                "Temporal at {} is not reachable (attempt {}): {}. "
                                        + "Is `docker compose up -d` running? Retrying in 5s.",
                                target, attempt, message);
                        try {
                            Thread.sleep(5_000);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "temporal-worker-starter");
            starter.setDaemon(true);
            starter.start();
        };
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor.getCause() != null && cursor.getCause() != cursor && depth++ < 20) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return cursor.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
