package com.zcshou.motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ProducerSessionMetadata {
    private final String sessionId;
    private final long routeSessionId;
    private final long modelSeed;
    private final HumanMotionConfig config;
    private final long startElapsedNs;
    private final long endElapsedNs;
    private final String appVersion;
    private final String sourceCommitSha;
    private final String deviceModel;
    private final String androidRelease;
    private final int apiLevel;
    private final String bootMarker;
    private final List<String> errorCodes;

    private ProducerSessionMetadata(Builder builder) {
        this.sessionId = MotionSessionId.validate(builder.sessionId);
        this.routeSessionId = builder.routeSessionId;
        this.modelSeed = builder.modelSeed;
        this.config = builder.config;
        this.startElapsedNs = builder.startElapsedNs;
        this.endElapsedNs = builder.endElapsedNs;
        this.appVersion = safe(builder.appVersion);
        this.sourceCommitSha = safe(builder.sourceCommitSha);
        this.deviceModel = safe(builder.deviceModel);
        this.androidRelease = safe(builder.androidRelease);
        this.apiLevel = builder.apiLevel;
        this.bootMarker = safe(builder.bootMarker);
        this.errorCodes = Collections.unmodifiableList(
                new ArrayList<>(builder.errorCodes)
        );
    }

    public String getSessionId() {
        return sessionId;
    }

    public String toJson(String recorderStatus, List<String> extraErrors) {
        List<String> merged = new ArrayList<>(errorCodes);
        if (extraErrors != null) {
            for (String code : extraErrors) {
                if (code != null && !code.isEmpty() && !merged.contains(code)) {
                    merged.add(code);
                }
            }
        }

        HumanMotionConfig c = config == null
                ? HumanMotionConfig.defaultConfig(modelSeed)
                : config;

        StringBuilder out = new StringBuilder();
        out.append("{\n");
        field(out, "schema_version", "v2e-1", true);
        field(out, "session_id", sessionId, true);
        number(out, "route_session_id", routeSessionId, true);
        number(out, "model_seed", modelSeed, true);
        decimal(out, "movement_threshold_mps", c.getMovementThresholdMps(), true);
        decimal(out, "cadence_intercept_spm", c.getCadenceInterceptSpm(), true);
        decimal(out, "cadence_slope_spm_per_mps", c.getCadenceSlopeSpmPerMps(), true);
        decimal(out, "cadence_min_spm", c.getMinCadenceSpm(), true);
        decimal(out, "cadence_max_spm", c.getMaxCadenceSpm(), true);
        decimal(out, "jitter_fraction", c.getJitterFraction(), true);
        number(out, "catch_up_cap", c.getCatchUpCap(), true);
        number(out, "start_elapsed_ns", startElapsedNs, true);
        number(out, "end_elapsed_ns", endElapsedNs, true);
        field(out, "app_version", appVersion, true);
        field(out, "source_commit_sha", sourceCommitSha, true);
        field(out, "device_model", deviceModel, true);
        field(out, "android_release", androidRelease, true);
        number(out, "api_level", apiLevel, true);
        field(out, "boot_marker", bootMarker, true);
        field(out, "recorder_status", safe(recorderStatus), true);
        array(out, "error_codes", merged, false);
        out.append("}\n");
        return out.toString();
    }

    private static void field(
            StringBuilder out,
            String key,
            String value,
            boolean comma
    ) {
        out.append("  ").append(quote(key)).append(": ")
                .append(quote(value));
        out.append(comma ? ",\n" : "\n");
    }

    private static void number(
            StringBuilder out,
            String key,
            long value,
            boolean comma
    ) {
        out.append("  ").append(quote(key)).append(": ").append(value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void decimal(
            StringBuilder out,
            String key,
            double value,
            boolean comma
    ) {
        out.append("  ").append(quote(key)).append(": ")
                .append(String.format(Locale.US, "%.12g", value));
        out.append(comma ? ",\n" : "\n");
    }

    private static void array(
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
        String input = safe(value);
        StringBuilder out = new StringBuilder(input.length() + 2);
        out.append('"');
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
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
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
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
        private final long routeSessionId;
        private final long modelSeed;
        private final HumanMotionConfig config;
        private long startElapsedNs;
        private long endElapsedNs;
        private String appVersion = "";
        private String sourceCommitSha = "";
        private String deviceModel = "";
        private String androidRelease = "";
        private int apiLevel;
        private String bootMarker = "";
        private final List<String> errorCodes = new ArrayList<>();

        public Builder(
                String sessionId,
                long routeSessionId,
                HumanMotionConfig config
        ) {
            this.sessionId = sessionId;
            this.routeSessionId = routeSessionId;
            this.config = config == null
                    ? HumanMotionConfig.defaultConfig(0L)
                    : config;
            this.modelSeed = this.config.getSeed();
        }

        public Builder elapsedRange(long startNs, long endNs) {
            this.startElapsedNs = startNs;
            this.endElapsedNs = endNs;
            return this;
        }

        public Builder appVersion(String value) {
            this.appVersion = value;
            return this;
        }

        public Builder sourceCommitSha(String value) {
            this.sourceCommitSha = value;
            return this;
        }

        public Builder device(String model, String release, int api) {
            this.deviceModel = model;
            this.androidRelease = release;
            this.apiLevel = api;
            return this;
        }

        public Builder bootMarker(String value) {
            this.bootMarker = value;
            return this;
        }

        public Builder addErrorCode(String value) {
            if (value != null && !value.isEmpty()) {
                this.errorCodes.add(value);
            }
            return this;
        }

        public ProducerSessionMetadata build() {
            return new ProducerSessionMetadata(this);
        }
    }
}
