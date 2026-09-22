package com.zcshou.route;

public final class ServiceLocationState {
    private final double latitudeWgs84;
    private final double longitudeWgs84;
    private final double altitudeMeters;
    private final double speedMps;
    private final float bearingDeg;
    private final ServiceLocationMode locationMode;
    private final long sessionId;

    private ServiceLocationState(
            double latitudeWgs84,
            double longitudeWgs84,
            double altitudeMeters,
            double speedMps,
            float bearingDeg,
            ServiceLocationMode locationMode,
            long sessionId
    ) {
        this.latitudeWgs84 = latitudeWgs84;
        this.longitudeWgs84 = longitudeWgs84;
        this.altitudeMeters = altitudeMeters;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        this.locationMode = locationMode;
        this.sessionId = sessionId;
    }

    public static ServiceLocationState manual(
            double latitudeWgs84,
            double longitudeWgs84,
            double altitudeMeters,
            double speedMps,
            float bearingDeg
    ) {
        return new ServiceLocationState(
                latitudeWgs84, longitudeWgs84, altitudeMeters,
                speedMps, bearingDeg,
                ServiceLocationMode.MANUAL, 0L
        );
    }

    public static ServiceLocationState fromRouteSample(
            RouteSample sample,
            ServiceLocationMode mode
    ) {
        return new ServiceLocationState(
                sample.getLatitudeWgs84(),
                sample.getLongitudeWgs84(),
                sample.getAltitudeMeters(),
                sample.getOutputSpeedMps(),
                (float) sample.getBearingDeg(),
                mode,
                sample.getSessionId()
        );
    }

    // Getters
    public double getLatitudeWgs84() { return latitudeWgs84; }
    public double getLongitudeWgs84() { return longitudeWgs84; }
    public double getAltitudeMeters() { return altitudeMeters; }
    public double getSpeedMps() { return speedMps; }
    public float getBearingDeg() { return bearingDeg; }
    public ServiceLocationMode getLocationMode() { return locationMode; }
    public long getSessionId() { return sessionId; }
}