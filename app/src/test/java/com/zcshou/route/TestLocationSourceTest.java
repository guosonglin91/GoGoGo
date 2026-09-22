package com.zcshou.route;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestLocationSourceTest {

    @Test
    public void beginSession_createsReadySnapshotWithoutOldPosition() {
        long first = TestLocationSource.beginSession(3.0);

        assertTrue(TestLocationSource.publishPosition(
                first,
                43.1,
                87.1,
                43.2,
                87.2,
                3.0,
                2.9,
                45.0f,
                1000L,
                0,
                10
        ));

        long second = TestLocationSource.beginSession(4.0);

        TestLocationSource.Snapshot snapshot =
                TestLocationSource.getLatest();

        assertTrue(second > first);
        assertEquals(
                TestLocationSource.State.READY,
                snapshot.state
        );
        assertFalse(snapshot.hasPosition);
        assertEquals(4.0, snapshot.targetSpeedMps, 0.0);
    }

    @Test
    public void staleSession_cannotOverwriteCurrentSession() {
        long oldSession =
                TestLocationSource.beginSession(2.0);

        long currentSession =
                TestLocationSource.beginSession(3.0);

        boolean accepted =
                TestLocationSource.publishPosition(
                        oldSession,
                        1.0,
                        2.0,
                        3.0,
                        4.0,
                        2.0,
                        2.0,
                        90.0f,
                        1000L,
                        0,
                        5
                );

        assertFalse(accepted);
        assertEquals(
                currentSession,
                TestLocationSource.getLatest().sessionId
        );
    }

    @Test
    public void publishPosition_storesCanonicalDisplayAndBothSpeeds() {
        long session =
                TestLocationSource.beginSession(3.0);

        assertTrue(TestLocationSource.publishPosition(
                session,
                43.81900001,
                87.56900001,
                43.82500001,
                87.57500001,
                3.0,
                2.95,
                123.4f,
                2000L,
                7,
                100
        ));

        TestLocationSource.Snapshot snapshot =
                TestLocationSource.getLatest();

        assertEquals(
                TestLocationSource.State.PLAYING,
                snapshot.state
        );

        assertTrue(snapshot.hasPosition);

        assertEquals(
                43.81900001,
                snapshot.sourceLatitudeWgs84,
                0.0
        );

        assertEquals(
                87.56900001,
                snapshot.sourceLongitudeWgs84,
                0.0
        );

        assertEquals(
                43.82500001,
                snapshot.displayLatitudeBd09,
                0.0
        );

        assertEquals(
                87.57500001,
                snapshot.displayLongitudeBd09,
                0.0
        );

        assertEquals(
                3.0,
                snapshot.targetSpeedMps,
                0.0
        );

        assertEquals(
                2.95,
                snapshot.measuredSpeedMps,
                0.0
        );

        assertEquals(
                123.4,
                snapshot.bearingDeg,
                0.0001
        );

        assertEquals(7, snapshot.index);
        assertEquals(100, snapshot.total);
    }

    @Test
    public void pause_preservesTargetSpeedButZerosMeasuredSpeed() {
        long session =
                TestLocationSource.beginSession(3.5);

        TestLocationSource.publishPosition(
                session,
                43.1,
                87.1,
                43.2,
                87.2,
                3.5,
                3.4,
                20.0f,
                3000L,
                2,
                10
        );

        assertTrue(TestLocationSource.publishState(
                session,
                TestLocationSource.State.PAUSED
        ));

        TestLocationSource.Snapshot snapshot =
                TestLocationSource.getLatest();

        assertEquals(
                TestLocationSource.State.PAUSED,
                snapshot.state
        );

        assertEquals(
                3.5,
                snapshot.targetSpeedMps,
                0.0
        );

        assertEquals(
                0.0,
                snapshot.measuredSpeedMps,
                0.0
        );

        assertTrue(snapshot.hasPosition);
    }
}
