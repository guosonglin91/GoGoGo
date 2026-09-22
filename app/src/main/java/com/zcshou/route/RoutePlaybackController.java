package com.zcshou.route;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class RoutePlaybackController {
    public interface Listener {
        void onRouteSample(RouteSample sample);
    }

    public interface Clock {
        long elapsedRealtimeNanos();
        long currentTimeMillis();
    }

    private final Clock clock;
    private final Listener listener;
    private final AtomicReference<RouteSample> lastSample;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;
    private RouteMotionEngine engine;
    private long currentSessionId;
    private boolean shutdownFlag;

    public RoutePlaybackController(Clock clock, Listener listener) {
        this.clock = clock;
        this.listener = listener;
        this.lastSample = new AtomicReference<>(null);
        this.currentSessionId = 0L;
        this.shutdownFlag = false;
    }

    public synchronized boolean start(long sessionId, RoutePlan resolvedPlan) {
        if (shutdownFlag) return false;
        if (sessionId <= currentSessionId) return false;

        // Shut down old scheduler/engine
        shutdownScheduler();

        currentSessionId = sessionId;
        long nowNs = clock.elapsedRealtimeNanos();
        long nowMs = clock.currentTimeMillis();

        engine = new RouteMotionEngine(sessionId, resolvedPlan, nowNs, nowMs);

        long intervalMs = resolvedPlan.getNominalUpdateIntervalMs();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "route-controller-" + sessionId);
            t.setDaemon(true);
            return t;
        });

        tickFuture = scheduler.scheduleAtFixedRate(
                this::tick,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        return true;
    }

    public synchronized boolean pause(long sessionId) {
        if (shutdownFlag) return false;
        if (sessionId != currentSessionId || engine == null) return false;

        long nowNs = clock.elapsedRealtimeNanos();
        long nowMs = clock.currentTimeMillis();
        RouteSample sample = engine.pause(nowNs, nowMs);
        publishSample(sample);
        return true;
    }

    public synchronized boolean resume(long sessionId) {
        if (shutdownFlag) return false;
        if (sessionId != currentSessionId || engine == null) return false;

        long nowNs = clock.elapsedRealtimeNanos();
        long nowMs = clock.currentTimeMillis();
        RouteSample sample = engine.resume(nowNs, nowMs);
        publishSample(sample);
        return true;
    }

    public synchronized boolean stop(long sessionId) {
        if (shutdownFlag) return false;
        if (sessionId != currentSessionId || engine == null) return false;

        long nowNs = clock.elapsedRealtimeNanos();
        long nowMs = clock.currentTimeMillis();
        RouteSample sample = engine.stop(nowNs, nowMs);
        publishSample(sample);
        shutdownScheduler();
        return true;
    }

    public synchronized boolean fail(long sessionId, String reason) {
        if (shutdownFlag) return false;
        if (sessionId != currentSessionId || engine == null) return false;

        long nowNs = clock.elapsedRealtimeNanos();
        long nowMs = clock.currentTimeMillis();
        RouteSample sample = engine.fail(reason, nowNs, nowMs);
        publishSample(sample);
        shutdownScheduler();
        return true;
    }

    public synchronized RouteSnapshot getSnapshot(ServiceLocationMode mode) {
        if (engine == null) {
            // Return empty snapshot
            return new RouteSnapshot.Builder(currentSessionId)
                    .state(RouteSessionState.READY)
                    .build();
        }

        long nowNs = clock.elapsedRealtimeNanos();
        long nowMs = clock.currentTimeMillis();
        return engine.snapshot(mode, nowNs, nowMs);
    }

    public synchronized long getCurrentSessionId() {
        return currentSessionId;
    }

    public synchronized void shutdown() {
        shutdownFlag = true;
        shutdownScheduler();
        engine = null;
        currentSessionId = 0L;
    }

    // ---- Internal ----

    private void tick() {
        RouteSample sample;
        boolean terminal = false;
        long currentId;

        synchronized (this) {
            if (shutdownFlag || engine == null) return;
            currentId = currentSessionId;

            try {
                long nowNs = clock.elapsedRealtimeNanos();
                long nowMs = clock.currentTimeMillis();
                sample = engine.sample(nowNs, nowMs);
                terminal = engine.isTerminal();
            } catch (RuntimeException e) {
                // Convert any exception to ERROR
                long nowNs = clock.elapsedRealtimeNanos();
                long nowMs = clock.currentTimeMillis();
                sample = engine.fail("Controller tick error: " + e.getMessage(), nowNs, nowMs);
                terminal = true;
            }
        }

        publishSample(sample);

        if (terminal) {
            synchronized (this) {
                shutdownScheduler();
            }
        }
    }

    private void publishSample(RouteSample sample) {
        if (sample == null) return;
        lastSample.set(sample);

        if (listener != null) {
            try {
                listener.onRouteSample(sample);
            } catch (RuntimeException ignored) {
                // Listener exceptions must not kill the scheduler thread
            }
        }
    }

    private void shutdownScheduler() {
        if (tickFuture != null) {
            tickFuture.cancel(false);
            tickFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }
}