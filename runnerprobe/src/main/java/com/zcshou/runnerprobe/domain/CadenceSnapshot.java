package com.zcshou.runnerprobe.domain;

public final class CadenceSnapshot {
    private final CadenceState state;
    private final double cadence5sSpm;
    private final double cadence15sSpm;
    private final long totalStepCount;
    private final int window5sStepCount;
    private final int window15sStepCount;
    private final long sessionStepSpanNs;

    public CadenceSnapshot(
            CadenceState state,
            double cadence5sSpm,
            double cadence15sSpm,
            long totalStepCount,
            int window5sStepCount,
            int window15sStepCount,
            long sessionStepSpanNs
    ) {
        this.state = state;
        this.cadence5sSpm = cadence5sSpm;
        this.cadence15sSpm = cadence15sSpm;
        this.totalStepCount = totalStepCount;
        this.window5sStepCount = window5sStepCount;
        this.window15sStepCount = window15sStepCount;
        this.sessionStepSpanNs = sessionStepSpanNs;
    }

    public CadenceState getState() {
        return state;
    }

    public double getCadence5sSpm() {
        return cadence5sSpm;
    }

    public double getCadence15sSpm() {
        return cadence15sSpm;
    }

    public long getTotalStepCount() {
        return totalStepCount;
    }

    public int getWindow5sStepCount() {
        return window5sStepCount;
    }

    public int getWindow15sStepCount() {
        return window15sStepCount;
    }

    public long getSessionStepSpanNs() {
        return sessionStepSpanNs;
    }
}
