package com.aerosaga.drone;

import com.aerosaga.domain.Drone;
import com.aerosaga.supabase.SupabaseClient;
import com.aerosaga.supabase.SupabaseRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The drone registry — Supabase is the source of truth, with an in-memory fallback so the demo
 * is never blocked on cloud setup.
 *
 * <p>The dashboard's 3D markers come straight from {@link #findAll()}. In Week 1 they are parked
 * and static; in Week 2 this class also carries the <em>live</em> position that the telemetry
 * WebSocket stream is built from.</p>
 */
@Component
public class DroneRegistry {

    private static final Logger log = LoggerFactory.getLogger(DroneRegistry.class);

    private final SupabaseClient supabase;
    private final String homeBaseLabel;
    private final double homeLat;
    private final double homeLng;

    /** Last position reported by a running workflow, keyed by drone id. */
    private final ConcurrentHashMap<String, Drone> livePositions = new ConcurrentHashMap<>();

    public DroneRegistry(
            SupabaseClient supabase,
            @Value("${aerosaga.home-base.label}") String homeBaseLabel,
            @Value("${aerosaga.home-base.lat}") double homeLat,
            @Value("${aerosaga.home-base.lng}") double homeLng) {
        this.supabase = supabase;
        this.homeBaseLabel = homeBaseLabel;
        this.homeLat = homeLat;
        this.homeLng = homeLng;
    }

    /** The full fleet. Live positions override whatever the database says. */
    public List<Drone> findAll() {
        Map<String, Drone> merged = new ConcurrentHashMap<>();

        List<SupabaseRow> rows = supabase.select("drones", "select=*&order=id.asc", SupabaseRow.class);
        if (rows != null && !rows.isEmpty()) {
            for (SupabaseRow row : rows) {
                merged.put(row.id(), toDrone(row));
            }
        } else {
            merged.putAll(demoFleet());
        }

        livePositions.forEach((id, drone) -> {
            Drone db = merged.get(id);
            if (db != null) {
                merged.put(id, new Drone(
                        drone.id(), drone.callsign(), drone.lat(), drone.lng(), drone.altitudeM(),
                        drone.batteryPct(), drone.status(), db.homeBase()));
            } else {
                merged.put(id, drone);
            }
        });

        return new ArrayList<>(merged.values());
    }

    public Optional<Drone> findById(String droneId) {
        return findAll().stream().filter(d -> d.id().equals(droneId)).findFirst();
    }

    /**
     * Called by an activity when it has a fresh position. Week 2's telemetry gateway reads this
     * map every tick and pushes it over the WebSocket.
     */
    public void reportPosition(String droneId, String callsign, double lat, double lng,
                               double altitudeM, double batteryPct, String status) {
        livePositions.put(droneId, new Drone(
                droneId, callsign, lat, lng, altitudeM, Math.round(batteryPct * 10) / 10.0, status, homeBaseLabel));
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

    /** Six airframes parked around Hyderabad, so the globe has something to show on day one. */
    private Map<String, Drone> demoFleet() {
        Map<String, Drone> fleet = new ConcurrentHashMap<>();
        fleet.put("DRN-01", new Drone("DRN-01", "Garuda-01", 17.3850, 78.4867, 0, 100, "idle", homeBaseLabel));
        fleet.put("DRN-02", new Drone("DRN-02", "Garuda-02", 17.3921, 78.4720, 0, 92, "idle", homeBaseLabel));
        fleet.put("DRN-03", new Drone("DRN-03", "Garuda-03", 17.3744, 78.5031, 0, 78, "charging", homeBaseLabel));
        fleet.put("DRN-04", new Drone("DRN-04", "Vayu-04", 17.4126, 78.4482, 0, 100, "idle", homeBaseLabel));
        fleet.put("DRN-05", new Drone("DRN-05", "Vayu-05", 17.4375, 78.4483, 0, 64, "maintenance", homeBaseLabel));
        fleet.put("DRN-06", new Drone("DRN-06", "Vayu-06", 17.3616, 78.5261, 0, 100, "idle", homeBaseLabel));
        log.debug("Serving in-memory demo fleet ({} drones)", fleet.size());
        return fleet;
    }
}
