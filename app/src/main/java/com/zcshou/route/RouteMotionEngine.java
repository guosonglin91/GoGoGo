package com.zcshou.route;

import java.util.concurrent.TimeUnit;

public final class RouteMotionEngine {
    private final long sessionId;
    private final RoutePlan resolvedPlan;
    private final RouteGeometry geometry;
    private final long startElapsedRealtimeNanos;
    private final long startWallTimeMs;
    private final double routeLengthMeters;
    private final boolean loop;
    private final int segmentCount;

    private RouteSessionState state;
    private long accumulatedPausedNs;
    private long pauseStartNs;
    private long lastSampleElapsedNs;
    private long lastSampleWallMs;
    private double lastLatitudeWgs84;
    private double lastLongitudeWgs84;
    private double lastBearingDeg;
    private double distanceTraveled;
    private int lapCount;
    private double lastOutputSpeed;
    private String errorReason;
    private boolean terminal;

    public RouteMotionEngine(
            long sessionId,
            RoutePlan resolvedPlan,
            long startElapsedRealtimeNanos,
            long startWallTimeMs
    ) {
        this.sessionId = sessionId;
        this.resolvedPlan = resolvedPlan;
        this.geometry = RouteGeometry.from(resolvedPlan);
        this.startElapsedRealtimeNanos = startElapsedRealtimeNanos;
        this.startWallTimeMs = startWallTimeMs;
        this.routeLengthMeters = geometry.getRouteLengthMeters();
        this.loop = resolvedPlan.isLoop();
        this.segmentCount = geometry.getSegmentCount();

        this.state = RouteSessionState.READY;
        this.accumulatedPausedNs = 0L;
        this.pauseStartNs = -1L;
        this.lastSampleElapsedNs = startElapsedRealtimeNanos;
        this.lastSampleWallMs = startWallTimeMs;
        this.lastOutputSpeed = 0.0;
        this.terminal = false;

        // Initialize position to first point
        RoutePoint first = geometry.getCanonicalPoints().get(0);
        this.lastLatitudeWgs84 = first.latitude;
        this.lastLongitudeWgs84 = first.longitude;

        // Calculate initial bearing from first segment
        if (segmentCount > 0) {
            RouteGeometry.Position pos = geometry.sampleAtDistance(0.0, 0.0f);
            this.lastBearingDeg = pos.bearingDeg;
        } else {
            this.lastBearingDeg = 0.0;
        }
    }

    public synchronized RouteSample sample(
            long nowElapsedRealtimeNanos,
            long nowWallTimeMs
    ) {
        if (terminal) {
            return buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
        }

        if (state == RouteSessionState.READY) {
            state = RouteSessionState.PLAYING;
        }

        if (state == RouteSessionState.PLAYING) {
            advance(nowElapsedRealtimeNanos, nowWallTimeMs);
        }

        lastSampleElapsedNs = nowElapsedRealtimeNanos;
        lastSampleWallMs = nowWallTimeMs;
        return buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
    }

    public synchronized RouteSample pause(
            long nowElapsedRealtimeNanos,
            long nowWallTimeMs
    ) {
        if (terminal) {
            return buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
        }

        if (state == RouteSessionState.PLAYING) {
            advance(nowElapsedRealtimeNanos, nowWallTimeMs);
        }

        state = RouteSessionState.PAUSED;
        pauseStartNs = nowElapsedRealtimeNanos;
        lastOutputSpeed = 0.0;

        lastSampleElapsedNs = nowElapsedRealtimeNanos;
        lastSampleWallMs = nowWallTimeMs;
        return buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
    }

    public synchronized RouteSample resume(
            long nowElapsedRealtimeNanos,
            long nowWallTimeMs
    ) {
        if (terminal) {
            return buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
        }

        if (state == RouteSessionState.PAUSED && pauseStartNs >= 0) {
            accumulatedPausedNs += (nowElapsedRealtimeNanos - pauseStartNs);
            pauseStartNs = -1L;
        }

        state = RouteSessionState.PLAYING;

        lastSampleElapsedNs = nowElapsedRealtimeNanos;
        lastSampleWallMs = nowWallTimeMs;
        return buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
    }

    public synchronized RouteSample stop(
            long nowElapsedRealtimeNanos,
            long nowWallTimeMs
    ) {
        if (!terminal) {
            terminal = true;
            // Keep current position, set speed 0
            lastOutputSpeed = 0.0;
            state = RouteSessionState.STOPPED;
        }
        lastSampleElapsedNs = nowElapsedRealtimeNanos;
        lastSampleWallMs = nowWallTimeMs;
        return buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
    }

    public synchronized RouteSample fail(
            String reason,
            long nowElapsedRealtimeNanos,
            long nowWallTimeMs
    ) {
        if (!terminal) {
            terminal = true;
            lastOutputSpeed = 0.0;
            this.errorReason = reason;
            state = RouteSessionState.ERROR;
        }
        lastSampleElapsedNs = nowElapsedRealtimeNanos;
        lastSampleWallMs = nowWallTimeMs;
        return buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
    }

    public synchronized RouteSnapshot snapshot(
            ServiceLocationMode mode,
            long nowElapsedRealtimeNanos,
            long nowWallTimeMs
    ) {
        RouteSample sample = buildCurrentSample(nowElapsedRealtimeNanos, nowWallTimeMs);
        return RouteSnapshot.fromSample(sample, mode);
    }

    public synchronized boolean isTerminal() {
        return terminal;
    }

    // ---- Internal ----

    private void advance(long nowElapsedNs, long nowWallMs) {
        long activeNs = nowElapsedNs - startElapsedRealtimeNanos - accumulatedPausedNs;
        double activeSeconds = activeNs / 1_000_000_000.0;
        double targetSpeed = resolvedPlan.getTargetSpeedMps();

        double rawDistance = targetSpeed * activeSeconds;

        if (!loop) {
            // Clamp to route length
            if (rawDistance >= routeLengthMeters) {
                rawDistance = routeLengthMeters;
                terminal = true;
                state = RouteSessionState.FINISHED;
                lastOutputSpeed = 0.0;
            } else {
                lastOutputSpeed = targetSpeed;
            }
        } else {
            // Loop mode
            if (routeLengthMeters > 0.0) {
                lapCount = (int) (rawDistance / routeLengthMeters);
            }
            lastOutputSpeed = targetSpeed;
        }

        distanceTraveled = rawDistance;

        // Sample position from geometry
        RouteGeometry.Position pos = geometry.sampleAtDistance(rawDistance, (float) lastBearingDeg);
        lastLatitudeWgs84 = pos.latitudeWgs84;
        lastLongitudeWgs84 = pos.longitudeWgs84;
        lastBearingDeg = pos.bearingDeg;
    }

    private RouteSample buildCurrentSample(long nowElapsedNs, long nowWallMs) {
        double targetSpeed = resolvedPlan.getTargetSpeedMps();

        return new RouteSample.Builder(sessionId)
                .state(state)
                .latitudeWgs84(lastLatitudeWgs84)
                .longitudeWgs84(lastLongitudeWgs84)
                .altitudeMeters(resolvedPlan.getAltitudeMeters())
                .targetSpeedMps(targetSpeed)
                .outputSpeedMps(lastOutputSpeed)
                .bearingDeg(lastBearingDeg)
                .timestampMs(nowWallMs)
                .elapsedRealtimeNanos(nowElapsedNs)
                .lapCount(lapCount)
                .distanceMeters(distanceTraveled)
                .routeLengthMeters(routeLengthMeters)
                .progressFraction(routeLengthMeters > 0.0 ? distanceTraveled / routeLengthMeters : 0.0)
                .segmentIndex(0)
                .segmentCount(segmentCount)
                .errorReason(errorReason)
                .build();
    }
}