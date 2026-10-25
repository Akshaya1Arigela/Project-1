package com.aerosaga.api;

import com.aerosaga.domain.Drone;
import com.aerosaga.drone.DroneRegistry;
import com.aerosaga.supabase.SupabaseClient;
import com.aerosaga.telemetry.TelemetryEngine;
import com.aerosaga.telemetry.TelemetryWebSocketHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Registry endpoints. Telemetry itself flows over the WebSocket, not over REST. */
@RestController
@RequestMapping("/api")
public class DroneController {

    private final DroneRegistry registry;
    private final TelemetryEngine telemetry;
    private final TelemetryWebSocketHandler handler;
    private final SupabaseClient supabase;

    public DroneController(
            DroneRegistry registry,
            TelemetryEngine telemetry,
            TelemetryWebSocketHandler handler,
            SupabaseClient supabase) {
        this.registry = registry;
        this.telemetry = telemetry;
        this.handler = handler;
        this.supabase = supabase;
    }

    /** The durable registry: 50 airframes, sourced from Supabase or synthesised locally. */
    @GetMapping("/drones")
    public List<Drone> drones() {
        return registry.findAll();
    }

    @GetMapping("/drones/{droneId}")
    public Drone drone(@PathVariable String droneId) {
        return registry.findById(droneId);
    }

    /** One frame of live telemetry, for clients that cannot or will not hold a WebSocket. */
    @GetMapping("/telemetry")
    public Map<String, Object> telemetry() {
        return Map.of(
                "type", "fleet.telemetry",
                "storm", telemetry.isStormActive(),
                "clients", handler.clientCount(),
                "homeBase", Map.of(
                        "label", registry.homeBaseLabel(),
                        "lat", registry.homeLat(),
                        "lng", registry.homeLng()),
                "drones", telemetry.snapshot());
    }
}
