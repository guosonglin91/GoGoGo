package com.zcshou.runnerprobe.service;

import com.zcshou.runnerprobe.domain.CadenceState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RecordingSnapshotTest {
    @Test
    public void snapshotKeepsExplicitStateInsteadOfCollapsingToZero() {
        RecordingSnapshot snapshot = new RecordingSnapshot.Builder()
                .sessionId("v2e_snapshot_001")
                .state(RecordingState.RECORDING)
                .lastError(RecordingError.SENSOR_PRESENT_NO_EVENTS)
                .cadence(CadenceState.WARMING_UP, Double.NaN, Double.NaN)
                .detectorEventCount(0L)
                .counter(500L, 0L)
                .lastStepAgeNs(Long.MAX_VALUE)
                .sensorPresence(true, true)
                .location("gps", 34.0, 108.0, 3.0, 90.0, true)
                .lifecycleState("SCREEN_OFF")
                .build();

        assertEquals(RecordingState.RECORDING, snapshot.getState());
        assertEquals(RecordingError.SENSOR_PRESENT_NO_EVENTS, snapshot.getLastError());
        assertEquals(CadenceState.WARMING_UP, snapshot.getCadenceState());
        assertTrue(Double.isNaN(snapshot.getCadence15sSpm()));
        assertEquals("gps", snapshot.getProvider());
        assertTrue(snapshot.isMock());
        assertEquals("SCREEN_OFF", snapshot.getLifecycleState());
    }
}
