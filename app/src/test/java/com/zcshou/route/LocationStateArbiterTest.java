package com.zcshou.route;

import org.junit.Test;

import static org.junit.Assert.*;

public class LocationStateArbiterTest {

    private static ServiceLocationState initialManual() {
        return ServiceLocationState.manual(0.0, 0.0, 55.0, 0.0, 0.0f);
    }

    private static RouteSample sample(long sessionId, RouteSessionState state) {
        return new RouteSample.Builder(sessionId)
                .state(state)
                .latitudeWgs84(10.0)
                .longitudeWgs84(20.0)
                .altitudeMeters(55.0)
                .targetSpeedMps(3.0)
                .outputSpeedMps(state == RouteSessionState.PAUSED ? 0.0 : 3.0)
                .bearingDeg(90.0)
                .timestampMs(5000L)
                .elapsedRealtimeNanos(5_000_000_000L)
                .lapCount(0)
                .distanceMeters(15.0)
                .routeLengthMeters(100.0)
                .progressFraction(0.15)
                .segmentIndex(1)
                .segmentCount(2)
                .build();
    }

    @Test
    public void manualModeAcceptsManualWrite() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());

        assertTrue(arbiter.updateManual(10.0, 20.0, 55.0, 1.2, 0.0f));
        assertEquals(ServiceLocationMode.MANUAL, arbiter.getMode());
    }

    @Test
    public void routeModeRejectsManualWrite() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());
        assertTrue(arbiter.beginRoute(10L));

        assertFalse(arbiter.updateManual(87.0, 43.0, 55.0, 1.2, 0.0f));
    }

    @Test
    public void routeModeAcceptsCurrentSessionSample() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());
        assertTrue(arbiter.beginRoute(10L));

        assertTrue(arbiter.acceptRouteSample(sample(10L, RouteSessionState.PLAYING)));
        assertEquals(ServiceLocationMode.ROUTE, arbiter.getMode());
    }

    @Test
    public void routeModeRejectsStaleSessionSample() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());

        assertTrue(arbiter.beginRoute(10L));
        assertTrue(arbiter.beginRoute(11L));

        assertFalse(arbiter.acceptRouteSample(sample(10L, RouteSessionState.PLAYING)));
        assertEquals(11L, arbiter.getCurrentSessionId());
    }

    @Test
    public void newSessionRejectsOldSamplesAndCommands() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());

        assertTrue(arbiter.beginRoute(10L));
        assertTrue(arbiter.beginRoute(11L));

        assertFalse(arbiter.acceptRouteSample(sample(10L, RouteSessionState.PLAYING)));
        assertFalse(arbiter.acceptRouteCommand(10L, RouteSessionState.PAUSED));
        assertEquals(11L, arbiter.getCurrentSessionId());
    }

    @Test
    public void pausedSampleChangesModeAndFreezesState() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());
        assertTrue(arbiter.beginRoute(10L));
        assertTrue(arbiter.acceptRouteSample(sample(10L, RouteSessionState.PLAYING)));

        assertTrue(arbiter.acceptRouteSample(sample(10L, RouteSessionState.PAUSED)));
        assertEquals(ServiceLocationMode.ROUTE_PAUSED, arbiter.getMode());
    }

    @Test
    public void stoppedSampleReturnsManualAndKeepsPosition() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());
        assertTrue(arbiter.beginRoute(10L));
        assertTrue(arbiter.acceptRouteSample(sample(10L, RouteSessionState.PLAYING)));
        assertTrue(arbiter.acceptRouteSample(sample(10L, RouteSessionState.STOPPED)));

        assertEquals(ServiceLocationMode.MANUAL, arbiter.getMode());
        ServiceLocationState state = arbiter.getLocationState();
        assertEquals(10.0, state.getLatitudeWgs84(), 1e-10);
        assertEquals(0.0, state.getSpeedMps(), 0.0);
    }

    @Test
    public void finishedSampleReturnsManualAndKeepsPosition() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());
        assertTrue(arbiter.beginRoute(10L));
        assertTrue(arbiter.acceptRouteSample(sample(10L, RouteSessionState.FINISHED)));

        assertEquals(ServiceLocationMode.MANUAL, arbiter.getMode());
        ServiceLocationState state = arbiter.getLocationState();
        assertEquals(10.0, state.getLatitudeWgs84(), 1e-10);
    }

    @Test
    public void errorSampleReturnsManualAndKeepsPosition() {
        LocationStateArbiter arbiter = new LocationStateArbiter(initialManual());
        assertTrue(arbiter.beginRoute(10L));
        assertTrue(arbiter.acceptRouteSample(sample(10L, RouteSessionState.ERROR)));

        assertEquals(ServiceLocationMode.MANUAL, arbiter.getMode());
        ServiceLocationState state = arbiter.getLocationState();
        assertEquals(10.0, state.getLatitudeWgs84(), 1e-10);
    }
}