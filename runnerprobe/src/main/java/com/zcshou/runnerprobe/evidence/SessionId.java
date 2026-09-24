package com.zcshou.runnerprobe.evidence;

import java.util.regex.Pattern;

public final class SessionId {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,48}");

    private SessionId() {
    }

    public static String validate(String raw) throws EvidenceValidationException {
        if (raw == null || !VALID.matcher(raw).matches()) {
            throw new EvidenceValidationException(
                    "INVALID_SESSION_ID",
                    "Session ID must match [A-Za-z0-9_-]{1,48}"
            );
        }
        return raw;
    }
}
