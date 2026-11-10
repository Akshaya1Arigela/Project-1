package com.aerosaga.api;

import com.aerosaga.domain.PatrolCommand;
import com.aerosaga.domain.Waypoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.ArrayList;
import java.util.List;

/**
 * What the dashboard POSTs to <code>/api/patrols</code>.
 *
 * <p>Everything except the drone is optional: omit the circuit and the backend uses a four-stop
 * loop around the depot, which is what makes the "one click and watch it come back every 30
 * seconds" demo work.</p>
 *
 * @param droneId         airframe to patrol with
 * @param intervalSeconds gap between laps (the backend floors this at 5s)
 * @param cycles          number of laps; a patrol is finite on purpose, so it can complete
 * @param stops           optional custom circuit
 * @param payloadKg       parcel mass per drop
 */
public record StartPatrolRequest(
        @NotBlank String droneId,
        @Min(5) @Max(86_400) Integer intervalSeconds,
        @Min(1) @Max(100) Integer cycles,
        @Valid List<StartRouteRequest.StopSpec> stops,
        @PositiveOrZero Double payloadKg) {

    public PatrolCommand toCommand(
            String patrolId, String originLabel, double originLat, double originLng) {
        List<Waypoint> circuit = new ArrayList<>();
        if (stops != null && !stops.isEmpty()) {
            for (StartRouteRequest.StopSpec stop : stops) {
                circuit.add(stop.toWaypoint());
            }
        } else {
            circuit.addAll(defaultCircuit(originLabel, originLat, originLng));
        }
        return new PatrolCommand(
                patrolId,
                droneId,
                originLabel,
                originLat,
                originLng,
                intervalSeconds == null ? 30 : intervalSeconds,
                cycles == null ? 3 : cycles,
                circuit,
                payloadKg == null || payloadKg <= 0 ? 0.8 : payloadKg);
    }

    /**
     * The default circuit: four depots around the hub, derived from the configured home base so
     * the patrol looks right wherever the project is run.
     */
    public static List<Waypoint> defaultCircuit(String originLabel, double originLat,
                                                double originLng) {
        return List.of(
                new Waypoint(originLabel + " — North Loop", originLat + 0.018, originLng + 0.004, 110),
                new Waypoint(originLabel + " — East Dock", originLat + 0.004, originLng + 0.024, 110),
                new Waypoint(originLabel + " — South Yard", originLat - 0.016, originLng + 0.002, 110),
                new Waypoint(originLabel + " — West Gate", originLat + 0.002, originLng - 0.022, 110));
    }
}
