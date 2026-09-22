package com.zcshou.route;

import java.util.ArrayList;
import java.util.List;

public final class RouteInterpolator {

    private static final double EARTH_RADIUS_M = 6371000.0;

    private RouteInterpolator() {}

    public static List<RoutePoint> resample(List<RoutePoint> input, double stepMeters) {
        List<RoutePoint> out = new ArrayList<>();
        if (input == null || input.isEmpty()) return out;
        if (input.size() == 1 || stepMeters <= 0.0) {
            out.addAll(input);
            return out;
        }

        out.add(input.get(0));

        for (int i = 0; i < input.size() - 1; i++) {
            RoutePoint a = input.get(i);
            RoutePoint b = input.get(i + 1);

            double distance = distanceMeters(a, b);
            if (distance <= 0.001) continue;

            int segments = Math.max(1, (int) Math.ceil(distance / stepMeters));
            for (int s = 1; s <= segments; s++) {
                double t = (double) s / (double) segments;
                out.add(new RoutePoint(
                        a.latitude + (b.latitude - a.latitude) * t,
                        a.longitude + (b.longitude - a.longitude) * t
                ));
            }
        }

        return out;
    }

    public static double totalDistanceMeters(List<RoutePoint> points) {
        if (points == null || points.size() < 2) return 0.0;
        double total = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            total += distanceMeters(points.get(i), points.get(i + 1));
        }
        return total;
    }

    public static double distanceMeters(RoutePoint a, RoutePoint b) {
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude - a.longitude);

        double h = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);

        return 2.0 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }
}
