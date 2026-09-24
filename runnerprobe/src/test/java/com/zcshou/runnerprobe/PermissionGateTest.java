package com.zcshou.runnerprobe;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PermissionGateTest {
    @Test
    public void api29NeedsActivityRecognitionForStepSensors() {
        PermissionGate.Result r = PermissionGate.evaluate(29, true, true, false);
        assertTrue(r.needsActivityRecognition());
        assertFalse(r.canRecordMotionSensors());
    }

    @Test
    public void locationCanBeUnavailableWithoutBlockingSensorOnlySession() {
        PermissionGate.Result r = PermissionGate.evaluate(32, false, false, true);
        assertFalse(r.canRecordLocation());
        assertTrue(r.canRecordMotionSensors());
    }
}
