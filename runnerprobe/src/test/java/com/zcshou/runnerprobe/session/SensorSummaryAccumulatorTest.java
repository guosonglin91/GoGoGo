package com.zcshou.runnerprobe.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SensorSummaryAccumulatorTest {
    @Test
    public void emitsOneSecondSummaryWhenWindowAdvances() {
        SensorSummaryAccumulator a = new SensorSummaryAccumulator();

        assertNull(a.onSample(100_000_000L, 3.0, 4.0, 0.0));
        assertNull(a.onSample(900_000_000L, 0.0, 0.0, 10.0));

        SensorSummaryAccumulator.Summary s =
                a.onSample(1_100_000_000L, 0.0, 6.0, 8.0);

        assertEquals(2L, s.getEventCount());
        assertEquals(7.5, s.getMeanMagnitude(), 1e-9);
        assertEquals(5.0, s.getMinMagnitude(), 1e-9);
        assertEquals(10.0, s.getMaxMagnitude(), 1e-9);
    }

    @Test
    public void ignoresNonFiniteSamples() {
        SensorSummaryAccumulator a = new SensorSummaryAccumulator();

        assertNull(a.onSample(100L, Double.NaN, 1.0, 1.0));
        assertNull(a.onSample(200L, Double.POSITIVE_INFINITY, 1.0, 1.0));
        assertNull(a.flush());
    }

    @Test
    public void flushReturnsCurrentNonEmptyWindow() {
        SensorSummaryAccumulator a = new SensorSummaryAccumulator();
        a.onSample(1_200_000_000L, 1.0, 2.0, 2.0);

        SensorSummaryAccumulator.Summary s = a.flush();
        assertEquals(1L, s.getEventCount());
        assertTrue(s.getWindowEndElapsedNs() >= s.getWindowStartElapsedNs());
        assertNull(a.flush());
    }
}
