package com.aerosaga.analytics;

import com.aerosaga.domain.MissionPhase;
import com.aerosaga.domain.MissionSnapshot;
import com.aerosaga.mission.MissionService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the ledger's event log into the numbers the ops panel shows.
 *
 * <p>All aggregation happens here rather than in SQL, which is a deliberate Week 3 trade-off: the
 * dashboard has to work with no database configured, and a few thousand in-memory events are
 * cheaper to fold in Java than to round-trip.</p>
 */
@Service
public class AnalyticsService {

    private static final long BUCKET_MILLIS = 5 * 60 * 1000L;
    private static final int BUCKET_COUNT = 12;
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final MissionLedger ledger;
    private final MissionService missions;

    public AnalyticsService(MissionLedger ledger, MissionService missions) {
        this.ledger = ledger;
        this.missions = missions;
    }

    public AnalyticsSummary summary() {
        List<MissionLedger.Event> latest = ledger.latestPerMission();
        Map<String, Long> firstSeen = ledger.firstSeen();

        int delivered = 0;
        int compensated = 0;
        int failed = 0;
        int aborted = 0;
        long totalFlightMillis = 0;
        int timedMissions = 0;

        Map<String, int[]> perDrone = new HashMap<>();   // [missions, delivered, compensated]
        Map<String, MissionPhase> lastPhase = new HashMap<>();

        for (MissionLedger.Event event : latest) {
            MissionPhase phase = event.phase();
            lastPhase.put(event.droneId(), phase);
            int[] counts = perDrone.computeIfAbsent(event.droneId(), id -> new int[3]);
            counts[0]++;

            switch (phase) {
                case DELIVERED -> {
                    delivered++;
                    counts[1]++;
                }
                case COMPENSATED -> {
                    compensated++;
                    counts[2]++;
                }
                case FAILED -> failed++;
                case ABORTED -> aborted++;
                default -> {
                    // still flying: counted in missionsInFlight below
                }
            }

            if (isTerminal(phase)) {
                Long start = firstSeen.get(event.missionId());
                if (start != null && event.at() >= start) {
                    totalFlightMillis += event.at() - start;
                    timedMissions++;
                }
            }
        }

        int inFlight;
        try {
            inFlight = missions.list().size();
        } catch (RuntimeException e) {
            // Temporal unreachable: report what the ledger knows instead of failing the endpoint.
            inFlight = 0;
        }

        int terminal = delivered + compensated + failed + aborted;
        double successRate = terminal == 0 ? 0.0 : (double) delivered / terminal;
        double averageFlightSeconds = timedMissions == 0
                ? 0.0
                : (totalFlightMillis / 1000.0) / timedMissions;

        List<AnalyticsSummary.DroneStat> droneStats = new ArrayList<>();
        perDrone.forEach((droneId, counts) -> droneStats.add(new AnalyticsSummary.DroneStat(
                droneId,
                counts[0],
                counts[1],
                counts[2],
                String.valueOf(lastPhase.getOrDefault(droneId, MissionPhase.QUEUED)))));
        droneStats.sort(Comparator.comparingInt(AnalyticsSummary.DroneStat::missions).reversed());

        return new AnalyticsSummary(
                System.currentTimeMillis(),
                ledger.missionCount(),
                inFlight,
                delivered,
                compensated,
                failed,
                aborted,
                round(successRate * 100) / 100.0,
                round(averageFlightSeconds),
                ledger.totalRecorded(),
                suspensionEvents(),
                droneStats,
                throughput());
    }

    /** The mission timeline, for the "what exactly happened" question after a drill. */
    public List<MissionLedger.Event> timeline(String missionId) {
        return ledger.timeline(missionId);
    }

    /** The newest transitions across the whole fleet. */
    public List<MissionLedger.Event> events(int limit) {
        return ledger.recent(limit);
    }

    // ------------------------------------------------------------------ internals

    private int suspensionEvents() {
        return (int) ledger.recent(5_000).stream()
                .filter(event -> event.phase() == MissionPhase.OFFLINE_SUSPENDED)
                .count();
    }

    private List<AnalyticsSummary.ThroughputSlot> throughput() {
        long now = System.currentTimeMillis();
        long windowStart = now - BUCKET_COUNT * BUCKET_MILLIS;

        Map<Integer, int[]> buckets = new LinkedHashMap<>();   // [delivered, compensated]
        for (int i = 0; i < BUCKET_COUNT; i++) {
            buckets.put(i, new int[2]);
        }

        for (MissionLedger.Event event : ledger.recent(5_000)) {
            if (event.at() < windowStart) {
                continue;
            }
            if (event.phase() != MissionPhase.DELIVERED
                    && event.phase() != MissionPhase.COMPENSATED) {
                continue;
            }
            int index = (int) ((event.at() - windowStart) / BUCKET_MILLIS);
            index = Math.max(0, Math.min(BUCKET_COUNT - 1, index));
            int[] slot = buckets.get(index);
            if (event.phase() == MissionPhase.DELIVERED) {
                slot[0]++;
            } else {
                slot[1]++;
            }
        }

        List<AnalyticsSummary.ThroughputSlot> slots = new ArrayList<>();
        for (int i = 0; i < BUCKET_COUNT; i++) {
            int[] slot = buckets.get(i);
            long at = windowStart + (long) i * BUCKET_MILLIS;
            slots.add(new AnalyticsSummary.ThroughputSlot(
                    CLOCK.format(Instant.ofEpochMilli(at)), slot[0], slot[1]));
        }
        return slots;
    }

    private static boolean isTerminal(MissionPhase phase) {
        return phase == MissionPhase.DELIVERED
                || phase == MissionPhase.COMPENSATED
                || phase == MissionPhase.FAILED
                || phase == MissionPhase.ABORTED;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
