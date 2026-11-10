package com.aerosaga.domain;

/**
 * One airframe from the drone registry.
 *
 * @param id          e.g. {@code DRN-01}
 * @param callsign    display name shown next to the 3D marker
 * @param lat         parked latitude
 * @param lng         parked longitude
 * @param altitudeM   parked altitude
 * @param batteryPct  current state of charge
 * @param status      {@code idle}, {@code charging}, {@code maintenance}, {@code on-mission}
 * @param homeBase    name of the base this drone is assigned to
 */
public record Drone(
        String id,
        String callsign,
        double lat,
        double lng,
        double altitudeM,
        double batteryPct,
        String status,
        String homeBase) {
}
