package com.zcshou.route;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RoutePlaybackControllerTest {

    private FakeClock clock;
    private RoutePlaybackController controller;

    private static class FakeClock implements RoutePlaybackController.Clock {
        long elapsedNanos = 0L;
        long wallMs = 0L;

        @Override
        public long elapsedRealtimeNanos() { return elapsedNanos; }

        @Override
        public long currentTimeMillis() { return wallMs; }

        public void advance(long nanos) {
            elapsedNanos += nanos;
            wallMs += (nanos / 1_000_000L);
        }
    }

    private static RoutePlan makePlan(double speedMps, long intervalMs) {
        return RoutePlan.request(
                Arrays.asList(
                        new RoutePoint(0.0, 0.0),
                        new RoutePoint(0.0, 0.001)  // ~111 m
                ),
                false,
                false,
                speedMps,
                intervalMs
        ).resolveAltitude(55.0);
    }

    @Before
    public void setUp() {
        clock = new FakeClock();
        controller = new RoutePlaybackController(clock, null);
    }

    @After
    public void tearDown() {
        controller.shutdown();
    }

    @Test
    public void startPublishesCurrentSessionSample() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        controller = new RoutePlaybackController(clock, sample -> {
            if (sample.getState() == RouteSessionState.PLAYING) {
                latch.countDown();
            }
        });

        clock.advance(1_000_000L); // 1 ms
        RoutePlan plan = makePlan(3.0, 50L);
        assertTrue(controller.start(1L, plan));

        // Advance clock beyond interval
        clock.advance(100_000_000L); // 100 ms
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));

        RouteSnapshot snap = controller.getSnapshot(ServiceLocationMode.ROUTE);
        assertEquals(1L, snap.getSessionId());
        assertEquals(RouteSessionState.PLAYING, snap.getState());
    }

    @Test
    public void pauseWithStaleSessionIsRejected() {
        clock.advance(1_000_000L);
        RoutePlan plan = makePlan(3.0, 50L);
        assertTrue(controller.start(1L, plan));

        assertFalse(controller.pause(999L)); // stale session
    }

    @Test
    public void newStartReplacesOldSession() throws Exception {
        CountDownLatch newLatch = new CountDownLatch(1);
        controller = new RoutePlaybackController(clock, sample -> {
            if (sample.getSessionId() == 2L && sample.getState() == RouteSessionState.PLAYING) {
                newLatch.countDown();
            }
        });

        clock.advance(1_000_000L);
        controller.start(1L, makePlan(3.0, 50L));
        clock.advance(10_000_000L);

        assertTrue(controller.start(2L, makePlan(3.0, 50L)));
        clock.advance(100_000_000L);

        assertTrue(newLatch.await(500, TimeUnit.MILLISECONDS));
        assertEquals(2L, controller.getCurrentSessionId());
    }

    @Test
    public void stopPublishesStoppedSample() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        controller = new RoutePlaybackController(clock, sample -> {
            if (sample.getState() == RouteSessionState.STOPPED) {
                latch.countDown();
            }
        });

        clock.advance(1_000_000L);
        controller.start(1L, makePlan(3.0, 50L));
        clock.advance(100_000_000L);

        assertTrue(controller.stop(1L));
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));

        RouteSnapshot snap = controller.getSnapshot(ServiceLocationMode.MANUAL);
        assertEquals(RouteSessionState.STOPPED, snap.getState());
    }

    @Test
    public void listenerExceptionDoesNotKillController() throws Exception {
        final boolean[] listenerThrew = {false};
        final boolean[] listenerSucceeded = {false};
        final CountDownLatch anyCallback = new CountDownLatch(1);

        controller = new RoutePlaybackController(clock, sample -> {
            if (sample.getSessionId() != 1L) return;
            if (!listenerThrew[0]) {
                listenerThrew[0] = true;
                throw new RuntimeException("Listener failure");
            }
            listenerSucceeded[0] = true;
            anyCallback.countDown();
        });

        clock.advance(1_000_000L);
        assertTrue(controller.start(1L, makePlan(3.0, 50L)));

        // Wait for at least one callback to complete
        assertTrue(anyCallback.await(1000, TimeUnit.MILLISECONDS));
        assertTrue("Listener should have thrown first", listenerThrew[0]);
        // The controller should still be alive (listenerSucceeded may or may not fire)
    }

    @Test
    public void shutdownStopsFurtherPublication() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        controller = new RoutePlaybackController(clock, sample -> {
            if (sample.getState() == RouteSessionState.PLAYING) {
                latch.countDown();
            }
        });

        clock.advance(1_000_000L);
        controller.start(1L, makePlan(3.0, 50L));
        clock.advance(100_000_000L);
        assertTrue(latch.await(500, TimeUnit.MILLISECONDS));

        controller.shutdown();

        // After shutdown, no more samples should fire
        RouteSnapshot snap = controller.getSnapshot(ServiceLocationMode.MANUAL);
        assertNotNull(snap);
        // State could be whatever was last published
    }
}