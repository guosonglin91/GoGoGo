package com.zcshou.route;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RouteTestMathTest {

    @Test
    public void stripDuplicateClosingPoint_removesTerminalDuplicate() {
        RoutePoint a = new RoutePoint(43.0, 87.0);
        RoutePoint b = new RoutePoint(43.0001, 87.0);

        List<RoutePoint> points = Arrays.asList(
                a,
                b,
                new RoutePoint(43.0, 87.0)
        );

        assertTrue(RouteTestMath.isClosedLoop(points, 0.5));

        List<RoutePoint> stripped =
                RouteTestMath.stripDuplicateClosingPoint(points, 0.5);

        assertEquals(2, stripped.size());
        assertEquals(a.latitude, stripped.get(0).latitude, 0.0);
        assertEquals(b.latitude, stripped.get(1).latitude, 0.0);
    }

    @Test
    public void stripDuplicateClosingPoint_keepsOpenRoute() {
        List<RoutePoint> points = Arrays.asList(
                new RoutePoint(43.0, 87.0),
                new RoutePoint(43.0001, 87.0),
                new RoutePoint(43.0002, 87.0)
        );

        assertFalse(RouteTestMath.isClosedLoop(points, 0.5));
        assertEquals(
                3,
                RouteTestMath
                        .stripDuplicateClosingPoint(points, 0.5)
                        .size()
        );
    }

    @Test
    public void prepareForPlayback_loopHasNoDuplicateTerminalFirstPoint() {
        List<RoutePoint> canonical = Arrays.asList(
                new RoutePoint(43.0, 87.0),
                new RoutePoint(43.0, 87.0001),
                new RoutePoint(43.0001, 87.0001)
        );

        List<RoutePoint> prepared =
                RouteTestMath.prepareForPlayback(
                        canonical,
                        2.0,
                        true,
                        0.5
                );

        assertTrue(prepared.size() > canonical.size());

        double closingDistance =
                RouteInterpolator.distanceMeters(
                        prepared.get(0),
                        prepared.get(prepared.size() - 1)
                );

        assertTrue(closingDistance > 0.5);
    }

    @Test
    public void bearingDegrees_eastIsAboutNinetyDegrees() {
        RoutePoint a = new RoutePoint(0.0, 0.0);
        RoutePoint b = new RoutePoint(0.0, 0.001);

        assertEquals(
                90.0,
                RouteTestMath.bearingDegrees(a, b, 17.0f),
                0.2
        );
    }

    @Test
    public void bearingDegrees_duplicatePointUsesFallback() {
        RoutePoint a = new RoutePoint(43.0, 87.0);

        assertEquals(
                271.5,
                RouteTestMath.bearingDegrees(a, a, 271.5f),
                0.0001
        );
    }

    @Test
    public void measuredSpeedMps_usesDistanceAndActualElapsedTime() {
        RoutePoint a = new RoutePoint(0.0, 0.0);
        RoutePoint b = new RoutePoint(0.0, 0.00001);

        double distance =
                RouteInterpolator.distanceMeters(a, b);

        double measured =
                RouteTestMath.measuredSpeedMps(
                        a,
                        1000L,
                        b,
                        2000L
                );

        assertEquals(distance, measured, 1e-9);
    }
}
