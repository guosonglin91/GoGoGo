package com.zcshou.motion;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HumanMotionModelTest {
    @Test
    public void fixedSeedProducesRepeatableSteps() {
        HumanMotionConfig cfg = HumanMotionConfig.defaultConfig(1234L);
        HumanMotionModel a = new HumanMotionModel(cfg);
        HumanMotionModel b = new HumanMotionModel(cfg);

        a.onSample(0L, 4.0);
        b.onSample(0L, 4.0);

        assertEquals(
                a.onSample(20_000_000_000L, 4.0),
                b.onSample(20_000_000_000L, 4.0)
        );
    }

    @Test
    public void stepTimesAreStrictlyMonotonicAndCadenceBounded() {
        HumanMotionModel model = new HumanMotionModel(
                HumanMotionConfig.defaultConfig(7L)
        );
        model.onSample(0L, 4.0);

        List<SyntheticStepEvent> events =
                model.onSample(10_000_000_000L, 4.0);

        long previous = Long.MIN_VALUE;
        for (SyntheticStepEvent event : events) {
            assertTrue(event.getElapsedRealtimeNs() > previous);
            assertTrue(event.getTargetCadenceSpm() >= 100.0);
            assertTrue(event.getTargetCadenceSpm() <= 190.0);

            double baseInterval =
                    60_000_000_000.0 / event.getTargetCadenceSpm();
            double relative =
                    Math.abs(event.getIntervalNs() - baseInterval)
                            / baseInterval;
            assertTrue(relative <= 0.02000001);
            previous = event.getElapsedRealtimeNs();
        }
    }

    @Test
    public void invalidSpeedProducesNoEventsAndExplicitError() {
        HumanMotionModel model = new HumanMotionModel(
                HumanMotionConfig.defaultConfig(9L)
        );

        assertTrue(
                model.onSample(1_000_000_000L, Double.NaN).isEmpty()
        );
        assertEquals(
                "INVALID_SPEED",
                model.snapshot().getErrorCode()
        );
    }

    @Test
    public void belowMovementThresholdProducesNoSteps() {
        HumanMotionModel model = new HumanMotionModel(
                HumanMotionConfig.defaultConfig(9L)
        );

        model.onSample(0L, 0.4);
        assertTrue(model.onSample(10_000_000_000L, 0.4).isEmpty());
        assertEquals(0L, model.snapshot().getStepCount());
    }

    @Test
    public void pauseResumeDoesNotCreateCatchUpBurst() {
        HumanMotionModel model = new HumanMotionModel(
                HumanMotionConfig.defaultConfig(11L)
        );

        model.onSample(0L, 4.0);
        List<SyntheticStepEvent> before =
                model.onSample(2_000_000_000L, 4.0);
        assertTrue(before.size() > 0);

        model.pause(2_000_000_000L);
        assertTrue(
                model.onSample(12_000_000_000L, 4.0).isEmpty()
        );
        model.resume(12_000_000_000L);

        List<SyntheticStepEvent> immediatelyAfter =
                model.onSample(12_000_000_001L, 4.0);
        assertTrue(immediatelyAfter.isEmpty());
    }

    @Test
    public void tenSecondGapRemainsFiniteAndBelowCap() {
        HumanMotionModel model = new HumanMotionModel(
                HumanMotionConfig.defaultConfig(15L)
        );
        model.onSample(0L, 4.0);

        List<SyntheticStepEvent> events =
                new ArrayList<>(
                        model.onSample(10_000_000_000L, 4.0)
                );

        assertTrue(events.size() > 0);
        assertTrue(events.size() < 64);
    }
}
