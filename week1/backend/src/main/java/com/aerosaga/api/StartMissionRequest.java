package com.aerosaga.api;

import com.aerosaga.domain.MissionCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * What the dashboard POSTs to <code>/api/missions</code>.
 *
 * <p>Deliberately a different type from {@link MissionCommand}: the API contract and the workflow
 * contract evolve at different rates, and conflating them means a REST change silently rewrites
 * Temporal's event history.</p>
 */
public record StartMissionRequest(
        @NotBlank String droneId,
        @NotBlank String destinationLabel,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double destLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double destLng,
        // Boxed Double + @PositiveOrZero so the field is genuinely optional: an omitted
        // payload becomes null and is defaulted below instead of failing validation at 0.
        @PositiveOrZero Double payloadKg) {

    public MissionCommand toCommand(
            String missionId, String originLabel, double originLat, double originLng) {
        return MissionCommand.of(
                missionId, droneId, originLabel, originLat, originLng,
                destinationLabel, destLat, destLng,
                payloadKg == null || payloadKg <= 0 ? 1.0 : payloadKg);
    }
}
