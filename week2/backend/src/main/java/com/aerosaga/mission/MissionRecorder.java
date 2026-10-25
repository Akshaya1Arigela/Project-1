package com.aerosaga.mission;

import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.MissionPhase;
import com.aerosaga.supabase.SupabaseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the mission audit trail to Supabase.
 *
 * <p>Every call is an <em>upsert</em> keyed on {@code mission_id}, so it is idempotent: replaying
 * after a crash, or retrying an activity, cannot create duplicate rows. That property is not
 * optional here — Temporal retries activities by design, so anything an activity writes must be
 * safe to run twice.</p>
 *
 * <p>If Supabase is not configured every method is a no-op and the app runs exactly as Week 1 did.</p>
 */
@Component
public class MissionRecorder {

    private static final Logger log = LoggerFactory.getLogger(MissionRecorder.class);

    private final SupabaseClient supabase;

    public MissionRecorder(SupabaseClient supabase) {
        this.supabase = supabase;
    }

    public void recordStart(MissionCommand command, String taskQueue) {
        if (!supabase.isEnabled()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mission_id", command.missionId());
        row.put("drone_id", command.droneId());
        row.put("workflow_id", "mission-" + command.missionId());
        row.put("task_queue", taskQueue);
        row.put("phase", MissionPhase.QUEUED.name());
        row.put("detail", "Workflow accepted by Temporal");
        row.put("payload_kg", command.payloadKg());
        row.put("destination_label", command.destinationLabel());
        supabase.upsert("missions", row);
        appendEvent(command.missionId(), command.droneId(), MissionPhase.QUEUED, "Workflow accepted by Temporal");
    }

    /**
     * Upsert the current phase. Called from the telemetry tick, which is why the drone's position
     * is optional — sometimes we only know that the phase changed.
     */
    public void recordPhase(MissionCommand command, MissionPhase phase,
                            Double batteryPct, Double lat, Double lng, String detail) {
        if (!supabase.isEnabled()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mission_id", command.missionId());
        row.put("drone_id", command.droneId());
        row.put("workflow_id", "mission-" + command.missionId());
        row.put("phase", phase.name());
        row.put("detail", detail);
        if (batteryPct != null) row.put("battery_pct", batteryPct);
        if (lat != null) row.put("lat", lat);
        if (lng != null) row.put("lng", lng);
        if (isTerminal(phase)) {
            row.put("completed_at", "now()");
        }
        supabase.upsert("missions", row);
        appendEvent(command.missionId(), command.droneId(), phase, detail);
    }

    /** Append-only transition log — the evidence trail for the resiliency audit. */
    private void appendEvent(String missionId, String droneId, MissionPhase phase, String detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("mission_id", missionId);
        event.put("drone_id", droneId);
        event.put("phase", phase.name());
        event.put("detail", detail);
        if (!supabase.upsert("mission_events", event)) {
            log.debug("Audit event for {} was not persisted", missionId);
        }
    }

    public static boolean isTerminal(MissionPhase phase) {
        return phase == MissionPhase.DELIVERED
                || phase == MissionPhase.COMPENSATED
                || phase == MissionPhase.FAILED
                || phase == MissionPhase.ABORTED;
    }
}
