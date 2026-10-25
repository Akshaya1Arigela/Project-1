package com.aerosaga.config;

import com.aerosaga.activity.DroneActivities;
import com.aerosaga.workflow.DroneMissionWorkflowImpl;
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
 * <p>Order matters: {@code WorkflowServiceStubs} opens the gRPC channel, {@code WorkflowClient}
 * wraps it with a namespace, {@code WorkerFactory} owns the polling threads, and
 * {@link ApplicationRunner} is what actually calls {@code start()}. If you forget the last step
 * your app boots happily and silently does no work.</p>
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
     * A lazy, shared gRPC channel to the Temporal frontend. "Lazy" is important: it means the
     * app can still boot (and its REST endpoints answer) if the Docker cluster is not up yet.
     * {@code newInstance} builds the channel without blocking on a connection; the first RPC is
     * what actually dials.
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

    /**
     * One worker, one task queue, one workflow type. Week 2 adds {@code FleetMissionWorkflow} to
     * this same worker rather than spinning up a second one.
     */
    @Bean(destroyMethod = "shutdown")
    public WorkerFactory workerFactory(WorkflowClient workflowClient, DroneActivities activities) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(taskQueue);

        // Workflows are registered by CLASS — Temporal instantiates them per workflow execution,
        // so a shared instance would leak state between missions.
        worker.registerWorkflowImplementationTypes(DroneMissionWorkflowImpl.class);

        // Activities are registered by INSTANCE because they hold real collaborators
        // (the drone registry, the Supabase client).
        worker.registerActivitiesImplementations(activities);

        log.info("Worker registered on task queue '{}'", taskQueue);
        return factory;
    }

    /**
     * Starts the polling threads — but never blocks or fails application startup.
     *
     * <p>{@code WorkerFactory.start()} issues a blocking {@code DescribeNamespace} RPC, so if the
     * Docker cluster is not up yet it throws and would take the whole app down with it. That is
     * a bad first-run experience: you naturally start the backend before remembering the
     * cluster. So the start happens on a background thread that retries every 5 seconds until it
     * succeeds, and {@link WorkerStatus} tells the dashboard whether we are polling yet.</p>
     */
    @Bean
    public ApplicationRunner startTemporalWorkers(WorkerFactory workerFactory, WorkerStatus workerStatus) {
        return args -> {
            Thread starter = new Thread(() -> {
                int attempt = 0;
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        workerFactory.start();
                        workerStatus.markPolling();
                        log.info("Temporal worker factory started — polling task queue '{}'", taskQueue);
                        return;
                    } catch (RuntimeException e) {
                        attempt++;
                        workerStatus.markFailed(rootMessage(e));
                        log.warn(
                                "Temporal at {} is not reachable (attempt {}): {}. "
                                        + "Is `docker compose up -d` running? Retrying in 5s.",
                                target, attempt, rootMessage(e));
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
