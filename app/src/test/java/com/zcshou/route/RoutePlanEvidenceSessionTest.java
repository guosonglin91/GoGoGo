package com.zcshou.route;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RoutePlanEvidenceSessionTest {
    @Test
    public void newRequestCarriesValidatedEvidenceSessionId() {
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(34.0, 108.0),
                        new RoutePoint(34.001, 108.001)
                ),
                false,
                false,
                3.0,
                100L,
                "v2e_20260924_001"
        );

        assertEquals(
                "v2e_20260924_001",
                plan.getEvidenceSessionId()
        );
        assertEquals(
                "v2e_20260924_001",
                plan.resolveAltitude(55.0).getEvidenceSessionId()
        );
    }

    @Test
    public void legacyRequestKeepsEvidenceDisabled() {
        RoutePlan plan = RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(34.0, 108.0),
                        new RoutePoint(34.001, 108.001)
                ),
                false,
                false,
                3.0,
                100L
        );
        assertNull(plan.getEvidenceSessionId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsafeEvidenceIdIsRejected() {
        RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(34.0, 108.0),
                        new RoutePoint(34.001, 108.001)
                ),
                false,
                false,
                3.0,
                100L,
                "../bad"
        );
    }
}
