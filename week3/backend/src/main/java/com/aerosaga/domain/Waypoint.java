package com.aerosaga.domain;

/**
 * A single point on the flight plan.
 *
 * @param label human-readable name shown on the dashboard
 * @param lat   degrees
 * @param lng   degrees
 * @param altitudeM metres above the ellipsoid
 */
public record Waypoint(String label, double lat, double lng, double altitudeM) {

    public Waypoint {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("waypoint label is required");
        }
    }
}
