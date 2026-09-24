package com.zcshou.v2finjector;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InjectionService extends Service {
    public static final String ACTION_BASELINE =
            "com.zcshou.v2finjector.action.BASELINE";
    public static final String ACTION_RUN =
            "com.zcshou.v2finjector.action.RUN";
    public static final String ACTION_STOP =
            "com.zcshou.v2finjector.action.STOP";

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_CADENCE_SPM = "cadence_spm";
    public static final String EXTRA_DURATION_S = "duration_s";
    public static final String EXTRA_COUNTER_BASELINE = "counter_baseline";

    private static final String TAG = "V2fSensorInjector";
    private static final String DETECTOR_NAME = "V2F Virtual Step Detector";
    private static final String COUNTER_NAME = "V2F Virtual Step Counter";

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private SensorManager sensorManager;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Build.IS_DEBUGGABLE) {
            Log.e(TAG, "Refusing synthetic sensor injection on non-debuggable build");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_BASELINE:
                injectBaseline(intent);
                break;
            case ACTION_RUN:
                startRun(intent);
                break;
            case ACTION_STOP:
                stopRequested.set(true);
                break;
            default:
                Log.e(TAG, "Unknown action " + intent.getAction());
        }
        return START_NOT_STICKY;
    }

    private void injectBaseline(Intent intent) {
        int baseline = intent.getIntExtra(EXTRA_COUNTER_BASELINE, 10_000);
        Sensor counter = findLabSensor(Sensor.TYPE_STEP_COUNTER, COUNTER_NAME);
        if (counter == null) {
            Log.e(TAG, "Counter sensor missing");
            return;
        }
        if (!beginInjection()) {
            return;
        }
        try {
            long now = SystemClock.elapsedRealtimeNanos();
            boolean ok = sensorManager.injectSensorData(
                    counter,
                    new float[]{(float) baseline},
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                    now);
            Log.i(TAG, "BASELINE value=" + baseline + " result=" + ok);
        } finally {
            endInjection();
        }
    }

    private synchronized void startRun(Intent intent) {
        if (worker != null && worker.isAlive()) {
            Log.e(TAG, "A run is already active");
            return;
        }
        final String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        final int cadenceSpm = intent.getIntExtra(EXTRA_CADENCE_SPM, 165);
        final int durationS = intent.getIntExtra(EXTRA_DURATION_S, 90);
        final int baseline = intent.getIntExtra(EXTRA_COUNTER_BASELINE, 10_000);
        if (sessionId == null || sessionId.trim().isEmpty()
                || (cadenceSpm != 150 && cadenceSpm != 165 && cadenceSpm != 180)
                || durationS != 90 || baseline < 0) {
            Log.e(TAG, "Invalid BCT-1 run arguments");
            return;
        }

        stopRequested.set(false);
        worker = new Thread(
                () -> runTimeline(sessionId, cadenceSpm, durationS, baseline),
                "V2fBct1-" + sessionId);
        worker.start();
    }

    private void runTimeline(String sessionId, int cadenceSpm, int durationS, int baseline) {
        Sensor detector = findLabSensor(Sensor.TYPE_STEP_DETECTOR, DETECTOR_NAME);
        Sensor counter = findLabSensor(Sensor.TYPE_STEP_COUNTER, COUNTER_NAME);
        if (detector == null || counter == null) {
            Log.e(TAG, "LAB_SENSOR_MISSING session=" + sessionId);
            return;
        }
        if (!beginInjection()) {
            Log.e(TAG, "INJECTION_INIT_FAILED session=" + sessionId);
            return;
        }

        boolean failed = false;
        long startNs = SystemClock.elapsedRealtimeNanos() + 500_000_000L;
        int index = 1;
        Log.i(TAG, "RUN_START session=" + sessionId + " cadence=" + cadenceSpm);
        try {
            while (!stopRequested.get()) {
                long offsetNs = Timeline.offsetNs(index, cadenceSpm);
                if (offsetNs >= durationS * 1_000_000_000L) {
                    break;
                }
                long eventNs = startNs + offsetNs;
                sleepUntil(eventNs);

                boolean detectorOk = sensorManager.injectSensorData(
                        detector,
                        new float[]{1.0f},
                        SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                        eventNs);
                boolean counterOk = sensorManager.injectSensorData(
                        counter,
                        new float[]{(float) (baseline + index)},
                        SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                        eventNs);
                if (!detectorOk || !counterOk) {
                    failed = true;
                    Log.e(TAG, "INJECT_FAILED session=" + sessionId + " step=" + index);
                    break;
                }
                index++;
            }
        } catch (RuntimeException e) {
            failed = true;
            Log.e(TAG, "RUN_ERROR session=" + sessionId, e);
        } finally {
            endInjection();
            Log.i(TAG, "RUN_END session=" + sessionId
                    + " emitted=" + (index - 1)
                    + " failed=" + failed
                    + " stopped=" + stopRequested.get());
        }
    }

    private boolean beginInjection() {
        try {
            boolean ok = sensorManager.initDataInjection(
                    true, SensorManager.HAL_BYPASS_REPLAY_DATA_INJECTION);
            Log.i(TAG, "HAL_BYPASS_REPLAY_DATA_INJECTION init=" + ok);
            return ok;
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot initialize data injection", e);
            return false;
        }
    }

    private void endInjection() {
        try {
            sensorManager.initDataInjection(
                    false, SensorManager.HAL_BYPASS_REPLAY_DATA_INJECTION);
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot close data injection client", e);
        }
    }

    private Sensor findLabSensor(int type, String expectedName) {
        List<Sensor> sensors = sensorManager.getSensorList(type);
        for (Sensor sensor : sensors) {
            if (expectedName.equals(sensor.getName())) {
                return sensor;
            }
        }
        return null;
    }

    private static void sleepUntil(long eventNs) {
        while (true) {
            long remainingNs = eventNs - SystemClock.elapsedRealtimeNanos();
            if (remainingNs <= 0) {
                return;
            }
            long millis = remainingNs / 1_000_000L;
            int nanos = (int) (remainingNs % 1_000_000L);
            try {
                Thread.sleep(millis, nanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void onDestroy() {
        stopRequested.set(true);
        endInjection();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
