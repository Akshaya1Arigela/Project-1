package com.aerosaga.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The Mid-Project Review's visual check, as an endpoint: "ensure the 3D globe performs smoothly
 * while rendering 50+ moving markers".
 *
 * @param count     how many missions to dispatch (capped at 100)
 * @param payloadKg parcel mass per mission
 * @param spreadKm  how far from the hub the destinations are scattered
 */
public record StartLoadTestRequest(
        @Min(1) @Max(100) Integer count,
        @PositiveOrZero Double payloadKg,
        @PositiveOrZero Double spreadKm) {
}
