package com.zcshou.motion;

public final class HumanMotionConfig {
    private final long seed;
    private final double movementThresholdMps;
    private final double cadenceInterceptSpm;
    private final double cadenceSlopeSpmPerMps;
    private final double minCadenceSpm;
    private final double maxCadenceSpm;
    private final double jitterFraction;
    private final int catchUpCap;

    public HumanMotionConfig(
            long seed,
            double movementThresholdMps,
            double cadenceInterceptSpm,
            double cadenceSlopeSpmPerMps,
            double minCadenceSpm,
            double maxCadenceSpm,
            double jitterFraction,
            int catchUpCap
    ) {
        if (movementThresholdMps < 0.0
                || minCadenceSpm <= 0.0
                || maxCadenceSpm < minCadenceSpm
                || jitterFraction < 0.0
                || jitterFraction >= 1.0
                || catchUpCap < 1) {
            throw new IllegalArgumentException("Invalid motion configuration");
        }
        this.seed = seed;
        this.movementThresholdMps = movementThresholdMps;
        this.cadenceInterceptSpm = cadenceInterceptSpm;
        this.cadenceSlopeSpmPerMps = cadenceSlopeSpmPerMps;
        this.minCadenceSpm = minCadenceSpm;
        this.maxCadenceSpm = maxCadenceSpm;
        this.jitterFraction = jitterFraction;
        this.catchUpCap = catchUpCap;
    }

    public static HumanMotionConfig defaultConfig(long seed) {
        return new HumanMotionConfig(
                seed,
                0.5,
                100.0,
                15.0,
                100.0,
                190.0,
                0.02,
                64
        );
    }

    public long getSeed() { return seed; }
    public double getMovementThresholdMps() { return movementThresholdMps; }
    public double getCadenceInterceptSpm() { return cadenceInterceptSpm; }
    public double getCadenceSlopeSpmPerMps() { return cadenceSlopeSpmPerMps; }
    public double getMinCadenceSpm() { return minCadenceSpm; }
    public double getMaxCadenceSpm() { return maxCadenceSpm; }
    public double getJitterFraction() { return jitterFraction; }
    public int getCatchUpCap() { return catchUpCap; }
}
