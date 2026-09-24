package com.zcshou.runnerprobe.evidence;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class EvidenceSchemaTest {
    @Test
    public void acceptsFrozenSchema() throws Exception {
        assertEquals("v2e-1", EvidenceSchema.requireSupported("v2e-1"));
    }

    @Test
    public void rejectsUnknownSchema() {
        try {
            EvidenceSchema.requireSupported("v2e-2");
            fail("Expected EvidenceValidationException");
        } catch (EvidenceValidationException e) {
            assertEquals("UNSUPPORTED_SCHEMA_VERSION", e.getCode());
        }
    }
}
