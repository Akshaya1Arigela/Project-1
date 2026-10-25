package com.aerosaga.telemetry;

import com.aerosaga.domain.MissionPhase;
import com.aerosaga.drone.DroneRegistry;
import com.aerosaga.mission.MissionIndex;
import com.aerosaga.mission.MissionRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The heartbeat of the dashboard.
 *
 * <p>Once a second this class does three things:</p>
 * <ol>
 *   <li><b>Sync</b> the registry into {@link TelemetryEngine}, so a drone that just appeared in
 *       Supabase shows up on the globe.</li>
 *   <li><b>Drift</b> any drone that is <em>not</em> on a mission. Real idle drones sit on pads,
 *       but a 50-marker map where 44 markers never move looks broken, so we wander them gently.
 *       Drones on a mission are untouched — their position comes from the Temporal activity.</li>
 *   <li><b>Broadcast</b> one JSON frame to every connected browser, and <b>persist</b> any phase
 *       change to Supabase for the audit trail.</li>
 * </ol>
 *
 * <h2>Why the simulator is separate from the activities</h2>
 * <p>Because the two run at different rates and with different guarantees. Activities run once
 * per workflow step and must be idempotent. The simulator runs 60-ish times a minute and its
 * output is disposable — the next frame supersedes the last. Keeping them apart means a lost
 * frame costs nothing, while a lost activity step is impossible.</p>
 */
@Component
public class DroneTelemetrySimulator {

    private static final Logger log = LoggerFactory.getLogger(DroneTelemetrySimulator.class);

    private final DroneRegistry registry;
    private final TelemetryEngine engine;
    private final TelemetryWebSocketHandler handler;
    private final MissionIndex missionIndex;
    private final MissionRecorder recorder;
    private final ObjectMapper objectMapper;

    private volatile boolean running;

    public DroneTelemetrySimulator(
            DroneRegistry registry,
            TelemetryEngine engine,
            TelemetryWebSocketHandler handler,
            MissionIndex missionIndex,
            MissionRecorder recorder,
            ObjectMapper objectMapper) {
        this.registry = registry;
        this.engine = engine;
        this.handler = handler;
        this.missionIndex = missionIndex;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void warmUp() {
        registry.findAll().forEach(engine::register);
        log.info("Telemetry engine primed with {} drones", engine.size());
    }

    @Scheduled(fixedRateString = "${aerosaga.telemetry.tick-ms:1000}",
            initialDelayString = "${aerosaga.telemetry.tick-ms:1000}")
    public void tick() {
        try {
            running = true;

            // 1. New drones (added in Supabase since the last tick) join the map.
            registry.findAll().forEach(engine::register);

            // 2. Idle drones drift. On-mission drones are driven by their activities.
            //    Phase changes are NOT written from here: the workflow reports its own
            //    transitions through the reportPhase activity, and two writers would produce
            //    duplicate rows in the Supabase audit trail.
            for (TelemetryEngine.Telemetry telemetry : engine.snapshot()) {
                if (telemetry.missionId() == null) {
                    engine.driftIdleDrone(telemetry.id());
                }
            }

            // 3. Fan the frame out to every dashboard.
            if (handler.clientCount() > 0) {
                handler.broadcast(toJson());
            }
        } catch (RuntimeException e) {
            // A telemetry tick must never kill the scheduler thread.
            log.error("Telemetry tick failed", e);
        } finally {
            running = false;
        }
    }

    private String toJson() {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "fleet.telemetry");
        frame.put("serverTime", System.currentTimeMillis());
        frame.put("storm", engine.isStormActive());
        frame.put("homeBase", Map.of(
                "label", engine.homeBaseLabel(),
                "lat", engine.homeLat(),
                "lng", engine.homeLng()));
        frame.put("drones", engine.snapshot());
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.error("Could not serialise telemetry frame", e);
            return null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
