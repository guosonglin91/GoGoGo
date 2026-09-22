package com.zcshou.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RouteGeometry {
    public static class Position {
        public final double latitudeWgs84;
        public final double longitudeWgs84;
        public final double bearingDeg;
        public final int segmentIndex;

        public Position(double latitudeWgs84, double longitudeWgs84, double bearingDeg, int segmentIndex) {
            this.latitudeWgs84 = latitudeWgs84;
            this.longitudeWgs84 = longitudeWgs84;
            this.bearingDeg = bearingDeg;
            this.segmentIndex = segmentIndex;
        }
    }

    private final List<RoutePoint> canonicalPoints;
    private final double[] segmentLengths;
    private final double[] cumulativeDistances;
    private final double routeLength;
    private final boolean hasClosingSegment;

    private RouteGeometry(
            List<RoutePoint> canonicalPoints,
            double[] segmentLengths,
            double[] cumulativeDistances,
            double routeLength,
            boolean hasClosingSegment
    ) {
        this.canonicalPoints = canonicalPoints;
        this.segmentLengths = segmentLengths;
        this.cumulativeDistances = cumulativeDistances;
        this.routeLength = routeLength;
        this.hasClosingSegment = hasClosingSegment;
    }

    public static RouteGeometry from(RoutePlan resolvedPlan) {
        if (resolvedPlan.requiresAltitudeResolution()) {
            throw new IllegalArgumentException("Plan must have resolved altitude");
        }

        List<RoutePoint> raw = resolvedPlan.getPointsWgs84();
        List<RoutePoint> canonical = new ArrayList<>(raw);

        // Remove terminal duplicate when first/last distance <= 0.5 m
        if (canonical.size() >= 2) {
            RoutePoint first = canonical.get(0);
            RoutePoint last = canonical.get(canonical.size() - 1);
            double dist = RouteInterpolator.distanceMeters(first, last);
            if (dist <= 0.5) {
                canonical.remove(canonical.size() - 1);
                if (canonical.size() < 2) {
                    throw new IllegalArgumentException("Route degenerate after duplicate closing point removal");
                }
            }
        }

        boolean planLoop = resolvedPlan.isLoop();
        boolean planExpectedClosed = resolvedPlan.isExpectedClosedLoop();

        // hasClosingSegment = expectedClosedLoop || loop
        boolean hasClosingSegment = planExpectedClosed || planLoop;

        // Number of segments: if closing, one per point (last->first); otherwise (n-1)
        int numSegments = hasClosingSegment ? canonical.size() : canonical.size() - 1;

        double[] segLengths = new double[numSegments];
        double[] cumDist = new double[numSegments];

        double total = 0.0;
        for (int i = 0; i < numSegments; i++) {
            RoutePoint p1 = canonical.get(i);
            RoutePoint p2;
            if (i < canonical.size() - 1) {
                p2 = canonical.get(i + 1);
            } else {
                // Closing segment: last -> first
                p2 = canonical.get(0);
            }

            double len = RouteInterpolator.distanceMeters(p1, p2);

            // Degenerate segments <= 0.001 m treated as 0
            if (len <= 0.001) {
                len = 0.0;
            }

            segLengths[i] = len;
            total += len;
            cumDist[i] = total;
        }

        return new RouteGeometry(
                Collections.unmodifiableList(canonical),
                segLengths,
                cumDist,
                total,
                hasClosingSegment
        );
    }

    public Position sampleAtDistance(double distanceMeters, float fallbackBearingDeg) {
        double d = distanceMeters;

        if (!hasClosingSegment) {
            // Clamp non-loop distance to [0, routeLength]
            if (d < 0.0) d = 0.0;
            if (d > routeLength) d = routeLength;
        } else {
            // For loop/closing, wrap around route length
            if (routeLength > 0.0) {
                d = d % routeLength;
            } else {
                d = 0.0;
            }
            if (d < 0.0) d = 0.0;
        }

        // Find segment
        int segIdx = 0;
        for (int i = 0; i < segmentLengths.length; i++) {
            if (d <= cumulativeDistances[i]) {
                segIdx = i;
                break;
            }
            if (i == segmentLengths.length - 1) {
                segIdx = i;
            }
        }

        // Compute local fraction
        double segStart = (segIdx == 0) ? 0.0 : cumulativeDistances[segIdx - 1];
        double segLen = segmentLengths[segIdx];
        double localD = d - segStart;

        double fraction;
        if (segLen <= 0.0) {
            fraction = 0.0;
        } else {
            fraction = Math.min(localD / segLen, 1.0);
        }

        // Get segment endpoints
        RoutePoint p1 = canonicalPoints.get(segIdx);
        RoutePoint p2;
        if (segIdx < canonicalPoints.size() - 1) {
            p2 = canonicalPoints.get(segIdx + 1);
        } else {
            // Closing segment
            p2 = canonicalPoints.get(0);
        }

        // Linear interpolation
        double lat = p1.latitude + (p2.latitude - p1.latitude) * fraction;
        double lng = p1.longitude + (p2.longitude - p1.longitude) * fraction;

        // Bearing from current segment direction
        double bearing;
        if (segLen <= 0.001) {
            bearing = fallbackBearingDeg;
        } else {
            bearing = RouteTestMath.bearingDegrees(p1, p2, (float) fallbackBearingDeg);
        }

        // Normalize bearing
        bearing = bearing % 360.0;
        if (bearing < 0) bearing += 360.0;

        return new Position(lat, lng, bearing, segIdx);
    }

    public List<RoutePoint> getCanonicalPoints() {
        return canonicalPoints;
    }

    public double getRouteLengthMeters() {
        return routeLength;
    }

    public int getSegmentCount() {
        return segmentLengths.length;
    }

    public boolean hasClosingSegment() {
        return hasClosingSegment;
    }
}