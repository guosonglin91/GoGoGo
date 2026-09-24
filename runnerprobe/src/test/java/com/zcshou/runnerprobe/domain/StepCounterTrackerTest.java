package com.zcshou.runnerprobe.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StepCounterTrackerTest {
    @Test
    public void deltaStartsAtZeroAndGrowsMonotonically() {
        StepCounterTracker tracker = new StepCounterTracker();

        StepCounterTracker.Result first = tracker.onCounter(1_000L, 600L);
        assertEquals(0L, first.getSessionDelta());
        assertFalse(first.isDiscontinuity());

        StepCounterTracker.Result second = tracker.onCounter(2_000L, 603L);
        assertEquals(3L, second.getSessionDelta());
        assertFalse(second.isDiscontinuity());
    }

    @Test
    public void decreasingAbsoluteCounterMarksDiscontinuityAndResetsBaseline() {
        StepCounterTracker tracker = new StepCounterTracker();
        tracker.onCounter(1_000L, 600L);

        StepCounterTracker.Result reset = tracker.onCounter(2_000L, 590L);
        assertTrue(reset.isDiscontinuity());
        assertTrue(reset.hasDiscontinuityObserved());
        assertEquals(0L, tracker.getSessionDelta());

        StepCounterTracker.Result next = tracker.onCounter(3_000L, 593L);
        assertEquals(3L, next.getSessionDelta());
        assertFalse(next.isDiscontinuity());
        assertTrue(next.hasDiscontinuityObserved());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonMonotonicTimestamp() {
        StepCounterTracker tracker = new StepCounterTracker();
        tracker.onCounter(2_000L, 10L);
        tracker.onCounter(1_000L, 11L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeAbsoluteCounter() {
        StepCounterTracker tracker = new StepCounterTracker();
        tracker.onCounter(1_000L, -1L);
    }
}
