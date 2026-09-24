package com.zcshou.motion;

public final class HumanMotionSnapshot {
    private final long stepCount;
    private final double targetCadenceSpm;
    private final boolean moving;
    private final boolean paused;
    private final long nextStepElapsedNs;
    private final String errorCode;

    HumanMotionSnapshot(
            long stepCount,
            double targetCadenceSpm,
            boolean moving,
            boolean paused,
            long nextStepElapsedNs,
            String errorCode
    ) {
        this.stepCount = stepCount;
        this.targetCadenceSpm = targetCadenceSpm;
        this.moving = moving;
        this.paused = paused;
        this.nextStepElapsedNs = nextStepElapsedNs;
        this.errorCode = errorCode;
    }

    public long getStepCount() { return stepCount; }
    public double getTargetCadenceSpm() { return targetCadenceSpm; }
    public boolean isMoving() { return moving; }
    public boolean isPaused() { return paused; }
    public long getNextStepElapsedNs() { return nextStepElapsedNs; }
    public String getErrorCode() { return errorCode; }
}
