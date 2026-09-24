package com.zcshou.runnerprobe.session;

public final class SensorSummaryAccumulator {
    private static final long WINDOW_NS = 1_000_000_000L;

    private long windowStartNs = Long.MIN_VALUE;
    private long lastSampleNs = Long.MIN_VALUE;
    private long count;
    private double sum;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    public Summary onSample(long elapsedNs, double x, double y, double z) {
        if (elapsedNs < 0L
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)) {
            return null;
        }

        double magnitude = Math.sqrt(x * x + y * y + z * z);
        if (!Double.isFinite(magnitude)) {
            return null;
        }

        long sampleWindow = elapsedNs - (elapsedNs % WINDOW_NS);
        Summary completed = null;
        if (windowStartNs != Long.MIN_VALUE && sampleWindow != windowStartNs) {
            completed = finishCurrent();
            reset(sampleWindow);
        } else if (windowStartNs == Long.MIN_VALUE) {
            reset(sampleWindow);
        }

        count++;
        sum += magnitude;
        min = Math.min(min, magnitude);
        max = Math.max(max, magnitude);
        lastSampleNs = elapsedNs;

        return completed;
    }

    public Summary flush() {
        if (count == 0L) {
            return null;
        }
        Summary out = finishCurrent();
        clear();
        return out;
    }

    private Summary finishCurrent() {
        if (count == 0L) {
            return null;
        }
        long endNs = Math.max(windowStartNs + WINDOW_NS, lastSampleNs);
        return new Summary(
                windowStartNs,
                endNs,
                count,
                sum / count,
                min,
                max
        );
    }

    private void reset(long startNs) {
        windowStartNs = startNs;
        lastSampleNs = Long.MIN_VALUE;
        count = 0L;
        sum = 0.0;
        min = Double.POSITIVE_INFINITY;
        max = Double.NEGATIVE_INFINITY;
    }

    private void clear() {
        windowStartNs = Long.MIN_VALUE;
        lastSampleNs = Long.MIN_VALUE;
        count = 0L;
        sum = 0.0;
        min = Double.POSITIVE_INFINITY;
        max = Double.NEGATIVE_INFINITY;
    }

    public static final class Summary {
        private final long windowStartElapsedNs;
        private final long windowEndElapsedNs;
        private final long eventCount;
        private final double meanMagnitude;
        private final double minMagnitude;
        private final double maxMagnitude;

        Summary(
                long windowStartElapsedNs,
                long windowEndElapsedNs,
                long eventCount,
                double meanMagnitude,
                double minMagnitude,
                double maxMagnitude
        ) {
            this.windowStartElapsedNs = windowStartElapsedNs;
            this.windowEndElapsedNs = windowEndElapsedNs;
            this.eventCount = eventCount;
            this.meanMagnitude = meanMagnitude;
            this.minMagnitude = minMagnitude;
            this.maxMagnitude = maxMagnitude;
        }

        public long getWindowStartElapsedNs() {
            return windowStartElapsedNs;
        }

        public long getWindowEndElapsedNs() {
            return windowEndElapsedNs;
        }

        public long getEventCount() {
            return eventCount;
        }

        public double getMeanMagnitude() {
            return meanMagnitude;
        }

        public double getMinMagnitude() {
            return minMagnitude;
        }

        public double getMaxMagnitude() {
            return maxMagnitude;
        }
    }
}
