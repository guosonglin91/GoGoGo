package com.zcshou.route;

import com.zcshou.motion.MotionSessionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RoutePlan {
    private final List<RoutePoint> pointsWgs84;
    private final boolean expectedClosedLoop;
    private final boolean loop;
    private final double targetSpeedMps;
    private final double altitudeMeters;
    private final boolean altitudeResolved;
    private final long nominalUpdateIntervalMs;
    private final String evidenceSessionId;

    private RoutePlan(
            List<RoutePoint> pointsWgs84,
            boolean expectedClosedLoop,
            boolean loop,
            double targetSpeedMps,
            long nominalUpdateIntervalMs,
            String evidenceSessionId
    ) {
        this.pointsWgs84 = Collections.unmodifiableList(new ArrayList<>(pointsWgs84));
        this.expectedClosedLoop = expectedClosedLoop;
        this.loop = loop;
        this.targetSpeedMps = targetSpeedMps;
        this.altitudeMeters = Double.NaN;
        this.altitudeResolved = false;
        this.nominalUpdateIntervalMs = nominalUpdateIntervalMs;
        this.evidenceSessionId = evidenceSessionId;
    }

    private RoutePlan(
            List<RoutePoint> pointsWgs84,
            boolean expectedClosedLoop,
            boolean loop,
            double targetSpeedMps,
            double altitudeMeters,
            boolean altitudeResolved,
            long nominalUpdateIntervalMs,
            String evidenceSessionId
    ) {
        this.pointsWgs84 = Collections.unmodifiableList(new ArrayList<>(pointsWgs84));
        this.expectedClosedLoop = expectedClosedLoop;
        this.loop = loop;
        this.targetSpeedMps = targetSpeedMps;
        this.altitudeMeters = altitudeMeters;
        this.altitudeResolved = altitudeResolved;
        this.nominalUpdateIntervalMs = nominalUpdateIntervalMs;
        this.evidenceSessionId = evidenceSessionId;
    }

    public static RoutePlan request(
            List<RoutePoint> pointsWgs84,
            boolean expectedClosedLoop,
            boolean loop,
            double targetSpeedMps,
            long nominalUpdateIntervalMs
    ) {
        if (pointsWgs84 == null || pointsWgs84.size() < 2) {
            throw new IllegalArgumentException("Route must have at least 2 points");
        }
        if (targetSpeedMps <= 0.0 || targetSpeedMps > 20.0) {
            throw new IllegalArgumentException("Target speed must be > 0 and <= 20.0 m/s");
        }
        if (nominalUpdateIntervalMs < 50L) {
            throw new IllegalArgumentException("Update interval must be >= 50 ms");
        }
        return request(
                pointsWgs84,
                expectedClosedLoop,
                loop,
                targetSpeedMps,
                nominalUpdateIntervalMs,
                null
        );
    }

    public static RoutePlan request(
            List<RoutePoint> pointsWgs84,
            boolean expectedClosedLoop,
            boolean loop,
            double targetSpeedMps,
            long nominalUpdateIntervalMs,
            String evidenceSessionId
    ) {
        if (pointsWgs84 == null || pointsWgs84.size() < 2) {
            throw new IllegalArgumentException("Route must have at least 2 points");
        }
        if (targetSpeedMps <= 0.0 || targetSpeedMps > 20.0) {
            throw new IllegalArgumentException("Target speed must be > 0 and <= 20.0 m/s");
        }
        if (nominalUpdateIntervalMs < 50L) {
            throw new IllegalArgumentException("Update interval must be >= 50 ms");
        }

        String validatedEvidenceSessionId = evidenceSessionId == null
                ? null
                : MotionSessionId.validate(evidenceSessionId);

        return new RoutePlan(
                pointsWgs84,
                expectedClosedLoop,
                loop,
                targetSpeedMps,
                nominalUpdateIntervalMs,
                validatedEvidenceSessionId
        );
    }

    public RoutePlan resolveAltitude(double altitudeMeters) {
        if (!Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("Altitude must be finite");
        }
        return new RoutePlan(
                pointsWgs84,
                expectedClosedLoop,
                loop,
                targetSpeedMps,
                altitudeMeters,
                true,
                nominalUpdateIntervalMs,
                evidenceSessionId
        );
    }

    public boolean requiresAltitudeResolution() {
        return !altitudeResolved;
    }

    public List<RoutePoint> getPointsWgs84() {
        return pointsWgs84;
    }

    public boolean isExpectedClosedLoop() {
        return expectedClosedLoop;
    }

    public boolean isLoop() {
        return loop;
    }

    public double getTargetSpeedMps() {
        return targetSpeedMps;
    }

    public double getAltitudeMeters() {
        return altitudeMeters;
    }

    public long getNominalUpdateIntervalMs() {
        return nominalUpdateIntervalMs;
    }

    public String getEvidenceSessionId() {
        return evidenceSessionId;
    }
}