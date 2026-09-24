package com.zcshou.runnerprobe.evidence;

public final class EvidenceValidationException extends Exception {
    private final String code;

    public EvidenceValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public EvidenceValidationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
