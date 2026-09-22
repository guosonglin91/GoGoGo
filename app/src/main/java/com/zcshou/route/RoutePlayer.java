package com.zcshou.route;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class RoutePlayer {

    public interface Listener {
        void onPosition(RoutePoint point, int index, int total);
        void onFinished();
    }

    private final Listener listener;
    private final Object lock = new Object();

    private List<RoutePoint> route = new ArrayList<>();
    private ScheduledExecutorService executor;
    private int index = 0;
    private boolean paused = false;
    private boolean loop = false;
    private long intervalMs = 1000L;

    public RoutePlayer(Listener listener) {
        this.listener = listener;
    }

    public void configure(List<RoutePoint> route, long intervalMs, boolean loop) {
        synchronized (lock) {
            this.route = new ArrayList<>(route);
            this.intervalMs = Math.max(100L, intervalMs);
            this.loop = loop;
            this.index = 0;
            this.paused = false;
        }
    }

    public void start() {
        synchronized (lock) {
            stopLocked();
            if (route.isEmpty()) return;

            index = 0;
            paused = false;
            executor = Executors.newSingleThreadScheduledExecutor();
            executor.scheduleAtFixedRate(this::tick, 0L, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    public void pause() {
        synchronized (lock) {
            paused = true;
        }
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
        }
    }

    public boolean isPaused() {
        synchronized (lock) {
            return paused;
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked();
            index = 0;
            paused = false;
        }
    }

    private void stopLocked() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void tick() {
        RoutePoint point;
        int currentIndex;
        int total;
        boolean finished = false;

        synchronized (lock) {
            if (paused || route.isEmpty()) return;

            if (index >= route.size()) {
                if (loop) {
                    index = 0;
                } else {
                    finished = true;
                    stopLocked();
                }
            }

            if (finished) {
                point = null;
                currentIndex = 0;
                total = route.size();
            } else {
                point = route.get(index);
                currentIndex = index;
                total = route.size();
                index++;
            }
        }

        if (finished) {
            listener.onFinished();
        } else {
            listener.onPosition(point, currentIndex, total);
        }
    }
}
