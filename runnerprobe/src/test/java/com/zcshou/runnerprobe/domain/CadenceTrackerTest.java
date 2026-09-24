package com.zcshou.runnerprobe.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CadenceTrackerTest {
    @Test
    public void reachesGateEligibleAfterFifteenSecondsOfSteps() {
        CadenceTracker tracker = new CadenceTracker();
        long t = 1_000_000_000L;

        for (int i = 0; i < 32; i++) {
            tracker.onStep(t + i * 500_000_000L);
        }

        CadenceSnapshot s = tracker.snapshot(t + 15_500_000_000L);
        assertEquals(CadenceState.GATE_ELIGIBLE, s.getState());
        assertEquals(120.0, s.getCadence15sSpm(), 0.5);
        assertTrue(s.getCadence5sSpm() > 119.0);
        assertEquals(32L, s.getTotalStepCount());
    }

    @Test
    public void fewerThanFourEventsRemainWarmingUp() {
        CadenceTracker tracker = new CadenceTracker();
        tracker.onStep(1_000_000_000L);
        tracker.onStep(1_500_000_000L);
        tracker.onStep(2_000_000_000L);

        assertEquals(
                CadenceState.WARMING_UP,
                tracker.snapshot(2_000_000_000L).getState()
        );
    }

    @Test
    public void transitionsThroughProvisionalAndValid() {
        CadenceTracker tracker = new CadenceTracker();
        long t = 1_000_000_000L;

        for (int i = 0; i < 4; i++) {
            tracker.onStep(t + i * 1_000_000_000L);
        }
        assertEquals(CadenceState.PROVISIONAL, tracker.snapshot(t + 3_000_000_000L).getState());

        for (int i = 4; i <= 10; i++) {
            tracker.onStep(t + i * 1_000_000_000L);
        }
        assertEquals(CadenceState.VALID, tracker.snapshot(t + 10_000_000_000L).getState());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonMonotonicStepTimestamp() {
        CadenceTracker tracker = new CadenceTracker();
        tracker.onStep(2_000_000_000L);
        tracker.onStep(1_000_000_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateStepTimestamp() {
        CadenceTracker tracker = new CadenceTracker();
        tracker.onStep(2_000_000_000L);
        tracker.onStep(2_000_000_000L);
    }

    @Test
    public void sparseWindowReturnsNaNInsteadOfZero() {
        CadenceTracker tracker = new CadenceTracker();
        tracker.onStep(1_000_000_000L);

        CadenceSnapshot s = tracker.snapshot(1_000_000_000L);
        assertTrue(Double.isNaN(s.getCadence5sSpm()));
        assertTrue(Double.isNaN(s.getCadence15sSpm()));
    }
}
