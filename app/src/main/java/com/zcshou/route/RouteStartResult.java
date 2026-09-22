package com.zcshou.route;

public final class RouteStartResult {
    public enum ErrorCode {
        NONE,
        INVALID_ROUTE,
        INVALID_SPEED,
        MOCK_PROVIDER_UNAVAILABLE,
        GPS_PROVIDER_UNAVAILABLE,
        NETWORK_PROVIDER_UNAVAILABLE,
        CONTROLLER_START_FAILED
    }

    private final boolean success;
    private final long sessionId;
    private final ErrorCode errorCode;
    private final String message;

    private RouteStartResult(boolean success, long sessionId, ErrorCode errorCode, String message) {
        this.success = success;
        this.sessionId = sessionId;
        this.errorCode = errorCode;
        this.message = message;
    }

    public static RouteStartResult success(long sessionId) {
        return new RouteStartResult(true, sessionId, ErrorCode.NONE, null);
    }

    public static RouteStartResult failure(ErrorCode code, String message) {
        return new RouteStartResult(false, 0L, code, message);
    }

    public boolean isSuccess() { return success; }
    public long getSessionId() { return sessionId; }
    public ErrorCode getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
}