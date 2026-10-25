package com.aerosaga.api;

import com.aerosaga.domain.MissionCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispatch several drones at once, so the dashboard has a real {@code Promise.allOf} fan-out to
 * show rather than a single mission.
 */
public record DispatchFleetRequest(@NotEmpty @Valid List<MissionSpec> missions) {

    public record MissionSpec(
            @NotBlank String droneId,
            @NotBlank String destinationLabel,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double destLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double destLng,
            // Optional: an omitted payload defaults to 1.0 kg rather than failing validation.
            @PositiveOrZero Double payloadKg) {

        public MissionCommand toCommand(
                String missionId, String originLabel, double originLat, double originLng) {
            return MissionCommand.of(
                    missionId, droneId, originLabel, originLat, originLng,
                    destinationLabel, destLat, destLng,
                    payloadKg == null || payloadKg <= 0 ? 1.0 : payloadKg);
        }
    }

    public List<MissionCommand> toCommands(String originLabel, double originLat, double originLng) {
        List<MissionCommand> result = new ArrayList<>(missions.size());
        for (MissionSpec spec : missions) {
            result.add(spec.toCommand(newMissionId(), originLabel, originLat, originLng));
        }
        return result;
    }

    private static String newMissionId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
