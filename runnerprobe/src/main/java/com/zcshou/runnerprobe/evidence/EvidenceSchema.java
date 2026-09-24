package com.zcshou.runnerprobe.evidence;

public final class EvidenceSchema {
    public static final String VERSION = "v2e-1";

    private EvidenceSchema() {
    }

    public static String requireSupported(String value) throws EvidenceValidationException {
        if (!VERSION.equals(value)) {
            throw new EvidenceValidationException(
                    "UNSUPPORTED_SCHEMA_VERSION",
                    "Unsupported evidence schema version: " + value
            );
        }
        return value;
    }
}
