package com.zcshou.runnerprobe.session;

import com.zcshou.runnerprobe.evidence.EvidenceSchema;
import com.zcshou.runnerprobe.evidence.EvidenceValidationException;
import com.zcshou.runnerprobe.evidence.SessionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionMetadata {
    private final String sessionId;
    private final long startElapsedNs;
    private final long endElapsedNs;
    private final long officialStartElapsedNs;
    private final long officialEndElapsedNs;
    private final String appVersion;
    private final String sourceCommitSha;
    private final String deviceModel;
    private final String androidRelease;
    private final int apiLevel;
    private final String bootMarker;
    private final String permissionState;
    private final String detectorName;
    private final String detectorVendor;
    private final int detectorId;
    private final int detectorType;
    private final String detectorStringType;
    private final int detectorVersion;
    private final boolean detectorWakeUp;
    private final int detectorReportingMode;
    private final String counterName;
    private final String counterVendor;
    private final int counterId;
    private final int counterType;
    private final String counterStringType;
    private final int counterVersion;
    private final boolean counterWakeUp;
    private final int counterReportingMode;
    private final List<String> lifecycleEvents;
    private final List<String> errorCodes;

    private SessionMetadata(Builder b) throws EvidenceValidationException {
        sessionId = SessionId.validate(b.sessionId);
        startElapsedNs = b.startElapsedNs;
        endElapsedNs = b.endElapsedNs;
        officialStartElapsedNs = b.officialStartElapsedNs;
        officialEndElapsedNs = b.officialEndElapsedNs;
        appVersion = safe(b.appVersion);
        sourceCommitSha = safe(b.sourceCommitSha);
        deviceModel = safe(b.deviceModel);
        androidRelease = safe(b.androidRelease);
        apiLevel = b.apiLevel;
        bootMarker = safe(b.bootMarker);
        permissionState = safe(b.permissionState);
        detectorName = safe(b.detectorName);
        detectorVendor = safe(b.detectorVendor);
        detectorId = b.detectorId;
        detectorType = b.detectorType;
        detectorStringType = safe(b.detectorStringType);
        detectorVersion = b.detectorVersion;
        detectorWakeUp = b.detectorWakeUp;
        detectorReportingMode = b.detectorReportingMode;
        counterName = safe(b.counterName);
        counterVendor = safe(b.counterVendor);
        counterId = b.counterId;
        counterType = b.counterType;
        counterStringType = safe(b.counterStringType);
        counterVersion = b.counterVersion;
        counterWakeUp = b.counterWakeUp;
        counterReportingMode = b.counterReportingMode;
        lifecycleEvents = Collections.unmodifiableList(new ArrayList<>(b.lifecycleEvents));
        errorCodes = Collections.unmodifiableList(new ArrayList<>(b.errorCodes));
    }

    public String getSessionId() {
        return sessionId;
    }

    public String toJson(String finalizationStatus, List<String> extraErrors) {
        List<String> mergedErrors = new ArrayList<>(errorCodes);
        if (extraErrors != null) {
            for (String error : extraErrors) {
                if (error != null && !error.isEmpty() && !mergedErrors.contains(error)) {
                    mergedErrors.add(error);
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("{\n");
        appendField(out, "schema_version", EvidenceSchema.VERSION, true);
        appendField(out, "session_id", sessionId, true);
        appendField(out, "app_version", appVersion, true);
        appendField(out, "source_commit_sha", sourceCommitSha, true);
        appendField(out, "device_model", deviceModel, true);
        appendField(out, "android_release", androidRelease, true);
        appendNumber(out, "api_level", apiLevel, true);
        appendNumber(out, "start_elapsed_ns", startElapsedNs, true);
        appendNumber(out, "end_elapsed_ns", endElapsedNs, true);
        appendNumber(out, "official_start_elapsed_ns", officialStartElapsedNs, true);
        appendNumber(out, "official_end_elapsed_ns", officialEndElapsedNs, true);
        appendField(out, "boot_marker", bootMarker, true);
        appendField(out, "finalization_status", safe(finalizationStatus), true);
        appendField(out, "permission_state", permissionState, true);
        appendField(out, "detector_name", detectorName, true);
        appendField(out, "detector_vendor", detectorVendor, true);
        appendNumber(out, "detector_id", detectorId, true);
        appendNumber(out, "detector_type", detectorType, true);
        appendField(out, "detector_string_type", detectorStringType, true);
        appendNumber(out, "detector_version", detectorVersion, true);
        appendBoolean(out, "detector_wake_up", detectorWakeUp, true);
        appendNumber(out, "detector_reporting_mode", detectorReportingMode, true);
        appendField(out, "counter_name", counterName, true);
        appendField(out, "counter_vendor", counterVendor, true);
        appendNumber(out, "counter_id", counterId, true);
        appendNumber(out, "counter_type", counterType, true);
        appendField(out, "counter_string_type", counterStringType, true);
        appendNumber(out, "counter_version", counterVersion, true);
        appendBoolean(out, "counter_wake_up", counterWakeUp, true);
        appendNumber(out, "counter_reporting_mode", counterReportingMode, true);
        appendArray(out, "lifecycle_events", lifecycleEvents, true);
        appendArray(out, "error_codes", mergedErrors, false);
        out.append("}\n");
        return out.toString();
    }

    private static void appendField(StringBuilder out, String key, String value, boolean comma) {
        out.append("  ").append(quote(key)).append(": ").append(quote(value));
        out.append(comma ? ",\n" : "\n");
    }

    private static void appendNumber(StringBuilder out, String key, long value, boolean comma) {
        out.append("  ").append(quote(key)).append(": ").append(value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void appendBoolean(
            StringBuilder out,
            String key,
            boolean value,
            boolean comma
    ) {
        out.append("  ").append(quote(key)).append(": ").append(value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void appendArray(
            StringBuilder out,
            String key,
            List<String> values,
            boolean comma
    ) {
        out.append("  ").append(quote(key)).append(": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(quote(safe(values.get(i))));
        }
        out.append("]");
        out.append(comma ? ",\n" : "\n");
    }

    private static String quote(String value) {
        String safe = safe(value);
        StringBuilder out = new StringBuilder(safe.length() + 2);
        out.append('"');
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class Builder {
        private final String sessionId;
        private long startElapsedNs;
        private long endElapsedNs;
        private long officialStartElapsedNs = -1L;
        private long officialEndElapsedNs = -1L;
        private String appVersion = "";
        private String sourceCommitSha = "";
        private String deviceModel = "";
        private String androidRelease = "";
        private int apiLevel;
        private String bootMarker = "";
        private String permissionState = "";
        private String detectorName = "";
        private String detectorVendor = "";
        private int detectorId = -1;
        private int detectorType = -1;
        private String detectorStringType = "";
        private int detectorVersion = -1;
        private boolean detectorWakeUp;
        private int detectorReportingMode = -1;
        private String counterName = "";
        private String counterVendor = "";
        private int counterId = -1;
        private int counterType = -1;
        private String counterStringType = "";
        private int counterVersion = -1;
        private boolean counterWakeUp;
        private int counterReportingMode = -1;
        private final List<String> lifecycleEvents = new ArrayList<>();
        private final List<String> errorCodes = new ArrayList<>();

        public Builder(String sessionId) {
            this.sessionId = sessionId;
        }

        public Builder elapsedRange(long startNs, long endNs) {
            startElapsedNs = startNs;
            endElapsedNs = endNs;
            return this;
        }

        public Builder officialRange(long startNs, long endNs) {
            officialStartElapsedNs = startNs;
            officialEndElapsedNs = endNs;
            return this;
        }

        public Builder appVersion(String value) {
            appVersion = value;
            return this;
        }

        public Builder sourceCommitSha(String value) {
            sourceCommitSha = value;
            return this;
        }

        public Builder device(String model, String release, int api) {
            deviceModel = model;
            androidRelease = release;
            apiLevel = api;
            return this;
        }

        public Builder bootMarker(String value) {
            bootMarker = value;
            return this;
        }

        public Builder permissionState(String value) {
            permissionState = value;
            return this;
        }

        public Builder detector(String name, String vendor) {
            return detector(name, vendor, -1, false, -1);
        }

        public Builder detector(
                String name,
                String vendor,
                int version,
                boolean wakeUp,
                int reportingMode
        ) {
            detectorName = name;
            detectorVendor = vendor;
            detectorVersion = version;
            detectorWakeUp = wakeUp;
            detectorReportingMode = reportingMode;
            return this;
        }

        public Builder detectorIdentity(
                String name,
                String vendor,
                int id,
                int type,
                String stringType,
                int version,
                boolean wakeUp,
                int reportingMode
        ) {
            detectorId = id;
            detectorType = type;
            detectorStringType = stringType;
            return detector(name, vendor, version, wakeUp, reportingMode);
        }

        public Builder counter(String name, String vendor) {
            return counter(name, vendor, -1, false, -1);
        }

        public Builder counter(
                String name,
                String vendor,
                int version,
                boolean wakeUp,
                int reportingMode
        ) {
            counterName = name;
            counterVendor = vendor;
            counterVersion = version;
            counterWakeUp = wakeUp;
            counterReportingMode = reportingMode;
            return this;
        }

        public Builder counterIdentity(
                String name,
                String vendor,
                int id,
                int type,
                String stringType,
                int version,
                boolean wakeUp,
                int reportingMode
        ) {
            counterId = id;
            counterType = type;
            counterStringType = stringType;
            return counter(name, vendor, version, wakeUp, reportingMode);
        }

        public Builder addLifecycleEvent(String value) {
            if (value != null && !value.isEmpty()) {
                lifecycleEvents.add(value);
            }
            return this;
        }

        public Builder addErrorCode(String value) {
            if (value != null && !value.isEmpty()) {
                errorCodes.add(value);
            }
            return this;
        }

        public SessionMetadata build() throws EvidenceValidationException {
            return new SessionMetadata(this);
        }
    }
}
