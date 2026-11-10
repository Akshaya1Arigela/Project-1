package com.aerosaga.api;

import com.aerosaga.analytics.AnalyticsService;
import com.aerosaga.analytics.AnalyticsSummary;
import com.aerosaga.analytics.MissionLedger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Fleet analytics and the per-mission timeline.
 *
 * <p>Both endpoints read the in-memory ledger, so they answer even when Supabase is not
 * configured. The Supabase audit trail remains the durable record; this is the live view.</p>
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** Totals, success rate, per-drone roll-up and a five-minute throughput history. */
    @GetMapping("/summary")
    public AnalyticsSummary summary() {
        return analytics.summary();
    }

    /**
     * Every phase transition recorded for one mission, oldest first.
     *
     * <p>This is the "prove it" endpoint: after a storm or a restart drill, the timeline shows
     * {@code OFFLINE_SUSPENDED} at one timestamp and {@code NAVIGATING} at another, minutes apart,
     * with nothing in between — which is exactly what a durable suspension looks like.</p>
     */
    @GetMapping("/timeline/{missionId}")
    public List<MissionLedger.Event> timeline(@PathVariable String missionId) {
        return analytics.timeline(missionId);
    }

    /** The most recent transitions across the whole fleet, newest first. */
    @GetMapping("/events")
    public List<MissionLedger.Event> events() {
        return analytics.events(50);
    }
}
