package com.aerosaga.api;

import com.aerosaga.domain.SupervisorCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * What the dashboard POSTs to <code>/api/supervisor</code>.
 *
 * <p>Everything is optional: the defaults are the demo policy (scan every 10s, tolerate 20s of
 * silence, intervene automatically). The two knobs worth changing in a review are
 * {@code offlineGraceSeconds} — turn it down to 5 to make the storm drill instantaneous — and
 * {@code autoIntervene} — turn it off to show the supervisor observing without acting.</p>
 */
public record StartSupervisorRequest(
        @Min(2) @Max(600) Integer scanIntervalSeconds,
        @Min(1) @Max(3600) Integer offlineGraceSeconds,
        @DecimalMin("1") @DecimalMax("90") Double lowBatteryFloorPct,
        @Min(0) @Max(100_000) Integer maxTicksPerRun,
        Boolean autoIntervene) {

    public SupervisorCommand toCommand(String supervisorId) {
        return new SupervisorCommand(
                supervisorId,
                scanIntervalSeconds == null ? 10 : scanIntervalSeconds,
                offlineGraceSeconds == null ? 20 : offlineGraceSeconds,
                lowBatteryFloorPct == null ? 12.0 : lowBatteryFloorPct,
                maxTicksPerRun == null ? 200 : maxTicksPerRun,
                autoIntervene == null || autoIntervene,
                0, 0, 0);
    }
}
