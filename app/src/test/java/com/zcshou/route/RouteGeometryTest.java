package com.zcshou.route;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class RouteGeometryTest {

    @Test
    public void closedInputWithoutLoopCompletesClosingSegmentOnce() {
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(0.0, 0.0),
                        new RoutePoint(0.0, 0.0001),
                        new RoutePoint(0.0001, 0.0001),
                        new RoutePoint(0.0, 0.0)
                ),
                true,
                false,
                3.0,
                100L
        ).resolveAltitude(55.0);

        RouteGeometry geometry = RouteGeometry.from(plan);

        assertEquals(3, geometry.getCanonicalPoints().size());
        assertTrue(geometry.hasClosingSegment());

        RouteGeometry.Position end =
                geometry.sampleAtDistance(
                        geometry.getRouteLengthMeters(),
                        0.0f
                );

        assertEquals(0.0, end.latitudeWgs84, 1e-10);
        assertEquals(0.0, end.longitudeWgs84, 1e-10);
    }

    @Test
    public void openInputWithoutLoopDoesNotInventClosingSegment() {
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(0.0, 0.0),
                        new RoutePoint(0.0, 0.0001),
                        new RoutePoint(0.0001, 0.0001)
                ),
                false,
                false,
                2.0,
                100L
        ).resolveAltitude(55.0);

        RouteGeometry geometry = RouteGeometry.from(plan);

        assertFalse(geometry.hasClosingSegment());
    }

    @Test
    public void loopOnOpenInputAddsLogicalClosingSegmentWithoutDuplicatePoint() {
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(0.0, 0.0),
                        new RoutePoint(0.0, 0.0001),
                        new RoutePoint(0.0001, 0.0001)
                ),
                false,
                true,
                2.0,
                100L
        ).resolveAltitude(55.0);

        RouteGeometry geometry = RouteGeometry.from(plan);

        assertEquals(3, geometry.getCanonicalPoints().size());
        assertTrue(geometry.hasClosingSegment());
    }

    @Test
    public void sampleAtDistanceInterpolatesWithinSegment() {
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(0.0, 0.0),
                        new RoutePoint(0.0, 0.001)
                ),
                false,
                false,
                2.0,
                100L
        ).resolveAltitude(55.0);

        RouteGeometry geometry = RouteGeometry.from(plan);
        RouteGeometry.Position mid =
                geometry.sampleAtDistance(
                        geometry.getRouteLengthMeters() / 2.0,
                        0.0f
                );

        assertEquals(0.0, mid.latitudeWgs84, 1e-9);
        assertEquals(0.0005, mid.longitudeWgs84, 1e-6);
        assertEquals(90.0, mid.bearingDeg, 0.3);
    }
}