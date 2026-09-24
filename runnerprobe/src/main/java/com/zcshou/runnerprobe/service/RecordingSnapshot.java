package com.zcshou.runnerprobe.service;

import com.zcshou.runnerprobe.domain.CadenceState;

public final class RecordingSnapshot {
    private final String sessionId;
    private final RecordingState state;
    private final RecordingError lastError;
    private final CadenceState cadenceState;
    private final double cadence5sSpm;
    private final double cadence15sSpm;
    private final long detectorEventCount;
    private final long counterAbsolute;
    private final long counterDelta;
    private final long lastStepAgeNs;
    private final boolean detectorPresent;
    private final boolean counterPresent;
    private final String provider;
    private final double latitude;
    private final double longitude;
    private final double speedMps;
    private final double bearingDeg;
    private final boolean mock;
    private final String lifecycleState;

    private RecordingSnapshot(Builder b) {
        sessionId = b.sessionId;
        state = b.state;
        lastError = b.lastError;
        cadenceState = b.cadenceState;
        cadence5sSpm = b.cadence5sSpm;
        cadence15sSpm = b.cadence15sSpm;
        detectorEventCount = b.detectorEventCount;
        counterAbsolute = b.counterAbsolute;
        counterDelta = b.counterDelta;
        lastStepAgeNs = b.lastStepAgeNs;
        detectorPresent = b.detectorPresent;
        counterPresent = b.counterPresent;
        provider = b.provider;
        latitude = b.latitude;
        longitude = b.longitude;
        speedMps = b.speedMps;
        bearingDeg = b.bearingDeg;
        mock = b.mock;
        lifecycleState = b.lifecycleState;
    }

    public String getSessionId() { return sessionId; }
    public RecordingState getState() { return state; }
    public RecordingError getLastError() { return lastError; }
    public CadenceState getCadenceState() { return cadenceState; }
    public double getCadence5sSpm() { return cadence5sSpm; }
    public double getCadence15sSpm() { return cadence15sSpm; }
    public long getDetectorEventCount() { return detectorEventCount; }
    public long getCounterAbsolute() { return counterAbsolute; }
    public long getCounterDelta() { return counterDelta; }
    public long getLastStepAgeNs() { return lastStepAgeNs; }
    public boolean isDetectorPresent() { return detectorPresent; }
    public boolean isCounterPresent() { return counterPresent; }
    public String getProvider() { return provider; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getSpeedMps() { return speedMps; }
    public double getBearingDeg() { return bearingDeg; }
    public boolean isMock() { return mock; }
    public String getLifecycleState() { return lifecycleState; }

    public static final class Builder {
        private String sessionId = "";
        private RecordingState state = RecordingState.IDLE;
        private RecordingError lastError = RecordingError.NONE;
        private CadenceState cadenceState = CadenceState.WARMING_UP;
        private double cadence5sSpm = Double.NaN;
        private double cadence15sSpm = Double.NaN;
        private long detectorEventCount;
        private long counterAbsolute = -1L;
        private long counterDelta;
        private long lastStepAgeNs = Long.MAX_VALUE;
        private boolean detectorPresent;
        private boolean counterPresent;
        private String provider = "";
        private double latitude = Double.NaN;
        private double longitude = Double.NaN;
        private double speedMps = Double.NaN;
        private double bearingDeg = Double.NaN;
        private boolean mock;
        private String lifecycleState = "IDLE";

        public Builder sessionId(String value) { sessionId = safe(value); return this; }
        public Builder state(RecordingState value) { state = value; return this; }
        public Builder lastError(RecordingError value) { lastError = value; return this; }

        public Builder cadence(CadenceState value, double five, double fifteen) {
            cadenceState = value;
            cadence5sSpm = five;
            cadence15sSpm = fifteen;
            return this;
        }

        public Builder detectorEventCount(long value) { detectorEventCount = value; return this; }

        public Builder counter(long absolute, long delta) {
            counterAbsolute = absolute;
            counterDelta = delta;
            return this;
        }

        public Builder lastStepAgeNs(long value) { lastStepAgeNs = value; return this; }

        public Builder sensorPresence(boolean detector, boolean counter) {
            detectorPresent = detector;
            counterPresent = counter;
            return this;
        }

        public Builder location(
                String providerValue,
                double lat,
                double lon,
                double speed,
                double bearing,
                boolean isMock
        ) {
            provider = safe(providerValue);
            latitude = lat;
            longitude = lon;
            speedMps = speed;
            bearingDeg = bearing;
            mock = isMock;
            return this;
        }

        public Builder lifecycleState(String value) {
            lifecycleState = safe(value);
            return this;
        }

        public RecordingSnapshot build() { return new RecordingSnapshot(this); }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
