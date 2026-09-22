package com.zcshou.route;

import java.util.ArrayList;
import java.util.List;

public final class RouteTestMath {

    private static final double MIN_DISTANCE_M = 0.001;

    private RouteTestMath() {
    }

    public static boolean isClosedLoop(List<RoutePoint> points, double toleranceMeters) {
        if (points == null || points.size() < 2) {
            return false;
        }
        double tolerance = Math.max(0.0, toleranceMeters);
        return RouteInterpolator.distanceMeters(
                points.get(0),
                points.get(points.size() - 1)
        ) <= tolerance;
    }

    public static List<RoutePoint> stripDuplicateClosingPoint(
            List<RoutePoint> points,
            double toleranceMeters
    ) {
        List<RoutePoint> result = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return result;
        }

        result.addAll(points);

        if (result.size() >= 2 && isClosedLoop(result, toleranceMeters)) {
            result.remove(result.size() - 1);
        }

        return result;
    }

    public static List<RoutePoint> prepareForPlayback(
            List<RoutePoint> canonicalWgs84,
            double stepMeters,
            boolean loop,
            double closingToleranceMeters
    ) {
        List<RoutePoint> normalized =
                stripDuplicateClosingPoint(canonicalWgs84, closingToleranceMeters);

        if (normalized.size() < 2) {
            return normalized;
        }

        List<RoutePoint> interpolationInput = new ArrayList<>(normalized);

        if (loop) {
            interpolationInput.add(normalized.get(0));
        }

        List<RoutePoint> resampled =
                RouteInterpolator.resample(interpolationInput, stepMeters);

        if (loop) {
            resampled = stripDuplicateClosingPoint(resampled, closingToleranceMeters);
        }

        return resampled;
    }

    public static double totalDistanceMeters(
            List<RoutePoint> canonicalWgs84,
            boolean includeClosingSegment
    ) {
        List<RoutePoint> normalized = stripDuplicateClosingPoint(canonicalWgs84, 0.001);

        if (normalized.size() < 2) {
            return 0.0;
        }

        double total = RouteInterpolator.totalDistanceMeters(normalized);

        if (includeClosingSegment) {
            total += RouteInterpolator.distanceMeters(
                    normalized.get(normalized.size() - 1),
                    normalized.get(0)
            );
        }

        return total;
    }

    public static float bearingDegrees(
            RoutePoint from,
            RoutePoint to,
            float fallbackBearingDeg
    ) {
        if (from == null || to == null) {
            return normalizeBearing(fallbackBearingDeg);
        }

        if (RouteInterpolator.distanceMeters(from, to) <= MIN_DISTANCE_M) {
            return normalizeBearing(fallbackBearingDeg);
        }

        double lat1 = Math.toRadians(from.latitude);
        double lat2 = Math.toRadians(to.latitude);
        double dLon = Math.toRadians(to.longitude - from.longitude);

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        double bearing = Math.toDegrees(Math.atan2(y, x));
        return normalizeBearing((float) bearing);
    }

    public static double measuredSpeedMps(
            RoutePoint previous,
            long previousTimestampMs,
            RoutePoint current,
            long currentTimestampMs
    ) {
        if (previous == null || current == null) {
            return 0.0;
        }

        long elapsedMs = currentTimestampMs - previousTimestampMs;
        if (previousTimestampMs <= 0L || elapsedMs <= 0L) {
            return 0.0;
        }

        double distanceMeters = RouteInterpolator.distanceMeters(previous, current);
        return distanceMeters / (elapsedMs / 1000.0);
    }

    private static float normalizeBearing(float bearingDeg) {
        float value = bearingDeg % 360.0f;
        if (value < 0.0f) {
            value += 360.0f;
        }
        return value;
    }
}
