package com.zcshou.motion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MotionSessionIdTest {
    @Test
    public void acceptsFrozenV2eId() {
        assertEquals(
                "v2e_20260924_001",
                MotionSessionId.validate("v2e_20260924_001")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTraversal() {
        MotionSessionId.validate("../escape");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooLongId() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 49; i++) {
            value.append('A');
        }
        MotionSessionId.validate(value.toString());
    }
}
