package com.aerosaga.activity;

import com.aerosaga.domain.RouteCommand;
import com.aerosaga.domain.Waypoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route planner is a pure function, so it gets a plain unit test — no Temporal, no Spring.
 *
 * <p>It is worth testing separately: the workflow test fakes {@code planRoute}, so without this
 * class nothing would ever exercise the actual ordering logic.</p>
 */
class RoutePlannerTest {

    private final DroneActivitiesImpl planner = new DroneActivitiesImpl(null, null, null);

    @Test
    void visitsTheNearestStopFirst() {
        // Deliberately supplied far-to-near. The planner should reverse that.
        RouteCommand command = new RouteCommand(
                "r1", "DRN-01", "Hub", 17.3850, 78.4867,
                List.of(
                        new Waypoint("Far North", 17.5000, 78.4867, 110),
                        new Waypoint("Near", 17.4000, 78.4867, 110),
                        new Waypoint("Middle", 17.4500, 78.4867, 110)),
                1.0, 25.0);

        List<Waypoint> ordered = planner.planRoute(command).ordered();

        assertEquals(List.of("Near", "Middle", "Far North"),
                ordered.stream().map(Waypoint::label).toList());
    }

    @Test
    void totalDistanceCoversTheWholeRoundTrip() {
        RouteCommand command = new RouteCommand(
                "r2", "DRN-01", "Hub", 17.3850, 78.4867,
                List.of(new Waypoint("Depot", 17.4000, 78.4867, 110)),
                1.0, 25.0);

        double km = planner.planRoute(command).totalDistanceKm();

        // ~1.7 km out and ~1.7 km back. Allow a generous band for the earth-radius constant.
        assertTrue(km > 2.5 && km < 4.5, "expected a return trip of roughly 3.4 km, got " + km);
    }

    @Test
    void haversineAgreesWithAKnownCityDistance() {
        // Hyderabad hub → Secunderabad, about 7 km as the drone flies.
        double km = DroneActivitiesImpl.haversineKm(17.3850, 78.4867, 17.4375, 78.4483);

        assertTrue(km > 6.0 && km < 8.0, "expected roughly 7 km, got " + km);
    }
}
