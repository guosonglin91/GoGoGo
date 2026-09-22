package com.zcshou.route;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public final class TestLocationSource {

    public enum State {
        READY,
        PLAYING,
        PAUSED,
        STOPPED,
        FINISHED
    }

    public interface Listener {
        void onRouteTestLocationChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final long sessionId;
        public final boolean hasPosition;

        public final double sourceLatitudeWgs84;
        public final double sourceLongitudeWgs84;

        public final double displayLatitudeBd09;
        public final double displayLongitudeBd09;

        public final double targetSpeedMps;
        public final double measuredSpeedMps;
        public final float bearingDeg;

        public final long timestampMs;
        public final int index;
        public final int total;
        public final State state;

        private Snapshot(
                long sessionId,
                boolean hasPosition,
                double sourceLatitudeWgs84,
                double sourceLongitudeWgs84,
                double displayLatitudeBd09,
                double displayLongitudeBd09,
                double targetSpeedMps,
                double measuredSpeedMps,
                float bearingDeg,
                long timestampMs,
                int index,
                int total,
                State state
        ) {
            this.sessionId = sessionId;
            this.hasPosition = hasPosition;
            this.sourceLatitudeWgs84 = sourceLatitudeWgs84;
            this.sourceLongitudeWgs84 = sourceLongitudeWgs84;
            this.displayLatitudeBd09 = displayLatitudeBd09;
            this.displayLongitudeBd09 = displayLongitudeBd09;
            this.targetSpeedMps = targetSpeedMps;
            this.measuredSpeedMps = measuredSpeedMps;
            this.bearingDeg = bearingDeg;
            this.timestampMs = timestampMs;
            this.index = index;
            this.total = total;
            this.state = state;
        }
    }

    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(0L);
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private static volatile long currentSessionId = 0L;
    private static volatile Snapshot latest;

    private TestLocationSource() {
    }

    public static long beginSession(double targetSpeedMps) {
        Snapshot snapshot;
        long sessionId;

        synchronized (TestLocationSource.class) {
            sessionId = NEXT_SESSION_ID.incrementAndGet();
            currentSessionId = sessionId;

            snapshot = new Snapshot(
                    sessionId,
                    false,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Math.max(0.0, targetSpeedMps),
                    0.0,
                    0.0f,
                    System.currentTimeMillis(),
                    -1,
                    0,
                    State.READY
            );
            latest = snapshot;
        }

        notifyListeners(snapshot);
        return sessionId;
    }

    public static long getCurrentSessionId() {
        return currentSessionId;
    }

    public static Snapshot getLatest() {
        return latest;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static boolean publishPosition(
            long sessionId,
            double sourceLatitudeWgs84,
            double sourceLongitudeWgs84,
            double displayLatitudeBd09,
            double displayLongitudeBd09,
            double targetSpeedMps,
            double measuredSpeedMps,
            float bearingDeg,
            long timestampMs,
            int index,
            int total
    ) {
        Snapshot snapshot;

        synchronized (TestLocationSource.class) {
            if (sessionId <= 0L || sessionId != currentSessionId) {
                return false;
            }

            snapshot = new Snapshot(
                    sessionId,
                    true,
                    sourceLatitudeWgs84,
                    sourceLongitudeWgs84,
                    displayLatitudeBd09,
                    displayLongitudeBd09,
                    Math.max(0.0, targetSpeedMps),
                    Math.max(0.0, measuredSpeedMps),
                    normalizeBearing(bearingDeg),
                    timestampMs,
                    index,
                    total,
                    State.PLAYING
            );
            latest = snapshot;
        }

        notifyListeners(snapshot);
        return true;
    }

    public static boolean publishState(long sessionId, State state) {
        if (state == null) {
            return false;
        }

        Snapshot snapshot;

        synchronized (TestLocationSource.class) {
            if (sessionId <= 0L || sessionId != currentSessionId || latest == null) {
                return false;
            }

            Snapshot previous = latest;

            snapshot = new Snapshot(
                    previous.sessionId,
                    previous.hasPosition,
                    previous.sourceLatitudeWgs84,
                    previous.sourceLongitudeWgs84,
                    previous.displayLatitudeBd09,
                    previous.displayLongitudeBd09,
                    previous.targetSpeedMps,
                    0.0,
                    previous.bearingDeg,
                    System.currentTimeMillis(),
                    previous.index,
                    previous.total,
                    state
            );
            latest = snapshot;
        }

        notifyListeners(snapshot);
        return true;
    }

    private static void notifyListeners(Snapshot snapshot) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onRouteTestLocationChanged(snapshot);
            } catch (Exception ignored) {
                // Diagnostic listeners must never break route playback.
            }
        }
    }

    private static float normalizeBearing(float bearingDeg) {
        float value = bearingDeg % 360.0f;
        if (value < 0.0f) {
            value += 360.0f;
        }
        return value;
    }
}
