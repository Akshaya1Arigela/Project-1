package com.aerosaga.drone;

import com.aerosaga.domain.Drone;
import com.aerosaga.supabase.SupabaseClient;
import com.aerosaga.supabase.SupabaseRow;
import com.aerosaga.telemetry.TelemetryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fleet registry.
 *
 * <p>Supabase is the source of truth; if it is not configured we synthesise
 * {@code aerosaga.telemetry.demo-fleet-size} drones around the hub so the dashboard still has 50
 * markers to render. Either way the shape is identical, so nothing downstream cares which one it
 * got.</p>
 *
 * <p>Live position is <b>not</b> stored here — it lives in {@link TelemetryEngine}. Keeping the
 * registry (durable, slowly-changing) separate from the telemetry (volatile, 1 Hz) is what stops
 * every workflow activity from writing a database row per animation tick.</p>
 */
@Component
public class DroneRegistry {

    private static final Logger log = LoggerFactory.getLogger(DroneRegistry.class);

    private final SupabaseClient supabase;
    private final TelemetryEngine telemetry;
    private final String homeBaseLabel;
    private final double homeLat;
    private final double homeLng;
    private final int demoFleetSize;

    public DroneRegistry(
            SupabaseClient supabase,
            TelemetryEngine telemetry,
            @Value("${aerosaga.home-base.label}") String homeBaseLabel,
            @Value("${aerosaga.home-base.lat}") double homeLat,
            @Value("${aerosaga.home-base.lng}") double homeLng,
            @Value("${aerosaga.telemetry.demo-fleet-size}") int demoFleetSize) {
        this.supabase = supabase;
        this.telemetry = telemetry;
        this.homeBaseLabel = homeBaseLabel;
        this.homeLat = homeLat;
        this.homeLng = homeLng;
        this.demoFleetSize = demoFleetSize;
    }

    public List<Drone> findAll() {
        List<SupabaseRow> rows = supabase.select("drones", "select=*&order=id.asc", SupabaseRow.class);
        if (rows != null && !rows.isEmpty()) {
            List<Drone> drones = new ArrayList<>(rows.size());
            for (SupabaseRow row : rows) {
                drones.add(toDrone(row));
            }
            return drones;
        }
        return demoFleet();
    }

    public Drone findById(String droneId) {
        return findAll().stream()
                .filter(d -> d.id().equals(droneId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown drone: " + droneId));
    }

    public String homeBaseLabel() {
        return homeBaseLabel;
    }

    public double homeLat() {
        return homeLat;
    }

    public double homeLng() {
        return homeLng;
    }

    // ------------------------------------------------------------------ fallback

    private static Drone toDrone(SupabaseRow row) {
        return new Drone(
                row.id(),
                row.callsign() == null ? row.id() : row.callsign(),
                row.lat() == null ? 0 : row.lat(),
                row.lng() == null ? 0 : row.lng(),
                row.altitudeM() == null ? 0 : row.altitudeM(),
                row.batteryPct() == null ? 100 : row.batteryPct(),
                row.status() == null ? "idle" : row.status(),
                row.homeBase() == null ? "Unassigned" : row.homeBase());
    }

    /**
     * 50 drones scattered deterministically around the hub. Deterministic on purpose: reloading
     * the page should not teleport the whole fleet, or the demo looks like a bug.
     */
    private List<Drone> demoFleet() {
        List<Drone> fleet = new ArrayList<>(demoFleetSize);
        for (int n = 1; n <= demoFleetSize; n++) {
            double angle = (n * 137.508) % 360;              // golden-angle spread
            double radius = 0.004 + (n % 10) * 0.0045;        // degrees
            double lat = homeLat + radius * Math.cos(Math.toRadians(angle));
            double lng = homeLng + radius * Math.sin(Math.toRadians(angle)) / Math.cos(Math.toRadians(homeLat));

            String id = "DRN-%02d".formatted(n);
            String prefix = n <= 25 ? "Garuda-" : "Vayu-";
            String status = n % 11 == 0 ? "maintenance" : (n % 7 == 0 ? "charging" : "idle");

            Drone drone = new Drone(
                    id,
                    prefix + "%02d".formatted(n),
                    Math.round(lat * 1e6) / 1e6,
                    Math.round(lng * 1e6) / 1e6,
                    0,
                    55 + (n * 7) % 45,
                    status,
                    homeBaseLabel);
            fleet.add(drone);
            telemetry.register(drone);
        }
        log.debug("Serving in-memory demo fleet ({} drones)", fleet.size());
        return fleet;
    }
}
