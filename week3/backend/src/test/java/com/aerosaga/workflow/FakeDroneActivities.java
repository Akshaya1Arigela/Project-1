package com.aerosaga.workflow;

import com.aerosaga.activity.DroneActivities;
import com.aerosaga.domain.MissionCommand;
import com.aerosaga.domain.RouteCommand;
import com.aerosaga.domain.Waypoint;

import java.util.List;

/**
 * A scripted stand-in for the real drone, shared by the Week 3 workflow tests.
 *
 * <p>It counts calls so tests can assert on the <em>shape of the saga</em> — one takeoff, one
 * return, N drops, one compensation — instead of on timing, which is what Temporal's time-skipping
 * test server makes meaningless anyway.</p>
 */
public class FakeDroneActivities implements DroneActivities {

    public double battery = 80.0;
    public boolean failDrop;
    public int takeoffs;
    public int drops;
    public int returns;
    public int compensations;
    public int navigations;

    @Override
    public TakeoffResult takeoff(MissionCommand command) {
        takeoffs++;
        return new TakeoffResult(battery, command.originLat(), command.originLng(), 110,
                "airborne");
    }

    @Override
    public NavigationResult navigate(MissionCommand command, Waypoint waypoint) {
        navigations++;
        return new NavigationResult(battery, waypoint.lat(), waypoint.lng(),
                waypoint.altitudeM(), "arrived at " + waypoint.label());
    }

    @Override
    public DropResult dropParcel(MissionCommand command) {
        if (failDrop) {
            throw new IllegalStateException("winch jammed at " + command.destinationLabel());
        }
        drops++;
        return new DropResult(true, battery, "delivered to " + command.destinationLabel());
    }

    @Override
    public ReturnResult returnToBase(MissionCommand command) {
        returns++;
        return new ReturnResult(battery, command.originLat(), command.originLng(), "landed");
    }

    @Override
    public void compensateReturnToBase(MissionCommand command, String reason) {
        compensations++;
        returns++;
    }

    @Override
    public void reportPhase(MissionCommand command, String phase, String detail) {
        // Recorded in the test server's history; nothing to assert here.
    }

    @Override
    public RoutePlan planRoute(RouteCommand command) {
        // Input order counts as "optimised" here: the real planner is tested on its own in
        // RoutePlannerTest, so this class keeps the workflow tests about the saga.
        double km = 0;
        double lat = command.originLat();
        double lng = command.originLng();
        for (Waypoint stop : command.stops()) {
            km += Math.abs(stop.lat() - lat) * 111 + Math.abs(stop.lng() - lng) * 105;
            lat = stop.lat();
            lng = stop.lng();
        }
        return new RoutePlan(List.copyOf(command.stops()), Math.round(km * 10.0) / 10.0);
    }
}
