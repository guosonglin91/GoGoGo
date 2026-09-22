package com.zcshou.route;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class TestLocationSource {

    public interface Listener {
        void onRouteTestLocationChanged(RouteSnapshot snapshot);
    }

    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private static volatile long currentSessionId = 0L;
    private static volatile RouteSnapshot latest;

    private TestLocationSource() {
    }

    public static boolean publishSnapshot(RouteSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }

        long sessionId = snapshot.getSessionId();

        synchronized (TestLocationSource.class) {
            if (sessionId <= 0L) {
                return false;
            }
            if (currentSessionId > 0L && sessionId < currentSessionId) {
                return false;
            }
            currentSessionId = sessionId;
            latest = snapshot;
        }

        notifyListeners(snapshot);
        return true;
    }

    public static RouteSnapshot getLatest() {
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

    public static void clear() {
        synchronized (TestLocationSource.class) {
            currentSessionId = 0L;
            latest = null;
        }
    }

    private static void notifyListeners(RouteSnapshot snapshot) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onRouteTestLocationChanged(snapshot);
            } catch (Exception ignored) {
                // Diagnostic listeners must never break route playback.
            }
        }
    }
}