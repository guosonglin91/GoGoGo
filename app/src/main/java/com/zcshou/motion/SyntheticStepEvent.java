package com.zcshou.motion;

import java.util.Objects;

public final class SyntheticStepEvent {
    private final long stepIndex;
    private final long elapsedRealtimeNs;
    private final double speedMps;
    private final double targetCadenceSpm;
    private final double instantaneousCadenceSpm;
    private final long intervalNs;

    public SyntheticStepEvent(
            long stepIndex,
            long elapsedRealtimeNs,
            double speedMps,
            double targetCadenceSpm,
            double instantaneousCadenceSpm,
            long intervalNs
    ) {
        this.stepIndex = stepIndex;
        this.elapsedRealtimeNs = elapsedRealtimeNs;
        this.speedMps = speedMps;
        this.targetCadenceSpm = targetCadenceSpm;
        this.instantaneousCadenceSpm = instantaneousCadenceSpm;
        this.intervalNs = intervalNs;
    }

    public long getStepIndex() { return stepIndex; }
    public long getElapsedRealtimeNs() { return elapsedRealtimeNs; }
    public double getSpeedMps() { return speedMps; }
    public double getTargetCadenceSpm() { return targetCadenceSpm; }
    public double getInstantaneousCadenceSpm() { return instantaneousCadenceSpm; }
    public long getIntervalNs() { return intervalNs; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SyntheticStepEvent)) return false;
        SyntheticStepEvent that = (SyntheticStepEvent) other;
        return stepIndex == that.stepIndex
                && elapsedRealtimeNs == that.elapsedRealtimeNs
                && intervalNs == that.intervalNs
                && Double.compare(speedMps, that.speedMps) == 0
                && Double.compare(targetCadenceSpm, that.targetCadenceSpm) == 0
                && Double.compare(
                instantaneousCadenceSpm,
                that.instantaneousCadenceSpm
        ) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                stepIndex,
                elapsedRealtimeNs,
                speedMps,
                targetCadenceSpm,
                instantaneousCadenceSpm,
                intervalNs
        );
    }
}
