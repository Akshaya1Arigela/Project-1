package com.aerosaga.domain;

import java.util.List;

/**
 * The command a workflow runs from.
 *
 * <p>This object is serialized into Temporal's history, so it must be immutable and serializable.
 * Records are supported by the SDK's default Jackson payload codec. Do not add anything mutable
 * or non-serialisable here — it would be baked into the event history forever.</p>
 *
 * @param missionId            caller-supplied, also used as the Temporal workflow id
 * @param droneId              which airframe to fly, e.g. {@code DRN-01}
 * @param originLabel          home base name
 * @param originLat            home base latitude
 * @param originLng            home base longitude
 * @param route                ordered waypoints between takeoff and the delivery point
 * @param destinationLabel     delivery address name
 * @param payloadKg            parcel mass
 * @param lowBatteryThresholdPct below this, the workflow compensates and flies home
 * @param offlineTimeoutSeconds hard cap on how long the workflow may sit OFFLINE_SUSPENDED
 */
public record MissionCommand(
        String missionId,
        String droneId,
        String originLabel,
        double originLat,
        double originLng,
        List<Waypoint> route,
        String destinationLabel,
        double destLat,
        double destLng,
        double payloadKg,
        double lowBatteryThresholdPct,
        int offlineTimeoutSeconds) {

    public MissionCommand {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("missionId is required");
        }
        if (droneId == null || droneId.isBlank()) {
            throw new IllegalArgumentException("droneId is required");
        }
        route = route == null ? List.of() : List.copyOf(route);
        if (lowBatteryThresholdPct <= 0) {
            lowBatteryThresholdPct = 25.0;
        }
        if (offlineTimeoutSeconds <= 0) {
            offlineTimeoutSeconds = 7200; // 2 hours, per the problem statement
        }
        if (payloadKg <= 0) {
            payloadKg = 1.0;
        }
    }

    /** Convenience factory used by the REST layer. */
    public static MissionCommand of(
            String missionId,
            String droneId,
            String originLabel,
            double originLat,
            double originLng,
            String destinationLabel,
            double destLat,
            double destLng,
            double payloadKg) {
        return new MissionCommand(
                missionId,
                droneId,
                originLabel,
                originLat,
                originLng,
                List.of(),
                destinationLabel,
                destLat,
                destLng,
                payloadKg,
                25.0,
                7200);
    }
}
