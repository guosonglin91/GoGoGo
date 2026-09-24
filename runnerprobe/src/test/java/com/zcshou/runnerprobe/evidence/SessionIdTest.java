package com.zcshou.runnerprobe.evidence;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SessionIdTest {
    @Test
    public void acceptsStableAsciiSessionId() throws Exception {
        assertEquals("v2e_20260924_001",
                SessionId.validate("v2e_20260924_001"));
    }

    @Test
    public void acceptsMaximumLength() throws Exception {
        String value = repeated('A', 48);
        assertEquals(value, SessionId.validate(value));
    }

    @Test
    public void rejectsTraversalAndSeparators() {
        assertInvalid("../bad");
        assertInvalid("bad/path");
        assertInvalid("bad\\path");
    }

    @Test
    public void rejectsEmptyAndTooLong() {
        assertInvalid("");
        assertInvalid(repeated('A', 49));
    }

    private static String repeated(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            out.append(value);
        }
        return out.toString();
    }

    private static void assertInvalid(String value) {
        try {
            SessionId.validate(value);
            fail("Expected invalid session ID: " + value);
        } catch (EvidenceValidationException e) {
            assertEquals("INVALID_SESSION_ID", e.getCode());
        }
    }
}
