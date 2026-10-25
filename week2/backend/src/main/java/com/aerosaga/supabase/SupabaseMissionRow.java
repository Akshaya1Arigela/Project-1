package com.aerosaga.supabase;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Row shape of the {@code missions} audit table.
 *
 * <p>Kept separate from {@code DroneRow} on purpose: the two tables evolve independently, and a
 * single shared DTO is how a column rename on one silently breaks the other.</p>
 */
public record SupabaseMissionRow(
        @JsonProperty("mission_id") String missionId,
        @JsonProperty("drone_id") String droneId,
        @JsonProperty("workflow_id") String workflowId,
        @JsonProperty("task_queue") String taskQueue,
        String phase,
        String detail,
        @JsonProperty("battery_pct") Double batteryPct,
        Double lat,
        Double lng,
        @JsonProperty("payload_kg") Double payloadKg,
        @JsonProperty("destination_label") String destinationLabel,
        @JsonProperty("started_at") String startedAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("completed_at") String completedAt) {
}
