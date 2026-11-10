package com.aerosaga.api;

import com.aerosaga.domain.RouteCommand;
import com.aerosaga.domain.Waypoint;
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
 * What the dashboard POSTs to <code>/api/routes</code>.
 *
 * <p>Stops arrive in whatever order the operator typed. The workflow is what sorts them, via the
 * {@code planRoute} activity — the API deliberately does not promise an order.</p>
 */
public record StartRouteRequest(
        @NotBlank String droneId,
        @NotEmpty @Valid List<StopSpec> stops,
        @PositiveOrZero Double payloadKg,
        @DecimalMin("1") @DecimalMax("90") Double lowBatteryThresholdPct) {

    /** One delivery stop. */
    public record StopSpec(
            @NotBlank String label,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @PositiveOrZero Double altitudeM) {

        public Waypoint toWaypoint() {
            return new Waypoint(label, lat, lng, altitudeM == null || altitudeM <= 0 ? 110.0 : altitudeM);
        }
    }

    public RouteCommand toCommand(
            String missionId, String originLabel, double originLat, double originLng) {
        List<Waypoint> points = new ArrayList<>(stops.size());
        for (StopSpec stop : stops) {
            points.add(stop.toWaypoint());
        }
        return new RouteCommand(
                missionId,
                droneId,
                originLabel,
                originLat,
                originLng,
                points,
                payloadKg == null || payloadKg <= 0 ? 1.0 : payloadKg,
                lowBatteryThresholdPct == null ? 25.0 : lowBatteryThresholdPct);
    }
}
