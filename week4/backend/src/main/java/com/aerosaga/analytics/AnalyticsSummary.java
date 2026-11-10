package com.aerosaga.analytics;

import java.util.List;

/**
 * What {@code GET /api/analytics/summary} returns.
 *
 * <p>Deliberately flat and fully computed on the server: the dashboard should not have to know how
 * a mission is counted, and the same numbers have to be quotable in a review.</p>
 */
public record AnalyticsSummary(
        long generatedAt,
        int missionsRecorded,
        int missionsInFlight,
        int delivered,
        int compensated,
        int failed,
        int aborted,
        double successRate,
        double averageFlightSeconds,
        long totalEvents,
        int suspensionEvents,
        List<DroneStat> drones,
        List<ThroughputSlot> throughput) {

    /** Per-airframe roll-up. */
    public record DroneStat(
            String droneId,
            int missions,
            int delivered,
            int compensated,
            String lastPhase) {
    }

    /** Deliveries per five-minute bucket, oldest first — a tiny sparkline for the panel. */
    public record ThroughputSlot(String label, int delivered, int compensated) {
    }
}
