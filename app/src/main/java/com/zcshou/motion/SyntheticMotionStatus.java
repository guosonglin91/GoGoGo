package com.zcshou.motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SyntheticMotionStatus {
    private final boolean active;
    private final long routeSessionId;
    private final String evidenceSessionId;
    private final long syntheticStepCount;
    private final List<String> errorCodes;

    SyntheticMotionStatus(
            boolean active,
            long routeSessionId,
            String evidenceSessionId,
            long syntheticStepCount,
            List<String> errorCodes
    ) {
        this.active = active;
        this.routeSessionId = routeSessionId;
        this.evidenceSessionId = evidenceSessionId == null
                ? ""
                : evidenceSessionId;
        this.syntheticStepCount = syntheticStepCount;
        this.errorCodes = Collections.unmodifiableList(
                new ArrayList<>(errorCodes)
        );
    }

    public boolean isActive() {
        return active;
    }

    public long getRouteSessionId() {
        return routeSessionId;
    }

    public String getEvidenceSessionId() {
        return evidenceSessionId;
    }

    public long getSyntheticStepCount() {
        return syntheticStepCount;
    }

    public List<String> getErrorCodes() {
        return errorCodes;
    }
}
