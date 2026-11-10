package com.aerosaga.mission;

import com.aerosaga.domain.FleetCommand;
import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.supabase.SupabaseClient;
import com.aerosaga.supabase.SupabaseMissionRow;
import com.aerosaga.workflow.DroneMissionWorkflow;
import com.aerosaga.workflow.FleetMissionWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only place in the app that talks to Temporal.
 *
 * <h2>The fact that makes the whole project work</h2>
 * <p>Starting a workflow is not like running a thread. {@code WorkflowClient.start} appends the
 * first events to a durable history in Temporal's database and returns. If your JVM dies one
 * millisecond later, the mission still runs — it just has no worker until you start one.</p>
 */
@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    /** Workflow ids are namespaced so a mission and a fleet never collide in the visibility store. */
    private static final String MISSION_WORKFLOW_PREFIX = "mission-";
    private static final String DRONE_WORKFLOW_TYPE = "DroneMissionWorkflow";

    private final WorkflowClient workflowClient;
    private final SupabaseClient supabase;
    private final MissionIndex index;
    private final MissionRecorder recorder;
    private final com.aerosaga.telemetry.TelemetryEngine telemetry;
    private final String taskQueue;
    private final String namespace;

    private final Map<String, DroneMissionWorkflow> running = new ConcurrentHashMap<>();

    public MissionService(
            WorkflowClient workflowClient,
            SupabaseClient supabase,
            MissionIndex index,
            MissionRecorder recorder,
            com.aerosaga.telemetry.TelemetryEngine telemetry,
            @Value("${aerosaga.temporal.task-queue}") String taskQueue,
            @Value("${aerosaga.temporal.namespace}") String namespace) {
        this.workflowClient = workflowClient;
        this.supabase = supabase;
        this.index = index;
        this.recorder = recorder;
        this.telemetry = telemetry;
        this.taskQueue = taskQueue;
        this.namespace = namespace;
        log.info("MissionService bound to task queue '{}'", taskQueue);
    }

    // ------------------------------------------------------------------ start

    /** Fire-and-forget. Returns as soon as the history row is written. */
    public String start(MissionCommand command) {
        DroneMissionWorkflow workflow = workflowClient.newWorkflowStub(
                DroneMissionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(taskQueue)
                        .setWorkflowId(workflowId(command.missionId()))
                        .build());
        try {
            WorkflowClient.start(workflow::runMission, command);
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.warn("Mission {} was already started", command.missionId());
        }
        running.put(command.missionId(), workflow);
        index.put(command.missionId(), command);
        recorder.recordStart(command, taskQueue);
        log.info("Started DroneMissionWorkflow {} for drone {}",
                command.missionId(), command.droneId());
        return command.missionId();
    }

    /**
     * Fan out to a whole fleet. One parent workflow, N children, and a {@code Promise.allOf}
     * barrier holding them together.
     */
    public String dispatchFleet(List<MissionCommand> missions) {
        String fleetId = "fleet-" + UUID.randomUUID().toString().substring(0, 8);
        FleetCommand command = new FleetCommand(missions);

        FleetMissionWorkflow workflow = workflowClient.newWorkflowStub(
                FleetMissionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(taskQueue)
                        .setWorkflowId(fleetId)
                        .build());

        try {
            WorkflowClient.start(workflow::dispatchFleet, command.missions());
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.warn("Fleet {} was already started", fleetId);
        }

        for (MissionCommand mission : missions) {
            index.put(mission.missionId(), mission);
            recorder.recordStart(mission, taskQueue);
            // Register a stub per CHILD, not just for the parent. The children are started by the
            // parent workflow, so this process never sees their "start" call — without this the
            // dashboard would show an empty mission list while five missions are actually running.
            running.put(mission.missionId(), newTargetedStub(mission.missionId()));
        }
        log.info("Started FleetMissionWorkflow {} with {} child missions", fleetId, missions.size());
        return fleetId;
    }

    // ------------------------------------------------------------------ read & signal

    /**
     * Calls the workflow's {@code @QueryMethod}. This runs against the live in-memory workflow
     * instance held by a worker — there is no database read in this call path at all, which is
     * exactly why the dashboard can poll it once a second.
     */
    public MissionSnapshot snapshot(String missionId) {
        try {
            return resolve(missionId).snapshot();
        } catch (RuntimeException e) {
            throw new MissionNotFoundException(missionId, e);
        }
    }

    /** Walk a mission back out of its durable suspension. */
    public void reconnect(String missionId) {
        resolve(missionId).reconnect();
        log.info("Signalled reconnect to mission {}", missionId);
    }

    public void abort(String missionId, String reason) {
        resolve(missionId).abort(reason);
        log.info("Signalled abort to mission {}: {}", missionId, reason);
    }

    public List<MissionSnapshot> list() {
        List<MissionSnapshot> result = new ArrayList<>();
        running.forEach((missionId, workflow) -> {
            try {
                MissionSnapshot snapshot = workflow.snapshot();
                if (!isTerminal(snapshot)) {
                    result.add(snapshot);
                }
            } catch (RuntimeException e) {
                log.debug("Skipping mission {} — {}", missionId, e.getMessage());
            }
        });
        return result;
    }

    public List<String> runningMissionIds() {
        return list().stream().map(MissionSnapshot::missionId).toList();
    }

    public static boolean isTerminal(MissionSnapshot snapshot) {
        return switch (snapshot.phase()) {
            case DELIVERED, COMPENSATED, FAILED, ABORTED -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ restart recovery

    /**
     * Re-attach to missions that were already running when this process died.
     *
     * <p>This is the trick that makes the Mid-Project Review audit a one-liner. Temporal still has
     * every open workflow; all we have to do is rebuild a stub from the workflow id. Where the list
     * of ids comes from, in order of preference:</p>
     *
     * <ol>
     *   <li><b>Supabase</b> — our own bookkeeping: mission id, phase, timestamps. It is the honest
     *       division of labour: Temporal owns execution state, Supabase owns what we record about
     *       it.</li>
     *   <li><b>Temporal itself</b> — {@code ListWorkflowExecutions} with a visibility query, used
     *       when Supabase is not configured. Without this the resiliency audit silently shows an
     *       empty dashboard on a fresh checkout, which looks exactly like the bug we are trying to
     *       disprove.</li>
     * </ol>
     *
     * <p>Called from the worker starter thread once the worker is actually polling — not from a
     * Spring lifecycle event — so a query issued against a restored mission cannot race the
     * worker's first poll.</p>
     */
    public void restoreRunningMissions() {
        List<String> missionIds = openMissionIdsFromSupabase();
        if (missionIds.isEmpty()) {
            missionIds = openMissionIdsFromTemporal();
        }
        if (missionIds.isEmpty()) {
            log.info("No open missions to restore");
            return;
        }
        int restored = 0;
        for (String missionId : missionIds) {
            try {
                DroneMissionWorkflow stub = newTargetedStub(missionId);
                running.put(missionId, stub);
                restored++;
                // Put the drone back on the map where Temporal says it is. Without this the
                // dashboard shows five idle drones while their workflows are still parked in
                // OFFLINE_SUSPENDED — the exact confusion the audit is meant to disprove.
                try {
                    com.aerosaga.domain.MissionSnapshot snapshot = stub.snapshot();
                    telemetry.claimForMission(snapshot.droneId(), snapshot.missionId(),
                            snapshot.phase());
                    // Temporal's snapshot starts at 0,0 until the first position fix is recorded;
                    // pushing that onto the map would park the drone in the Bay of Bengal.
                    if (snapshot.lat() != 0.0 || snapshot.lng() != 0.0) {
                        telemetry.updatePosition(snapshot.droneId(), snapshot.lat(), snapshot.lng(),
                                snapshot.altitudeM(), snapshot.batteryPct());
                    }
                } catch (RuntimeException e) {
                    log.debug("No snapshot for mission {} yet: {}", missionId, e.getMessage());
                }
            } catch (RuntimeException e) {
                log.warn("Could not re-attach to mission {}: {}", missionId, e.getMessage());
            }
        }
        log.info("Re-attached to {} open mission(s) after restart — Temporal held the state", restored);
    }

    /** Supabase knows which missions we started and have not yet seen finish. */
    private List<String> openMissionIdsFromSupabase() {
        if (!supabase.isEnabled()) {
            return List.of();
        }
        // PostgREST "not in" filter: every mission whose phase is not terminal.
        List<SupabaseMissionRow> rows = supabase.select(
                "missions",
                "select=mission_id,phase&phase=not.in.(DELIVERED,COMPENSATED,FAILED,ABORTED)"
                        + "&order=started_at.desc&limit=200",
                SupabaseMissionRow.class);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (SupabaseMissionRow row : rows) {
            if (row.missionId() != null && !row.missionId().isBlank()) {
                ids.add(row.missionId());
            }
        }
        return ids;
    }

    /**
     * Fallback used when Supabase is not configured: ask Temporal's visibility store which of our
     * mission workflows are still running, and rebuild a stub for each.
     */
    private List<String> openMissionIdsFromTemporal() {
        List<String> ids = new ArrayList<>();
        try {
            io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest request =
                    io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest.newBuilder()
                            .setNamespace(namespace)
                            .setPageSize(200)
                            .setQuery("WorkflowType = '" + DRONE_WORKFLOW_TYPE
                                    + "' AND ExecutionStatus = 'Running'")
                            .build();
            var response = workflowClient.getWorkflowServiceStubs().blockingStub()
                    .listWorkflowExecutions(request);
            for (io.temporal.api.workflow.v1.WorkflowExecutionInfo info
                    : response.getExecutionsList()) {
                String workflowId = info.getExecution().getWorkflowId();
                if (workflowId.startsWith(MISSION_WORKFLOW_PREFIX)) {
                    ids.add(workflowId.substring(MISSION_WORKFLOW_PREFIX.length()));
                }
            }
            log.info("Temporal visibility returned {} open mission workflow(s)", ids.size());
        } catch (RuntimeException e) {
            log.warn("Could not list open workflows from Temporal: {}", e.getMessage());
        }
        return ids;
    }

    // ------------------------------------------------------------------ internals

    public DroneMissionWorkflow resolve(String missionId) {
        return running.computeIfAbsent(missionId, this::newTargetedStub);
    }

    /**
     * A stub that targets an EXISTING workflow execution by id.
     *
     * <p>This distinction is subtle and it bites hard. A stub built from {@link WorkflowOptions}
     * carries no workflowId until <em>you</em> start the workflow through it — ask such a stub to
     * query or signal and you get {@code IllegalStateException: Null workflowId. Was workflow
     * started?}. Fleet children are started by their parent workflow, so this process never calls
     * start on them, and an options-based stub is useless for reading their state. The
     * {@code newWorkflowStub(Class, workflowId)} overload produces a stub aimed at that execution
     * from the moment it exists — whether it is running, completed, or not started yet.</p>
     */
    private DroneMissionWorkflow newTargetedStub(String missionId) {
        return workflowClient.newWorkflowStub(DroneMissionWorkflow.class, workflowId(missionId));
    }

    /** Temporal's workflow id for a mission. One convention, one place — the Web UI greps this. */
    private static String workflowId(String missionId) {
        return "mission-" + missionId;
    }
}
