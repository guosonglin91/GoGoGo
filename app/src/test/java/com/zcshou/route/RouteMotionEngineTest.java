package com.zcshou.route;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class RouteMotionEngineTest {

    // Helper: create a 100m route, target 3 m/s
    private static RouteMotionEngine engineAt3Mps() {
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(0.0, 0.0),
                        new RoutePoint(0.0, 0.001)  // ~111 m
                ),
                false,
                false,
                3.0,
                100L
        ).resolveAltitude(55.0);

        return new RouteMotionEngine(1L, plan, 0L, 0L);
    }

    // Helper: seconds to nanoseconds
    private static long ns(double seconds) {
        return (long) (seconds * 1_000_000_000L);
    }

    @Test
    public void distanceDrivenByElapsedTimeNotTickCount() {
        RouteMotionEngine a = engineAt3Mps();
        RouteMotionEngine b = engineAt3Mps();

        a.sample(ns(1.0), 1000L);
        a.sample(ns(2.0), 2000L);
        RouteSample aFinal = a.sample(ns(5.0), 5000L);

        RouteSample bFinal = b.sample(ns(5.0), 5000L);

        assertEquals(
                aFinal.getDistanceMeters(),
                bFinal.getDistanceMeters(),
                1e-6
        );
        assertEquals(15.0, bFinal.getDistanceMeters(), 0.05);
    }

    @Test
    public void pauseFreezesDistance() {
        RouteMotionEngine engine = engineAt3Mps();

        RouteSample before = engine.sample(ns(2.0), 2000L);
        engine.pause(ns(2.0), 2000L);
        RouteSample paused = engine.sample(ns(12.0), 12000L);

        assertEquals(before.getDistanceMeters(), paused.getDistanceMeters(), 1e-9);
        assertEquals(0.0, paused.getOutputSpeedMps(), 0.0);
        assertEquals(RouteSessionState.PAUSED, paused.getState());
    }

    @Test
    public void resumeExcludesPausedDuration() {
        RouteMotionEngine engine = engineAt3Mps();

        engine.sample(ns(2.0), 2000L);      // 6 m
        engine.pause(ns(2.0), 2000L);
        engine.resume(ns(12.0), 12000L);    // 10 s pause
        RouteSample after = engine.sample(ns(13.0), 13000L);

        assertEquals(9.0, after.getDistanceMeters(), 0.05);
    }

    @Test
    public void nonLoopFinishesAtExactEndpoint() {
        // Use a shorter route of ~50m
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(0.0, 0.0),
                        new RoutePoint(0.0, 0.00045)  // ~50 m
                ),
                false,
                false,
                3.0,
                100L
        ).resolveAltitude(55.0);

        RouteMotionEngine engine = new RouteMotionEngine(1L, plan, 0L, 0L);

        // Run long enough to finish
        RouteSample done = engine.sample(ns(30.0), 30000L);

        assertEquals(RouteSessionState.FINISHED, done.getState());
        assertEquals(0.0, done.getOutputSpeedMps(), 0.0);
    }

    @Test
    public void closedNonLoopFinishesAtFirstPointAfterOneLap() {
        // Closed route: last point = first point
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

        RouteMotionEngine engine = new RouteMotionEngine(1L, plan, 0L, 0L);

        // Run long enough to finish one lap
        RouteSample done = engine.sample(ns(30.0), 30000L);

        assertEquals(RouteSessionState.FINISHED, done.getState());
        assertEquals(0.0, done.getOutputSpeedMps(), 0.0);
        assertEquals(0.0, done.getLatitudeWgs84(), 1e-10);
        assertEquals(0.0, done.getLongitudeWgs84(), 1e-10);
    }

    @Test
    public void loopWrapsWithoutDuplicateZeroSegment() {
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(0.0, 0.0),
                        new RoutePoint(0.0, 0.0001),
                        new RoutePoint(0.0001, 0.0001)
                ),
                false,
                true,
                3.0,
                100L
        ).resolveAltitude(55.0);

        RouteMotionEngine engine = new RouteMotionEngine(1L, plan, 0L, 0L);

        // After one lap, should still be PLAYING (loop), lapCount > 0
        RouteSample afterOneLap = engine.sample(ns(30.0), 30000L);

        assertEquals(RouteSessionState.PLAYING, afterOneLap.getState());
        assertTrue(afterOneLap.getLapCount() >= 1);
    }

    @Test
    public void bearingUsesCurrentSegment() {
        RouteMotionEngine engine = engineAt3Mps();

        RouteSample sample = engine.sample(ns(1.0), 1000L);

        // Route goes east (0,0) -> (0, 0.001), bearing ~90 degrees
        assertEquals(90.0, sample.getBearingDeg(), 5.0);
    }

    @Test
    public void stopKeepsLastPositionAndZerosSpeed() {
        RouteMotionEngine engine = engineAt3Mps();

        RouteSample running = engine.sample(ns(2.0), 2000L);
        RouteSample stopped = engine.stop(ns(2.0), 2000L);

        assertEquals(RouteSessionState.STOPPED, stopped.getState());
        assertEquals(running.getLatitudeWgs84(), stopped.getLatitudeWgs84(), 1e-10);
        assertEquals(running.getLongitudeWgs84(), stopped.getLongitudeWgs84(), 1e-10);
        assertEquals(0.0, stopped.getOutputSpeedMps(), 0.0);
    }

    @Test
    public void failureKeepsLastPositionAndReportsReason() {
        RouteMotionEngine engine = engineAt3Mps();

        RouteSample running = engine.sample(ns(2.0), 2000L);
        RouteSample failed = engine.fail("GPS provider write failure", ns(2.0), 2000L);

        assertEquals(RouteSessionState.ERROR, failed.getState());
        assertEquals(running.getLatitudeWgs84(), failed.getLatitudeWgs84(), 1e-10);
        assertEquals(running.getLongitudeWgs84(), failed.getLongitudeWgs84(), 1e-10);
        assertEquals(0.0, failed.getOutputSpeedMps(), 0.0);
        assertEquals("GPS provider write failure", failed.getErrorReason());
    }

    @Test
    public void terminalStateIsImmutable() {
        RouteMotionEngine engine = engineAt3Mps();

        engine.stop(ns(1.0), 1000L);
        assertTrue(engine.isTerminal());

        // Further operations should not change state
        RouteSample sample = engine.sample(ns(2.0), 2000L);
        assertEquals(RouteSessionState.STOPPED, sample.getState());
    }
}