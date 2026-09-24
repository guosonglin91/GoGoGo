package com.zcshou.v2finjector;

final class Timeline {
    static final long NS_PER_MINUTE = 60_000_000_000L;

    private Timeline() {
    }

    static long offsetNs(int stepIndex, int cadenceSpm) {
        if (stepIndex <= 0 || cadenceSpm <= 0) {
            throw new IllegalArgumentException("positive step index and cadence required");
        }
        return Math.round((double) stepIndex * NS_PER_MINUTE / cadenceSpm);
    }

    static int formalStepCount(int cadenceSpm, int durationSeconds) {
        if (cadenceSpm <= 0 || durationSeconds <= 0) {
            throw new IllegalArgumentException("positive cadence and duration required");
        }
        int count = 0;
        for (int i = 1; offsetNs(i, cadenceSpm) < durationSeconds * 1_000_000_000L; i++) {
            count++;
        }
        return count;
    }
}
