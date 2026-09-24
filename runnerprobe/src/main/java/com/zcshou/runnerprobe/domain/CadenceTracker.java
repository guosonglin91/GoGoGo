package com.zcshou.runnerprobe.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class CadenceTracker {
    private static final long FIVE_SECONDS_NS = 5_000_000_000L;
    private static final long TEN_SECONDS_NS = 10_000_000_000L;
    private static final long FIFTEEN_SECONDS_NS = 15_000_000_000L;
    private static final double SECONDS_PER_MINUTE_NS = 60_000_000_000.0;

    private final Deque<Long> recentSteps = new ArrayDeque<>();

    private long totalStepCount;
    private long firstSessionStepNs = Long.MIN_VALUE;
    private long lastStepNs = Long.MIN_VALUE;

    public CadenceSnapshot onStep(long sensorTimestampNs) {
        if (sensorTimestampNs < 0L) {
            throw new IllegalArgumentException("Step timestamp must be non-negative");
        }
        if (lastStepNs != Long.MIN_VALUE && sensorTimestampNs <= lastStepNs) {
            throw new IllegalArgumentException("Step timestamps must be strictly increasing");
        }

        if (firstSessionStepNs == Long.MIN_VALUE) {
            firstSessionStepNs = sensorTimestampNs;
        }
        lastStepNs = sensorTimestampNs;
        totalStepCount++;
        recentSteps.addLast(sensorTimestampNs);
        prune(sensorTimestampNs);

        return snapshot(sensorTimestampNs);
    }

    public CadenceSnapshot snapshot(long nowNs) {
        if (nowNs < 0L) {
            throw new IllegalArgumentException("Snapshot time must be non-negative");
        }
        if (lastStepNs != Long.MIN_VALUE && nowNs < lastStepNs) {
            throw new IllegalArgumentException("Snapshot time cannot precede last step");
        }

        prune(nowNs);

        List<Long> window15 = new ArrayList<>(recentSteps);
        List<Long> window5 = filter(window15, nowNs - FIVE_SECONDS_NS);

        long span = 0L;
        if (firstSessionStepNs != Long.MIN_VALUE && lastStepNs != Long.MIN_VALUE) {
            span = lastStepNs - firstSessionStepNs;
        }

        CadenceState state;
        if (totalStepCount < 4L) {
            state = CadenceState.WARMING_UP;
        } else if (span >= FIFTEEN_SECONDS_NS) {
            state = CadenceState.GATE_ELIGIBLE;
        } else if (span >= TEN_SECONDS_NS) {
            state = CadenceState.VALID;
        } else {
            state = CadenceState.PROVISIONAL;
        }

        return new CadenceSnapshot(
                state,
                cadence(window5),
                cadence(window15),
                totalStepCount,
                window5.size(),
                window15.size(),
                span
        );
    }

    private void prune(long nowNs) {
        long cutoff = nowNs - FIFTEEN_SECONDS_NS;
        while (!recentSteps.isEmpty() && recentSteps.peekFirst() < cutoff) {
            recentSteps.removeFirst();
        }
    }

    private static List<Long> filter(List<Long> source, long cutoff) {
        List<Long> out = new ArrayList<>();
        for (Long timestamp : source) {
            if (timestamp >= cutoff) {
                out.add(timestamp);
            }
        }
        return out;
    }

    private static double cadence(List<Long> timestamps) {
        if (timestamps.size() < 2) {
            return Double.NaN;
        }
        long first = timestamps.get(0);
        long last = timestamps.get(timestamps.size() - 1);
        long span = last - first;
        if (span <= 0L) {
            return Double.NaN;
        }
        return (timestamps.size() - 1) * SECONDS_PER_MINUTE_NS / span;
    }
}
