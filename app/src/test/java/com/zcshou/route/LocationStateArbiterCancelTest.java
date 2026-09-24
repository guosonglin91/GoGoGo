package com.zcshou.route;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationStateArbiterCancelTest {
    @Test
    public void cancelCurrentRouteReturnsOwnershipToManual() {
        LocationStateArbiter arbiter = new LocationStateArbiter(
                ServiceLocationState.manual(
                        108.0,
                        34.0,
                        55.0,
                        2.0,
                        90.0f
                )
        );

        assertTrue(arbiter.beginRoute(1L));
        assertEquals(ServiceLocationMode.ROUTE, arbiter.getMode());

        assertTrue(arbiter.cancelRoute(1L));
        assertEquals(ServiceLocationMode.MANUAL, arbiter.getMode());
        assertEquals(
                0.0,
                arbiter.getLocationState().getSpeedMps(),
                0.0
        );
    }

    @Test
    public void cancelRejectsStaleSession() {
        LocationStateArbiter arbiter = new LocationStateArbiter(
                ServiceLocationState.manual(
                        108.0,
                        34.0,
                        55.0,
                        2.0,
                        90.0f
                )
        );
        assertTrue(arbiter.beginRoute(2L));
        assertFalse(arbiter.cancelRoute(1L));
        assertEquals(ServiceLocationMode.ROUTE, arbiter.getMode());
    }
}
