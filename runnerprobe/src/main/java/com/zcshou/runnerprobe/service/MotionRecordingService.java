package com.zcshou.runnerprobe.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.zcshou.runnerprobe.BuildConfig;
import com.zcshou.runnerprobe.PermissionGate;
import com.zcshou.runnerprobe.domain.CadenceSnapshot;
import com.zcshou.runnerprobe.domain.CadenceState;
import com.zcshou.runnerprobe.domain.CadenceTracker;
import com.zcshou.runnerprobe.domain.StepCounterTracker;
import com.zcshou.runnerprobe.evidence.EvidenceValidationException;
import com.zcshou.runnerprobe.evidence.SessionId;
import com.zcshou.runnerprobe.session.SensorSummaryAccumulator;
import com.zcshou.runnerprobe.session.SessionFileStore;
import com.zcshou.runnerprobe.session.SessionMetadata;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MotionRecordingService extends Service
        implements SensorEventListener, LocationListener {

    public static final String ACTION_ARM_SESSION =
            "com.zcshou.runnerprobe.action.ARM_SESSION";
    public static final String ACTION_BEGIN_RECORDING =
            "com.zcshou.runnerprobe.action.BEGIN_RECORDING";
    public static final String ACTION_STOP_SESSION =
            "com.zcshou.runnerprobe.action.STOP_SESSION";
    public static final String ACTION_MARK_FOREGROUND =
            "com.zcshou.runnerprobe.action.MARK_FOREGROUND";
    public static final String ACTION_MARK_BACKGROUND =
            "com.zcshou.runnerprobe.action.MARK_BACKGROUND";
    public static final String EXTRA_SESSION_ID = "session_id";

    private static final String CHANNEL_ID = "runnerprobe_recording";
    private static final int NOTIFICATION_ID = 2201;

    private SensorManager sensorManager;
    private LocationManager locationManager;
    private Sensor detectorSensor;
    private Sensor counterSensor;
    private Sensor accelSensor;
    private Sensor gyroSensor;

    private CadenceTracker cadenceTracker = new CadenceTracker();
    private StepCounterTracker counterTracker = new StepCounterTracker();
    private SensorSummaryAccumulator accelSummary = new SensorSummaryAccumulator();
    private SensorSummaryAccumulator gyroSummary = new SensorSummaryAccumulator();

    private SessionFileStore store;
    private String sessionId = "";
    private RecordingState state = RecordingState.IDLE;
    private RecordingError lastError = RecordingError.NONE;
    private final Set<String> errorCodes = new LinkedHashSet<>();
    private final List<String> lifecycleEvents = new ArrayList<>();

    private long sessionStartElapsedNs = -1L;
    private long officialStartElapsedNs = -1L;
    private long officialEndElapsedNs = -1L;
    private long detectorEventCount;
    private long lastDetectorArrivalNs = -1L;
    private long lastCounterAbsolute = -1L;
    private boolean counterBaselineSeen;
    private boolean screenReceiverRegistered;

    private String lifecycleState = "IDLE";
    private String latestProvider = "";
    private double latestLatitude = Double.NaN;
    private double latestLongitude = Double.NaN;
    private double latestSpeed = Double.NaN;
    private double latestBearing = Double.NaN;
    private boolean latestMock;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                markLifecycle("SCREEN_OFF");
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                markLifecycle("SCREEN_ON");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createNotificationChannel();
        publishSnapshot();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_ARM_SESSION.equals(action)) {
            armSession(intent.getStringExtra(EXTRA_SESSION_ID));
        } else if (ACTION_BEGIN_RECORDING.equals(action)) {
            beginRecording();
        } else if (ACTION_STOP_SESSION.equals(action)) {
            stopSession(false);
        } else if (ACTION_MARK_FOREGROUND.equals(action)) {
            markLifecycle("FOREGROUND");
        } else if (ACTION_MARK_BACKGROUND.equals(action)) {
            markLifecycle("BACKGROUND");
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void armSession(String rawSessionId) {
        if (isActive()) {
            addError(RecordingError.NOT_READY, "SESSION_ALREADY_ACTIVE");
            return;
        }

        resetSessionState();

        try {
            sessionId = SessionId.validate(rawSessionId);
        } catch (EvidenceValidationException e) {
            failStart(RecordingError.SESSION_START_FAILURE, e.getCode());
            return;
        }

        boolean fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean activityGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || hasPermission(Manifest.permission.ACTIVITY_RECOGNITION);

        PermissionGate.Result permission = PermissionGate.evaluate(
                Build.VERSION.SDK_INT,
                fine,
                coarse,
                activityGranted
        );

        startForegroundForCurrentPermissions(permission.canRecordLocation());

        if (!permission.canRecordMotionSensors()) {
            failStart(RecordingError.PERMISSION_DENIED, "PERMISSION_DENIED");
            return;
        }

        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos();
        state = RecordingState.ARMING;
        markLifecycle("ARMING");

        File base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) {
            base = getFilesDir();
        }
        File consumerRoot = new File(base, "v2e/consumer");

        try {
            store = new SessionFileStore(consumerRoot, sessionId);
        } catch (Exception e) {
            failStart(RecordingError.SESSION_START_FAILURE, "TRACE_WRITE_FAILURE");
            return;
        }

        detectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        counterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (detectorSensor == null) {
            addError(RecordingError.SENSOR_ABSENT, "STEP_DETECTOR_ABSENT");
        }
        if (counterSensor == null) {
            addError(RecordingError.SENSOR_ABSENT, "STEP_COUNTER_ABSENT");
            counterBaselineSeen = true;
        }

        if (detectorSensor != null) {
            sensorManager.registerListener(
                    this,
                    detectorSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    0
            );
        }
        if (counterSensor != null) {
            sensorManager.registerListener(
                    this,
                    counterSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    0
            );
        }
        if (accelSensor != null) {
            sensorManager.registerListener(
                    this,
                    accelSensor,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }
        if (gyroSensor != null) {
            sensorManager.registerListener(
                    this,
                    gyroSensor,
                    SensorManager.SENSOR_DELAY_GAME
            );
        }

        if (permission.canRecordLocation()) {
            registerLocationProvider(LocationManager.GPS_PROVIDER);
            registerLocationProvider(LocationManager.NETWORK_PROVIDER);
        } else {
            errorCodes.add("LOCATION_PERMISSION_UNAVAILABLE");
        }

        registerScreenReceiver();
        updateReadyState();
        updateNotification();
    }

    private void beginRecording() {
        if (state != RecordingState.READY) {
            lastError = RecordingError.NOT_READY;
            errorCodes.add("NOT_READY");
            publishSnapshot();
            return;
        }

        officialStartElapsedNs = SystemClock.elapsedRealtimeNanos();
        officialEndElapsedNs = -1L;
        cadenceTracker = new CadenceTracker();
        detectorEventCount = 0L;
        lastDetectorArrivalNs = -1L;
        state = RecordingState.RECORDING;
        markLifecycle("RECORDING");
        updateNotification();
    }

    private void stopSession(boolean interrupted) {
        if (store == null) {
            state = interrupted ? RecordingState.ERROR : RecordingState.COMPLETE;
            publishSnapshot();
            stopForeground(true);
            stopSelf();
            return;
        }

        state = RecordingState.FINALIZING;
        publishSnapshot();

        if (interrupted) {
            addError(RecordingError.SESSION_INTERRUPTED, "SESSION_INTERRUPTED");
        }

        if (officialStartElapsedNs >= 0L && officialEndElapsedNs < 0L) {
            officialEndElapsedNs = SystemClock.elapsedRealtimeNanos();
        }

        unregisterInputs();

        SensorSummaryAccumulator.Summary accel = accelSummary.flush();
        SensorSummaryAccumulator.Summary gyro = gyroSummary.flush();
        if (accel != null) {
            store.appendAccelSummary(accel);
        }
        if (gyro != null) {
            store.appendGyroSummary(gyro);
        }

        if (detectorSensor != null
                && officialStartElapsedNs >= 0L
                && detectorEventCount == 0L) {
            addError(
                    RecordingError.SENSOR_PRESENT_NO_EVENTS,
                    "SENSOR_PRESENT_NO_EVENTS"
            );
        }

        SessionMetadata metadata = buildMetadata();
        SessionFileStore.CloseResult result =
                metadata == null ? null : store.close(metadata);

        if (result == null || !result.isSuccess()) {
            String code = result == null
                    ? "TRACE_CLOSE_FAILURE"
                    : result.getErrorCode();
            if ("TRACE_CLOSE_TIMEOUT".equals(code)) {
                lastError = RecordingError.TRACE_CLOSE_TIMEOUT;
            } else if ("TRACE_WRITE_FAILURE".equals(code)) {
                lastError = RecordingError.TRACE_WRITE_FAILURE;
            } else {
                lastError = RecordingError.TRACE_CLOSE_FAILURE;
            }
            if (code != null) {
                errorCodes.add(code);
            }
            state = RecordingState.ERROR;
        } else {
            state = RecordingState.COMPLETE;
        }

        store = null;
        publishSnapshot();
        stopForeground(true);
        stopSelf();
    }

    private SessionMetadata buildMetadata() {
        try {
            SessionMetadata.Builder builder = new SessionMetadata.Builder(sessionId)
                    .elapsedRange(
                            sessionStartElapsedNs,
                            SystemClock.elapsedRealtimeNanos()
                    )
                    .officialRange(
                            officialStartElapsedNs,
                            officialEndElapsedNs
                    )
                    .appVersion(BuildConfig.VERSION_NAME)
                    .sourceCommitSha(BuildConfig.SOURCE_COMMIT_SHA)
                    .device(
                            Build.MODEL,
                            Build.VERSION.RELEASE,
                            Build.VERSION.SDK_INT
                    )
                    .bootMarker(readBootMarker())
                    .permissionState(permissionSummary())
                    .detector(
                            sensorName(detectorSensor),
                            sensorVendor(detectorSensor)
                    )
                    .counter(
                            sensorName(counterSensor),
                            sensorVendor(counterSensor)
                    );

            for (String event : lifecycleEvents) {
                builder.addLifecycleEvent(event);
            }
            for (String code : errorCodes) {
                builder.addErrorCode(code);
            }
            return builder.build();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || store == null) {
            return;
        }

        long arrivalNs = SystemClock.elapsedRealtimeNanos();
        int type = event.sensor.getType();

        if (type == Sensor.TYPE_STEP_DETECTOR) {
            handleStepDetector(event, arrivalNs);
        } else if (type == Sensor.TYPE_STEP_COUNTER) {
            handleStepCounter(event, arrivalNs);
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            handleSummarySample(event, accelSummary, true);
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            handleSummarySample(event, gyroSummary, false);
        }
    }

    private void handleStepDetector(SensorEvent event, long arrivalNs) {
        if (event.values.length == 0 || !isFinite(event.values[0])) {
            addError(
                    RecordingError.INVALID_SENSOR_VALUE,
                    "INVALID_STEP_DETECTOR_VALUE"
            );
            return;
        }

        store.appendStepDetector(event.timestamp, arrivalNs, event.values[0]);

        if (state != RecordingState.RECORDING) {
            return;
        }

        try {
            CadenceSnapshot cadence = cadenceTracker.onStep(event.timestamp);
            detectorEventCount++;
            lastDetectorArrivalNs = arrivalNs;
            publishSnapshot(cadence);
        } catch (IllegalArgumentException e) {
            addError(
                    RecordingError.INVALID_SENSOR_VALUE,
                    "NON_MONOTONIC_STEP_TIME"
            );
        }
    }

    private void handleStepCounter(SensorEvent event, long arrivalNs) {
        if (event.values.length == 0
                || !isFinite(event.values[0])
                || event.values[0] < 0f) {
            addError(
                    RecordingError.INVALID_SENSOR_VALUE,
                    "INVALID_STEP_COUNTER_VALUE"
            );
            return;
        }

        long absolute = Math.round(event.values[0]);
        try {
            StepCounterTracker.Result result =
                    counterTracker.onCounter(event.timestamp, absolute);
            lastCounterAbsolute = absolute;
            counterBaselineSeen = true;
            store.appendStepCounter(
                    event.timestamp,
                    arrivalNs,
                    absolute,
                    result.getSessionDelta(),
                    result.isDiscontinuity()
            );
            if (result.isDiscontinuity()) {
                addError(
                        RecordingError.COUNTER_DISCONTINUITY,
                        "COUNTER_DISCONTINUITY"
                );
            }
            updateReadyState();
            publishSnapshot();
        } catch (IllegalArgumentException e) {
            addError(
                    RecordingError.INVALID_SENSOR_VALUE,
                    "INVALID_STEP_COUNTER_EVENT"
            );
        }
    }

    private void handleSummarySample(
            SensorEvent event,
            SensorSummaryAccumulator accumulator,
            boolean accelerometer
    ) {
        if (event.values.length < 3) {
            return;
        }

        SensorSummaryAccumulator.Summary summary = accumulator.onSample(
                event.timestamp,
                event.values[0],
                event.values[1],
                event.values[2]
        );
        if (summary == null) {
            return;
        }

        if (accelerometer) {
            store.appendAccelSummary(summary);
        } else {
            store.appendGyroSummary(summary);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null || store == null) {
            return;
        }

        long arrivalNs = SystemClock.elapsedRealtimeNanos();
        latestProvider = location.getProvider() == null
                ? ""
                : location.getProvider();
        latestLatitude = location.getLatitude();
        latestLongitude = location.getLongitude();
        latestSpeed = location.hasSpeed()
                ? location.getSpeed()
                : Double.NaN;
        latestBearing = location.hasBearing()
                ? location.getBearing()
                : Double.NaN;
        latestMock = location.isFromMockProvider();

        store.appendLocation(
                latestProvider,
                location.getElapsedRealtimeNanos(),
                arrivalNs,
                location.getTime(),
                latestLatitude,
                latestLongitude,
                location.hasSpeed() ? (double) location.getSpeed() : null,
                location.hasBearing() ? (double) location.getBearing() : null,
                location.hasAccuracy() ? (double) location.getAccuracy() : null,
                latestMock
        );
        publishSnapshot();
    }

    @Override
    public void onProviderDisabled(String provider) {
        errorCodes.add("PROVIDER_DISABLED_" + provider);
        publishSnapshot();
    }

    @Override
    public void onProviderEnabled(String provider) {
        publishSnapshot();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private void registerLocationProvider(String provider) {
        try {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(
                        provider,
                        250L,
                        0f,
                        this
                );
            } else {
                errorCodes.add("PROVIDER_UNAVAILABLE_" + provider);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            errorCodes.add("PROVIDER_UNAVAILABLE_" + provider);
        }
    }

    private void unregisterInputs() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
        }
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            screenReceiverRegistered = false;
        }
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
        screenReceiverRegistered = true;
    }

    private void updateReadyState() {
        if (state != RecordingState.ARMING) {
            return;
        }
        if (counterSensor == null || counterBaselineSeen) {
            state = RecordingState.READY;
            markLifecycle("READY");
            updateNotification();
        }
    }

    private void markLifecycle(String name) {
        lifecycleState = name;
        long now = SystemClock.elapsedRealtimeNanos();
        lifecycleEvents.add(name + "@" + now);
        publishSnapshot();
    }

    private void addError(RecordingError error, String code) {
        lastError = error;
        if (code != null && !code.isEmpty()) {
            errorCodes.add(code);
        }
        publishSnapshot();
    }

    private void failStart(RecordingError error, String code) {
        addError(error, code);
        state = RecordingState.ERROR;
        publishSnapshot();
        stopForeground(true);
        stopSelf();
    }

    private void publishSnapshot() {
        CadenceSnapshot cadence;
        try {
            cadence = cadenceTracker.snapshot(
                    SystemClock.elapsedRealtimeNanos()
            );
        } catch (IllegalArgumentException e) {
            cadence = null;
        }
        publishSnapshot(cadence);
    }

    private void publishSnapshot(CadenceSnapshot cadence) {
        long now = SystemClock.elapsedRealtimeNanos();
        long lastAge = lastDetectorArrivalNs < 0L
                ? Long.MAX_VALUE
                : Math.max(0L, now - lastDetectorArrivalNs);

        CadenceState cadenceState = cadence == null
                ? CadenceState.WARMING_UP
                : cadence.getState();
        double cadence5 = cadence == null
                ? Double.NaN
                : cadence.getCadence5sSpm();
        double cadence15 = cadence == null
                ? Double.NaN
                : cadence.getCadence15sSpm();

        RecordingSnapshot snapshot = new RecordingSnapshot.Builder()
                .sessionId(sessionId)
                .state(state)
                .lastError(lastError)
                .cadence(cadenceState, cadence5, cadence15)
                .detectorEventCount(detectorEventCount)
                .counter(
                        lastCounterAbsolute,
                        counterTracker.getSessionDelta()
                )
                .lastStepAgeNs(lastAge)
                .sensorPresence(
                        detectorSensor != null,
                        counterSensor != null
                )
                .location(
                        latestProvider,
                        latestLatitude,
                        latestLongitude,
                        latestSpeed,
                        latestBearing,
                        latestMock
                )
                .lifecycleState(lifecycleState)
                .build();

        RecordingSnapshotBus.publish(snapshot);
    }

    private void startForegroundForCurrentPermissions(
            boolean locationPermissionAvailable
    ) {
        Notification notification = buildNotification("Arming");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = locationPermissionAvailable
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        NOTIFICATION_SERVICE
                );
        manager.notify(
                NOTIFICATION_ID,
                buildNotification(state.name())
        );
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("RunnerProbe V2-E")
                .setContentText(text)
                .setOngoing(isActive())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "RunnerProbe recording",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        NOTIFICATION_SERVICE
                );
        manager.createNotificationChannel(channel);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String permissionSummary() {
        return "fine="
                + hasPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        )
                + ";coarse="
                + hasPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
        )
                + ";activity="
                + (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        || hasPermission(
                        Manifest.permission.ACTIVITY_RECOGNITION
                )
        );
    }

    private String readBootMarker() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader(
                        "/proc/sys/kernel/random/boot_id"
                ))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
        } catch (Exception ignored) {
        }

        long bootEpochApproxMs =
                System.currentTimeMillis()
                        - SystemClock.elapsedRealtime();
        return Build.FINGERPRINT + ":" + bootEpochApproxMs;
    }

    private static String sensorName(Sensor sensor) {
        return sensor == null ? "" : sensor.getName();
    }

    private static String sensorVendor(Sensor sensor) {
        return sensor == null ? "" : sensor.getVendor();
    }

    private boolean isActive() {
        return state == RecordingState.ARMING
                || state == RecordingState.READY
                || state == RecordingState.RECORDING
                || state == RecordingState.FINALIZING;
    }

    private void resetSessionState() {
        unregisterInputs();
        cadenceTracker = new CadenceTracker();
        counterTracker = new StepCounterTracker();
        accelSummary = new SensorSummaryAccumulator();
        gyroSummary = new SensorSummaryAccumulator();

        store = null;
        sessionId = "";
        state = RecordingState.IDLE;
        lastError = RecordingError.NONE;
        errorCodes.clear();
        lifecycleEvents.clear();

        sessionStartElapsedNs = -1L;
        officialStartElapsedNs = -1L;
        officialEndElapsedNs = -1L;
        detectorEventCount = 0L;
        lastDetectorArrivalNs = -1L;
        lastCounterAbsolute = -1L;
        counterBaselineSeen = false;

        lifecycleState = "IDLE";
        latestProvider = "";
        latestLatitude = Double.NaN;
        latestLongitude = Double.NaN;
        latestSpeed = Double.NaN;
        latestBearing = Double.NaN;
        latestMock = false;

        detectorSensor = null;
        counterSensor = null;
        accelSensor = null;
        gyroSensor = null;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value)
                && !Float.isInfinite(value);
    }

    @Override
    public void onDestroy() {
        if (store != null) {
            lastError = RecordingError.SESSION_INTERRUPTED;
            errorCodes.add("SESSION_INTERRUPTED");
            state = RecordingState.ERROR;
            publishSnapshot();
        }
        unregisterInputs();
        super.onDestroy();
    }
}
