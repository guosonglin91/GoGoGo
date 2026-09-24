package com.zcshou.runnerprobe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BuildContractTest {
    @Test
    public void runnerProbeHasIndependentApplicationId() {
        assertEquals("com.zcshou.runnerprobe", BuildContract.APPLICATION_ID);
    }
}
