package com.zcshou.route;

public final class RouteSnapshot {
    private final long sessionId;
    private final RouteSessionState state;
    private final double latitudeWgs84;
    private final double longitudeWgs84;
    private final double altitudeMeters;
    private final double targetSpeedMps;
    private final double outputSpeedMps;
    private final double bearingDeg;
    private final long timestampMs;
    private final long elapsedRealtimeNanos;
    private final int lapCount;
    private final double distanceMeters;
    private final double routeLengthMeters;
    private final double progressFraction;
    private final int segmentIndex;
    private final int segmentCount;
    private final String errorReason;
    private final ServiceLocationMode mode;

    private RouteSnapshot(Builder builder) {
        this.sessionId = builder.sessionId;
        this.state = builder.state;
        this.latitudeWgs84 = builder.latitudeWgs84;
        this.longitudeWgs84 = builder.longitudeWgs84;
        this.altitudeMeters = builder.altitudeMeters;
        this.targetSpeedMps = builder.targetSpeedMps;
        this.outputSpeedMps = builder.outputSpeedMps;
        this.bearingDeg = builder.bearingDeg;
        this.timestampMs = builder.timestampMs;
        this.elapsedRealtimeNanos = builder.elapsedRealtimeNanos;
        this.lapCount = builder.lapCount;
        this.distanceMeters = builder.distanceMeters;
        this.routeLengthMeters = builder.routeLengthMeters;
        this.progressFraction = builder.progressFraction;
        this.segmentIndex = builder.segmentIndex;
        this.segmentCount = builder.segmentCount;
        this.errorReason = builder.errorReason;
        this.mode = builder.mode;
    }

    public static RouteSnapshot fromSample(RouteSample sample, ServiceLocationMode mode) {
        return new Builder(sample.getSessionId())
                .state(sample.getState())
                .latitudeWgs84(sample.getLatitudeWgs84())
                .longitudeWgs84(sample.getLongitudeWgs84())
                .altitudeMeters(sample.getAltitudeMeters())
                .targetSpeedMps(sample.getTargetSpeedMps())
                .outputSpeedMps(sample.getOutputSpeedMps())
                .bearingDeg(sample.getBearingDeg())
                .timestampMs(sample.getTimestampMs())
                .elapsedRealtimeNanos(sample.getElapsedRealtimeNanos())
                .lapCount(sample.getLapCount())
                .distanceMeters(sample.getDistanceMeters())
                .routeLengthMeters(sample.getRouteLengthMeters())
                .progressFraction(sample.getProgressFraction())
                .segmentIndex(sample.getSegmentIndex())
                .segmentCount(sample.getSegmentCount())
                .errorReason(sample.getErrorReason())
                .mode(mode)
                .build();
    }

    // Getters
    public long getSessionId() { return sessionId; }
    public RouteSessionState getState() { return state; }
    public double getLatitudeWgs84() { return latitudeWgs84; }
    public double getLongitudeWgs84() { return longitudeWgs84; }
    public double getAltitudeMeters() { return altitudeMeters; }
    public double getTargetSpeedMps() { return targetSpeedMps; }
    public double getOutputSpeedMps() { return outputSpeedMps; }
    public double getBearingDeg() { return bearingDeg; }
    public long getTimestampMs() { return timestampMs; }
    public long getElapsedRealtimeNanos() { return elapsedRealtimeNanos; }
    public int getLapCount() { return lapCount; }
    public double getDistanceMeters() { return distanceMeters; }
    public double getRouteLengthMeters() { return routeLengthMeters; }
    public double getProgressFraction() { return progressFraction; }
    public int getSegmentIndex() { return segmentIndex; }
    public int getSegmentCount() { return segmentCount; }
    public String getErrorReason() { return errorReason; }
    public ServiceLocationMode getMode() { return mode; }

    public static class Builder {
        private long sessionId;
        private RouteSessionState state;
        private double latitudeWgs84;
        private double longitudeWgs84;
        private double altitudeMeters;
        private double targetSpeedMps;
        private double outputSpeedMps;
        private double bearingDeg;
        private long timestampMs;
        private long elapsedRealtimeNanos;
        private int lapCount;
        private double distanceMeters;
        private double routeLengthMeters;
        private double progressFraction;
        private int segmentIndex;
        private int segmentCount;
        private String errorReason;
        private ServiceLocationMode mode;

        public Builder(long sessionId) { this.sessionId = sessionId; }

        public Builder state(RouteSessionState v) { this.state = v; return this; }
        public Builder latitudeWgs84(double v) { this.latitudeWgs84 = v; return this; }
        public Builder longitudeWgs84(double v) { this.longitudeWgs84 = v; return this; }
        public Builder altitudeMeters(double v) { this.altitudeMeters = v; return this; }
        public Builder targetSpeedMps(double v) { this.targetSpeedMps = v; return this; }
        public Builder outputSpeedMps(double v) { this.outputSpeedMps = v; return this; }
        public Builder bearingDeg(double v) { this.bearingDeg = v; return this; }
        public Builder timestampMs(long v) { this.timestampMs = v; return this; }
        public Builder elapsedRealtimeNanos(long v) { this.elapsedRealtimeNanos = v; return this; }
        public Builder lapCount(int v) { this.lapCount = v; return this; }
        public Builder distanceMeters(double v) { this.distanceMeters = v; return this; }
        public Builder routeLengthMeters(double v) { this.routeLengthMeters = v; return this; }
        public Builder progressFraction(double v) { this.progressFraction = v; return this; }
        public Builder segmentIndex(int v) { this.segmentIndex = v; return this; }
        public Builder segmentCount(int v) { this.segmentCount = v; return this; }
        public Builder errorReason(String v) { this.errorReason = v; return this; }
        public Builder mode(ServiceLocationMode v) { this.mode = v; return this; }

        public RouteSnapshot build() { return new RouteSnapshot(this); }
    }
}