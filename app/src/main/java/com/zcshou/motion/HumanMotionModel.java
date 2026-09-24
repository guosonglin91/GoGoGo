package com.zcshou.motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class HumanMotionModel {
    private static final double NANOS_PER_MINUTE = 60_000_000_000.0;

    private final HumanMotionConfig config;
    private final Random random;

    private long stepCount;
    private long lastSampleNs = Long.MIN_VALUE;
    private long nextStepNs = Long.MIN_VALUE;
    private long nextIntervalNs;
    private double nextTargetCadenceSpm = Double.NaN;
    private double nextSpeedMps = Double.NaN;

    private boolean moving;
    private boolean paused;
    private long pauseStartNs = Long.MIN_VALUE;
    private double currentTargetCadenceSpm = Double.NaN;
    private String errorCode = "";

    public HumanMotionModel(HumanMotionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Config is required");
        }
        this.config = config;
        this.random = new Random(config.getSeed());
    }

    public List<SyntheticStepEvent> onSample(long nowNs, double speedMps) {
        if (nowNs < 0L) {
            errorCode = "INVALID_TIME";
            return Collections.emptyList();
        }
        if (lastSampleNs != Long.MIN_VALUE && nowNs < lastSampleNs) {
            errorCode = "INVALID_TIME";
            return Collections.emptyList();
        }
        lastSampleNs = nowNs;

        if (!Double.isFinite(speedMps) || speedMps < 0.0) {
            errorCode = "INVALID_SPEED";
            return Collections.emptyList();
        }
        errorCode = "";

        if (paused) {
            return Collections.emptyList();
        }

        if (speedMps < config.getMovementThresholdMps()) {
            moving = false;
            currentTargetCadenceSpm = Double.NaN;
            nextStepNs = Long.MIN_VALUE;
            nextIntervalNs = 0L;
            return Collections.emptyList();
        }

        currentTargetCadenceSpm = targetCadence(speedMps);

        if (!moving || nextStepNs == Long.MIN_VALUE) {
            moving = true;
            scheduleNext(nowNs, speedMps);
            return Collections.emptyList();
        }

        List<SyntheticStepEvent> events = new ArrayList<>();
        while (nextStepNs <= nowNs
                && events.size() < config.getCatchUpCap()) {
            stepCount++;
            events.add(new SyntheticStepEvent(
                    stepCount,
                    nextStepNs,
                    nextSpeedMps,
                    nextTargetCadenceSpm,
                    NANOS_PER_MINUTE / nextIntervalNs,
                    nextIntervalNs
            ));
            long anchor = nextStepNs;
            scheduleNext(anchor, speedMps);
        }

        if (nextStepNs <= nowNs) {
            errorCode = "BACKLOG_CAPPED";
            scheduleNext(nowNs, speedMps);
        }

        return events;
    }

    public void pause(long nowNs) {
        if (paused) {
            return;
        }
        if (nowNs < 0L
                || (lastSampleNs != Long.MIN_VALUE && nowNs < lastSampleNs)) {
            errorCode = "INVALID_TIME";
            return;
        }
        paused = true;
        pauseStartNs = nowNs;
        lastSampleNs = nowNs;
    }

    public void resume(long nowNs) {
        if (!paused) {
            return;
        }
        if (nowNs < pauseStartNs) {
            errorCode = "INVALID_TIME";
            return;
        }

        long pausedDuration = nowNs - pauseStartNs;
        if (moving && nextStepNs != Long.MIN_VALUE) {
            nextStepNs += pausedDuration;
        }
        paused = false;
        pauseStartNs = Long.MIN_VALUE;
        lastSampleNs = nowNs;
        errorCode = "";
    }

    public HumanMotionSnapshot snapshot() {
        return new HumanMotionSnapshot(
                stepCount,
                currentTargetCadenceSpm,
                moving,
                paused,
                nextStepNs,
                errorCode
        );
    }

    private void scheduleNext(long anchorNs, double speedMps) {
        double target = targetCadence(speedMps);
        double baseIntervalNs = NANOS_PER_MINUTE / target;
        double jitter =
                (random.nextDouble() * 2.0 - 1.0)
                        * config.getJitterFraction();
        long interval = Math.max(
                1L,
                Math.round(baseIntervalNs * (1.0 + jitter))
        );

        nextIntervalNs = interval;
        nextTargetCadenceSpm = target;
        nextSpeedMps = speedMps;
        nextStepNs = anchorNs + interval;
    }

    private double targetCadence(double speedMps) {
        double value = config.getCadenceInterceptSpm()
                + config.getCadenceSlopeSpmPerMps() * speedMps;
        return Math.max(
                config.getMinCadenceSpm(),
                Math.min(config.getMaxCadenceSpm(), value)
        );
    }
}
