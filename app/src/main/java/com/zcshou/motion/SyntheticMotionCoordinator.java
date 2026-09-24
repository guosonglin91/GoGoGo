package com.zcshou.motion;

import com.zcshou.route.RouteSample;
import com.zcshou.route.RouteSessionState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SyntheticMotionCoordinator {
    private final Set<String> errorCodes = new LinkedHashSet<>();

    private long routeSessionId;
    private String evidenceSessionId;
    private HumanMotionConfig config;
    private HumanMotionModel model;
    private ProducerEvidenceRecorder recorder;
    private boolean active;
    private boolean routePaused;

    public synchronized void start(
            long newRouteSessionId,
            String rawEvidenceSessionId,
            File producerRoot,
            HumanMotionConfig newConfig
    ) throws IOException {
        if (active) {
            throw new IllegalStateException(
                    "A producer evidence session is already active"
            );
        }
        if (newRouteSessionId <= 0L) {
            throw new IllegalArgumentException(
                    "Route session ID must be positive"
            );
        }

        String validated =
                MotionSessionId.validate(rawEvidenceSessionId);
        HumanMotionConfig resolvedConfig =
                newConfig == null
                        ? HumanMotionConfig.defaultConfig(
                        newRouteSessionId
                )
                        : newConfig;

        ProducerEvidenceRecorder newRecorder =
                new ProducerEvidenceRecorder(
                        producerRoot,
                        validated
                );

        routeSessionId = newRouteSessionId;
        evidenceSessionId = validated;
        config = resolvedConfig;
        model = new HumanMotionModel(resolvedConfig);
        recorder = newRecorder;
        active = true;
        routePaused = false;
        errorCodes.clear();
    }

    public synchronized void onRouteSample(RouteSample sample) {
        if (!active || sample == null) {
            return;
        }
        if (sample.getSessionId() != routeSessionId) {
            return;
        }

        long nowNs = sample.getElapsedRealtimeNanos();
        RouteSessionState state = sample.getState();

        if (state == RouteSessionState.PAUSED) {
            if (!routePaused) {
                model.pause(nowNs);
                routePaused = true;
            }
            captureModelError();
            return;
        }

        if (state == RouteSessionState.PLAYING) {
            if (routePaused) {
                model.resume(nowNs);
                routePaused = false;
            }

            List<SyntheticStepEvent> events =
                    model.onSample(
                            nowNs,
                            sample.getOutputSpeedMps()
                    );
            for (SyntheticStepEvent event : events) {
                if (!recorder.appendSyntheticStep(event)) {
                    errorCodes.add("TRACE_WRITE_FAILURE");
                }
            }
            captureModelError();
            return;
        }

        if (state == RouteSessionState.STOPPED
                || state == RouteSessionState.FINISHED
                || state == RouteSessionState.ERROR) {
            captureModelError();
        }
    }

    public synchronized void onLocationPublished(
            String provider,
            long publicationElapsedNs,
            long locationElapsedNs,
            double latitude,
            double longitude,
            double speedMps,
            double bearingDeg,
            double accuracyM
    ) {
        if (!active || recorder == null) {
            return;
        }

        boolean accepted = recorder.appendLocation(
                provider,
                publicationElapsedNs,
                locationElapsedNs,
                latitude,
                longitude,
                speedMps,
                bearingDeg,
                accuracyM
        );
        if (!accepted) {
            errorCodes.add("TRACE_WRITE_FAILURE");
        }
    }

    public synchronized ProducerEvidenceRecorder.CloseResult finish(
            ProducerSessionMetadata metadata
    ) {
        if (!active || recorder == null) {
            return null;
        }

        ProducerEvidenceRecorder.CloseResult result =
                recorder.close(metadata);
        if (result == null || !result.isSuccess()) {
            errorCodes.add(
                    result == null
                            ? "TRACE_CLOSE_FAILURE"
                            : result.getErrorCode()
            );
        }

        active = false;
        routePaused = false;
        recorder = null;
        return result;
    }

    public synchronized SyntheticMotionStatus snapshot() {
        long count = model == null
                ? 0L
                : model.snapshot().getStepCount();

        return new SyntheticMotionStatus(
                active,
                routeSessionId,
                evidenceSessionId,
                count,
                new ArrayList<>(errorCodes)
        );
    }

    public synchronized HumanMotionConfig getConfig() {
        return config;
    }

    public synchronized String getEvidenceSessionId() {
        return evidenceSessionId;
    }

    public synchronized long getRouteSessionId() {
        return routeSessionId;
    }

    private void captureModelError() {
        if (model == null) {
            return;
        }
        String code = model.snapshot().getErrorCode();
        if (code != null && !code.isEmpty()) {
            errorCodes.add(code);
        }
    }
}
