package com.zcshou.route;

public final class LocationStateArbiter {
    private ServiceLocationState currentState;
    private ServiceLocationMode mode;
    private long currentSessionId;
    private boolean routeActive;

    public LocationStateArbiter(ServiceLocationState initialManualState) {
        this.currentState = initialManualState;
        this.mode = ServiceLocationMode.MANUAL;
        this.currentSessionId = 0L;
        this.routeActive = false;
    }

    public synchronized ServiceLocationState getLocationState() {
        return currentState;
    }

    public synchronized ServiceLocationMode getMode() {
        return mode;
    }

    public synchronized long getCurrentSessionId() {
        return currentSessionId;
    }

    public synchronized boolean updateManual(
            double longitudeWgs84,
            double latitudeWgs84,
            double altitudeMeters,
            double speedMps,
            float bearingDeg
    ) {
        // Manual writes accepted only in MANUAL mode
        if (mode != ServiceLocationMode.MANUAL && routeActive) {
            return false;
        }

        currentState = ServiceLocationState.manual(
                latitudeWgs84, longitudeWgs84, altitudeMeters, speedMps, bearingDeg
        );
        mode = ServiceLocationMode.MANUAL;
        return true;
    }

    public synchronized boolean beginRoute(long sessionId) {
        // Session IDs must be monotonically increasing
        if (sessionId <= currentSessionId) {
            return false;
        }

        currentSessionId = sessionId;
        routeActive = true;
        mode = ServiceLocationMode.ROUTE;
        // Preserve current location until first sample arrives
        return true;
    }

    public synchronized boolean cancelRoute(long sessionId) {
        if (sessionId != currentSessionId || !routeActive) {
            return false;
        }

        routeActive = false;
        mode = ServiceLocationMode.MANUAL;
        currentState = ServiceLocationState.manual(
                currentState.getLongitudeWgs84(),
                currentState.getLatitudeWgs84(),
                currentState.getAltitudeMeters(),
                0.0,
                currentState.getBearingDeg()
        );
        return true;
    }

    public synchronized boolean acceptRouteSample(RouteSample sample) {
        // Reject stale session samples
        if (sample.getSessionId() != currentSessionId) {
            return false;
        }

        RouteSessionState sampleState = sample.getState();

        switch (sampleState) {
            case PLAYING:
                mode = ServiceLocationMode.ROUTE;
                currentState = ServiceLocationState.fromRouteSample(sample, ServiceLocationMode.ROUTE);
                break;

            case PAUSED:
                mode = ServiceLocationMode.ROUTE_PAUSED;
                currentState = ServiceLocationState.fromRouteSample(sample, ServiceLocationMode.ROUTE_PAUSED);
                break;

            case STOPPED:
                routeActive = false;
                mode = ServiceLocationMode.MANUAL;
                // Force speed 0
                currentState = ServiceLocationState.manual(
                        sample.getLatitudeWgs84(),
                        sample.getLongitudeWgs84(),
                        sample.getAltitudeMeters(),
                        0.0,
                        (float) sample.getBearingDeg()
                );
                break;

            case FINISHED:
                routeActive = false;
                mode = ServiceLocationMode.MANUAL;
                currentState = ServiceLocationState.manual(
                        sample.getLatitudeWgs84(),
                        sample.getLongitudeWgs84(),
                        sample.getAltitudeMeters(),
                        0.0,
                        (float) sample.getBearingDeg()
                );
                break;

            case ERROR:
                routeActive = false;
                mode = ServiceLocationMode.MANUAL;
                currentState = ServiceLocationState.manual(
                        sample.getLatitudeWgs84(),
                        sample.getLongitudeWgs84(),
                        sample.getAltitudeMeters(),
                        0.0,
                        (float) sample.getBearingDeg()
                );
                break;

            default:
                // READY - ignore, no state change
                return false;
        }

        return true;
    }

    public synchronized boolean acceptRouteCommand(
            long sessionId,
            RouteSessionState desiredState
    ) {
        // Only authorize current session
        if (sessionId != currentSessionId || !routeActive) {
            return false;
        }

        // Only PAUSED is meaningful as a command here (actual transition done by controller)
        // The controller will verify the transition via the sample it publishes
        return true;
    }
}