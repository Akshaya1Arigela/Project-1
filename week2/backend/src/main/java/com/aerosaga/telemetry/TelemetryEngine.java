package com.aerosaga.telemetry;

import com.aerosaga.domain.Drone;
import com.aerosaga.domain.MissionPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The live state of every airframe — the single source of truth the telemetry gateway streams
 * from.
 *
 * <p>Two writers, one reader:</p>
 * <ul>
 *   <li><b>Activities</b> write positions and battery for drones that are on a mission. This is
 *       real: the Temporal workflow is what is moving the drone.</li>
 *   <li><b>The simulator</b> drifts idle drones around so the globe has 50 moving markers, and
 *       applies the storm.</li>
 *   <li><b>{@link DroneTelemetrySimulator}</b> reads the whole map once a second and broadcasts
 *       it over WebSocket.</li>
 * </ul>
 *
 * <p>The storm lives here rather than in the workflow for a deliberate reason: the workflow must
 * stay deterministic, and "am I in a storm?" is exactly the kind of ambient world state a
 * workflow is not allowed to read. The workflow asks its <em>activity</em>, which asks this
 * class. Determinism preserved.</p>
 */
@Component
public class TelemetryEngine {

    private static final Logger log = LoggerFactory.getLogger(TelemetryEngine.class);

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Set<String> manualOffline = ConcurrentHashMap.newKeySet();

    private final String homeBaseLabel;
    private final double homeLat;
    private final double homeLng;

    private final double stormLatMin;
    private final double stormLatMax;
    private final double stormLngMin;
    private final double stormLngMax;

    private volatile boolean stormActive;

    public TelemetryEngine(
            @Value("${aerosaga.home-base.label}") String homeBaseLabel,
            @Value("${aerosaga.home-base.lat}") double homeLat,
            @Value("${aerosaga.home-base.lng}") double homeLng,
            @Value("${aerosaga.telemetry.storm.lat-min}") double stormLatMin,
            @Value("${aerosaga.telemetry.storm.lat-max}") double stormLatMax,
            @Value("${aerosaga.telemetry.storm.lng-min}") double stormLngMin,
            @Value("${aerosaga.telemetry.storm.lng-max}") double stormLngMax) {
        this.homeBaseLabel = homeBaseLabel;
        this.homeLat = homeLat;
        this.homeLng = homeLng;
        this.stormLatMin = stormLatMin;
        this.stormLatMax = stormLatMax;
        this.stormLngMin = stormLngMin;
        this.stormLngMax = stormLngMax;
    }

    // ------------------------------------------------------------------ registration

    public void register(Drone drone) {
        states.computeIfAbsent(drone.id(), id -> {
            State state = new State();
            state.id = drone.id();
            state.callsign = drone.callsign();
            state.lat = drone.lat();
            state.lng = drone.lng();
            state.altitudeM = drone.altitudeM();
            state.batteryPct = drone.batteryPct();
            state.status = drone.status();
            state.phase = MissionPhase.QUEUED;
            return state;
        });
        State existing = states.get(drone.id());
        existing.callsign = drone.callsign();
    }

    // ------------------------------------------------------------------ activity writes

    /** Called when a mission claims this airframe. */
    public void claimForMission(String droneId, String missionId, MissionPhase phase) {
        State state = states.computeIfAbsent(droneId, id -> {
            State s = new State();
            s.id = droneId;
            s.callsign = droneId;
            return s;
        });
        state.missionId = missionId;
        state.phase = phase;
        state.status = "on-mission";
        state.linkUp = true;
        state.lastPhaseWritten = phase;
    }

    public void updatePosition(String droneId, double lat, double lng, double altitudeM, double batteryPct) {
        State state = states.get(droneId);
        if (state == null) {
            return;
        }
        state.lat = lat;
        state.lng = lng;
        state.altitudeM = altitudeM;
        state.batteryPct = Math.max(0, batteryPct);
        state.updatedAt = System.currentTimeMillis();
    }

    public void setPhase(String droneId, MissionPhase phase) {
        State state = states.get(droneId);
        if (state != null) {
            state.phase = phase;
            state.updatedAt = System.currentTimeMillis();
        }
    }

    /** The mission finished; park the drone and let the simulator drift it again. */
    public void release(String droneId, String finalStatus) {
        State state = states.get(droneId);
        if (state == null) {
            return;
        }
        state.missionId = null;
        state.phase = MissionPhase.QUEUED;
        state.status = finalStatus;
        state.linkUp = true;
    }

    // ------------------------------------------------------------------ the storm

    /**
     * Is this airframe's radio working right now?
     *
     * <p>A drone is considered in the storm when it is physically inside the corridor. Because
     * the corridor is centred on the hub, a mission that is dispatched while the storm is on will
     * lose its link a few seconds after takeoff — which is exactly the sequence in the problem
     * statement.</p>
     */
    public boolean isLinkUp(String droneId) {
        State state = states.get(droneId);
        if (state == null) {
            return true;
        }
        if (manualOffline.contains(droneId)) {
            return false;
        }
        if (!stormActive) {
            return true;
        }
        return !inStormZone(state.lat, state.lng);
    }

    public void setStormActive(boolean active) {
        this.stormActive = active;
        log.warn("Storm is now {}", active ? "ACTIVE — drones in the corridor will lose their link" : "cleared");
    }

    public boolean isStormActive() {
        return stormActive;
    }

    public void setManualOffline(String droneId, boolean offline) {
        if (offline) {
            manualOffline.add(droneId);
        } else {
            manualOffline.remove(droneId);
        }
    }

    /** Extra battery burn while a drone is stuck in the storm, so compensation gets a chance. */
    public double weatherDrainFactor(String droneId) {
        State state = states.get(droneId);
        if (state == null) {
            return 1.0;
        }
        if (manualOffline.contains(droneId) || (stormActive && inStormZone(state.lat, state.lng))) {
            return 6.0;
        }
        return 1.0;
    }

    public boolean inStormZone(double lat, double lng) {
        return lat >= stormLatMin && lat <= stormLatMax && lng >= stormLngMin && lng <= stormLngMax;
    }

    /** Every mission currently sitting OFFLINE_SUSPENDED — used by the reconnect endpoint. */
    public List<String> suspendedDroneIds() {
        List<String> result = new ArrayList<>();
        states.forEach((id, state) -> {
            if (state.phase == MissionPhase.OFFLINE_SUSPENDED) {
                result.add(id);
            }
        });
        return result;
    }

    public Map<String, String> missionIdByDrone() {
        Map<String, String> result = new ConcurrentHashMap<>();
        states.forEach((id, state) -> {
            if (state.missionId != null) {
                result.put(id, state.missionId);
            }
        });
        return result;
    }

    public String phaseOf(String droneId) {
        State state = states.get(droneId);
        return state == null ? null : state.phase.name();
    }

    /** Phase change since the last tick — the caller uses this to append a Supabase audit row. */
    public MissionPhase pollPhaseChange(String droneId) {
        State state = states.get(droneId);
        if (state == null || state.phase == state.lastPhaseWritten) {
            return null;
        }
        state.lastPhaseWritten = state.phase;
        return state.phase;
    }

    // ------------------------------------------------------------------ reads

    public List<Telemetry> snapshot() {
        List<Telemetry> result = new ArrayList<>(states.size());
        for (State state : states.values()) {
            result.add(new Telemetry(
                    state.id,
                    state.callsign,
                    state.lat,
                    state.lng,
                    state.altitudeM,
                    state.batteryPct,
                    state.status,
                    state.phase,
                    state.missionId,
                    state.linkUp && !manualOffline.contains(state.id),
                    state.updatedAt));
        }
        return result;
    }

    public int size() {
        return states.size();
    }

    /** The current frame for one drone, or {@code null} if it is not registered. */
    public Telemetry get(String droneId) {
        State state = states.get(droneId);
        if (state == null) {
            return null;
        }
        return new Telemetry(
                state.id, state.callsign, state.lat, state.lng, state.altitudeM,
                state.batteryPct, state.status, state.phase, state.missionId,
                state.linkUp && !manualOffline.contains(state.id), state.updatedAt);
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

    /** Slow random drift for drones that are not on a mission, so the globe is alive. */
    public void driftIdleDrone(String droneId) {
        State state = states.get(droneId);
        if (state == null || state.missionId != null) {
            return;
        }
        state.lat += (ThreadLocalRandom.current().nextGaussian()) * 0.00025;
        state.lng += (ThreadLocalRandom.current().nextGaussian()) * 0.00025;
        state.altitudeM = 25 + ThreadLocalRandom.current().nextInt(20);
        state.batteryPct = Math.max(20, state.batteryPct - 0.004);
        state.updatedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ state holder

    /** Deliberately mutable and volatile — written by many worker threads, read once a tick. */
    private static final class State {
        String id;
        String callsign;
        volatile double lat;
        volatile double lng;
        volatile double altitudeM;
        volatile double batteryPct = 100;
        volatile String status = "idle";
        volatile MissionPhase phase = MissionPhase.QUEUED;
        volatile MissionPhase lastPhaseWritten = MissionPhase.QUEUED;
        volatile String missionId;
        volatile boolean linkUp = true;
        volatile long updatedAt = System.currentTimeMillis();
    }

    /** Immutable frame sent to the browser. */
    public record Telemetry(
            String id,
            String callsign,
            double lat,
            double lng,
            double altitudeM,
            double batteryPct,
            String status,
            MissionPhase phase,
            String missionId,
            boolean linkUp,
            long updatedAt) {
    }
}
