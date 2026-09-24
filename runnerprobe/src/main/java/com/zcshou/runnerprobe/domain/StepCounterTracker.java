package com.zcshou.runnerprobe.domain;

public final class StepCounterTracker {
    private boolean initialized;
    private long baselineAbsoluteCount;
    private long lastAbsoluteCount;
    private long lastTimestampNs = Long.MIN_VALUE;
    private boolean discontinuityObserved;

    public Result onCounter(long sensorTimestampNs, long absoluteCount) {
        if (sensorTimestampNs < 0L) {
            throw new IllegalArgumentException("Counter timestamp must be non-negative");
        }
        if (absoluteCount < 0L) {
            throw new IllegalArgumentException("Absolute counter must be non-negative");
        }
        if (lastTimestampNs != Long.MIN_VALUE && sensorTimestampNs <= lastTimestampNs) {
            throw new IllegalArgumentException("Counter timestamps must be strictly increasing");
        }

        boolean discontinuity = false;
        if (!initialized) {
            initialized = true;
            baselineAbsoluteCount = absoluteCount;
            lastAbsoluteCount = absoluteCount;
        } else if (absoluteCount < lastAbsoluteCount) {
            discontinuity = true;
            discontinuityObserved = true;
            baselineAbsoluteCount = absoluteCount;
            lastAbsoluteCount = absoluteCount;
        } else {
            lastAbsoluteCount = absoluteCount;
        }
        lastTimestampNs = sensorTimestampNs;

        return new Result(
                sensorTimestampNs,
                absoluteCount,
                getSessionDelta(),
                discontinuity,
                discontinuityObserved
        );
    }

    public long getSessionDelta() {
        if (!initialized) {
            return 0L;
        }
        long delta = lastAbsoluteCount - baselineAbsoluteCount;
        return Math.max(0L, delta);
    }

    public boolean hasDiscontinuity() {
        return discontinuityObserved;
    }

    public static final class Result {
        private final long sensorTimestampNs;
        private final long absoluteCount;
        private final long sessionDelta;
        private final boolean discontinuity;
        private final boolean discontinuityObserved;

        Result(
                long sensorTimestampNs,
                long absoluteCount,
                long sessionDelta,
                boolean discontinuity,
                boolean discontinuityObserved
        ) {
            this.sensorTimestampNs = sensorTimestampNs;
            this.absoluteCount = absoluteCount;
            this.sessionDelta = sessionDelta;
            this.discontinuity = discontinuity;
            this.discontinuityObserved = discontinuityObserved;
        }

        public long getSensorTimestampNs() {
            return sensorTimestampNs;
        }

        public long getAbsoluteCount() {
            return absoluteCount;
        }

        public long getSessionDelta() {
            return sessionDelta;
        }

        public boolean isDiscontinuity() {
            return discontinuity;
        }

        public boolean hasDiscontinuityObserved() {
            return discontinuityObserved;
        }
    }
}
