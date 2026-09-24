package com.zcshou.runnerprobe;

public final class PermissionGate {
    private PermissionGate() {
    }

    public static Result evaluate(
            int apiLevel,
            boolean fineLocationGranted,
            boolean coarseLocationGranted,
            boolean activityRecognitionGranted
    ) {
        boolean needsActivity = apiLevel >= 29 && !activityRecognitionGranted;
        return new Result(
                fineLocationGranted || coarseLocationGranted,
                !needsActivity,
                needsActivity
        );
    }

    public static final class Result {
        private final boolean canRecordLocation;
        private final boolean canRecordMotionSensors;
        private final boolean needsActivityRecognition;

        Result(
                boolean canRecordLocation,
                boolean canRecordMotionSensors,
                boolean needsActivityRecognition
        ) {
            this.canRecordLocation = canRecordLocation;
            this.canRecordMotionSensors = canRecordMotionSensors;
            this.needsActivityRecognition = needsActivityRecognition;
        }

        public boolean canRecordLocation() { return canRecordLocation; }
        public boolean canRecordMotionSensors() { return canRecordMotionSensors; }
        public boolean needsActivityRecognition() { return needsActivityRecognition; }
    }
}
