package com.aerosaga.domain;

import java.util.List;

/**
 * Live state of a multi-stop route, returned by the workflow's {@code @QueryMethod}.
 *
 * <p>This is the same trick as Week 2's {@link MissionSnapshot}: a query reads the workflow object
 * that a worker is holding in memory, not a row in a database. The dashboard can poll it once a
 * second without touching Supabase.</p>
 */
public record RouteProgress(
        String missionId,
        String droneId,
        MissionPhase phase,
        int stopsPlanned,
        int stopsDelivered,
        List<Waypoint> plannedRoute,
        double totalDistanceKm,
        String currentStopLabel,
        double batteryPct,
        String detail,
        int step,
        long updatedAt) {

    public static RouteProgress initial(String missionId, String droneId) {
        return new RouteProgress(
                missionId, droneId, MissionPhase.QUEUED, 0, 0, List.of(), 0.0,
                "—", 0.0, "Route accepted", 0, 0L);
    }

    public RouteProgress withPlannedRoute(List<Waypoint> ordered, double distanceKm, int step) {
        return new RouteProgress(
                missionId, droneId, MissionPhase.NAVIGATING, ordered.size(), stopsDelivered,
                List.copyOf(ordered), distanceKm,
                ordered.isEmpty() ? "—" : ordered.get(0).label(),
                batteryPct, "Route optimised: " + ordered.size() + " stops, "
                        + String.format("%.1f", distanceKm) + " km planned", step, updatedAt);
    }

    public RouteProgress atStop(Waypoint stop, int index, MissionPhase phase,
                                double battery, String detail, long at) {
        return new RouteProgress(
                missionId, droneId, phase, stopsPlanned, stopsDelivered, plannedRoute,
                totalDistanceKm, stop.label(), battery, detail, index, at);
    }

    public RouteProgress stopDelivered(Waypoint stop, int delivered, double battery, long at) {
        return new RouteProgress(
                missionId, droneId, MissionPhase.NAVIGATING, stopsPlanned, delivered, plannedRoute,
                totalDistanceKm, stop.label(), battery,
                "Dropped parcel " + delivered + " of " + stopsPlanned + " at " + stop.label(),
                delivered, at);
    }

    public RouteProgress terminal(MissionPhase phase, double battery, String detail, long at) {
        return new RouteProgress(
                missionId, droneId, phase, stopsPlanned, stopsDelivered, plannedRoute,
                totalDistanceKm, currentStopLabel, battery, detail, step, at);
    }
}
