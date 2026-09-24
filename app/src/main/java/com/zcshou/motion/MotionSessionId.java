package com.zcshou.motion;

import java.util.regex.Pattern;

public final class MotionSessionId {
    private static final Pattern VALID =
            Pattern.compile("[A-Za-z0-9_-]{1,48}");

    private MotionSessionId() {
    }

    public static String validate(String raw) {
        if (raw == null || !VALID.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "Session ID must match [A-Za-z0-9_-]{1,48}"
            );
        }
        return raw;
    }
}
