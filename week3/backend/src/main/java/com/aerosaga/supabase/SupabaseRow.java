package com.aerosaga.supabase;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw row shape of the {@code drones} table.
 *
 * <p>Postgres columns are snake_case; Java components are camelCase, so each one needs an explicit
 * {@code @JsonProperty}. Keeping this separate from {@code com.aerosaga.domain.Drone} means the
 * database schema can change without touching anything the workflow or the dashboard reads.</p>
 *
 * @param id          DRN-01
 * @param callsign    display name
 * @param lat         parked latitude
 * @param lng         parked longitude
 * @param altitudeM   parked altitude
 * @param batteryPct  state of charge
 * @param status      idle | charging | maintenance | on-mission
 * @param homeBase    base name
 */
public record SupabaseRow(
        String id,
        String callsign,
        Double lat,
        Double lng,
        @JsonProperty("altitude_m") Double altitudeM,
        @JsonProperty("battery_pct") Double batteryPct,
        String status,
        @JsonProperty("home_base") String homeBase) {
}
