package com.zcshou.route;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestLocationSourceTest {

    @After
    public void tearDown() {
        TestLocationSource.clear();
    }

    private static RouteSnapshot snapshot(long sessionId, RouteSessionState state) {
        return new RouteSnapshot.Builder(sessionId)
                .state(state)
                .latitudeWgs84(10.0)
                .longitudeWgs84(20.0)
                .altitudeMeters(55.0)
                .targetSpeedMps(3.0)
                .outputSpeedMps(state == RouteSessionState.PLAYING ? 3.0 : 0.0)
                .bearingDeg(90.0)
                .timestampMs(5000L)
                .elapsedRealtimeNanos(5_000_000_000L)
                .lapCount(0)
                .distanceMeters(15.0)
                .routeLengthMeters(100.0)
                .progressFraction(0.15)
                .segmentIndex(1)
                .segmentCount(2)
                .mode(ServiceLocationMode.ROUTE)
                .build();
    }

    @Test
    public void publishSnapshotStoresCurrentSnapshot() {
        RouteSnapshot snap = snapshot(1L, RouteSessionState.PLAYING);
        assertTrue(TestLocationSource.publishSnapshot(snap));

        RouteSnapshot latest = TestLocationSource.getLatest();
        assertNotNull(latest);
        assertEquals(1L, latest.getSessionId());
        assertEquals(10.0, latest.getLatitudeWgs84(), 1e-10);
    }

    @Test
    public void olderSessionCannotOverwriteNewerSession() {
        RouteSnapshot current = snapshot(12L, RouteSessionState.PLAYING);
        RouteSnapshot stale = snapshot(11L, RouteSessionState.PLAYING);

        assertTrue(TestLocationSource.publishSnapshot(current));
        assertFalse(TestLocationSource.publishSnapshot(stale));
        assertEquals(12L, TestLocationSource.getLatest().getSessionId());
    }

    @Test
    public void sameSessionStateUpdatesAreAccepted() {
        RouteSnapshot s1 = snapshot(1L, RouteSessionState.PLAYING);
        RouteSnapshot s2 = new RouteSnapshot.Builder(1L)
                .state(RouteSessionState.PAUSED)
                .latitudeWgs84(10.0)
                .longitudeWgs84(20.0)
                .altitudeMeters(55.0)
                .targetSpeedMps(3.0)
                .outputSpeedMps(0.0)
                .bearingDeg(90.0)
                .timestampMs(6000L)
                .elapsedRealtimeNanos(6_000_000_000L)
                .lapCount(0)
                .distanceMeters(15.0)
                .routeLengthMeters(100.0)
                .progressFraction(0.15)
                .segmentIndex(1)
                .segmentCount(2)
                .mode(ServiceLocationMode.ROUTE_PAUSED)
                .build();

        assertTrue(TestLocationSource.publishSnapshot(s1));
        assertTrue(TestLocationSource.publishSnapshot(s2));
        assertEquals(RouteSessionState.PAUSED, TestLocationSource.getLatest().getState());
    }

    @Test
    public void clearRemovesSnapshot() {
        assertTrue(TestLocationSource.publishSnapshot(snapshot(1L, RouteSessionState.PLAYING)));
        TestLocationSource.clear();
        assertNull(TestLocationSource.getLatest());
    }

    @Test
    public void listenerReceivesPublishedSnapshot() {
        final RouteSnapshot[] captured = {null};
        TestLocationSource.Listener listener = captured::setOnRouteTestLocationChanged;
        TestLocationSource.addListener(listener);

        RouteSnapshot snap = snapshot(1L, RouteSessionState.PLAYING);
        assertTrue(TestLocationSource.publishSnapshot(snap));

        assertNotNull(captured[0]);
        assertEquals(1L, captured[0].getSessionId());

        TestLocationSource.removeListener(listener);
    }

    @Test
    public void nullSnapshotIsRejected() {
        assertFalse(TestLocationSource.publishSnapshot(null));
    }
}