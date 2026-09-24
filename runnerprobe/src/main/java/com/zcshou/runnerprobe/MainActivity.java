package com.zcshou.runnerprobe;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.zcshou.runnerprobe.domain.CadenceState;
import com.zcshou.runnerprobe.evidence.EvidenceSchema;
import com.zcshou.runnerprobe.evidence.EvidenceValidationException;
import com.zcshou.runnerprobe.evidence.SessionId;
import com.zcshou.runnerprobe.service.MotionRecordingService;
import com.zcshou.runnerprobe.service.RecordingSnapshot;
import com.zcshou.runnerprobe.service.RecordingSnapshotBus;
import com.zcshou.runnerprobe.service.RecordingState;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity
        implements RecordingSnapshotBus.Listener {

    private static final int REQUEST_PERMISSIONS = 4101;

    private EditText sessionIdInput;
    private TextView statusView;
    private Button armButton;
    private Button beginButton;
    private Button stopButton;

    private String pendingSessionId;
    private RecordingSnapshot latestSnapshot =
            new com.zcshou.runnerprobe.service.RecordingSnapshot.Builder().build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sessionIdInput = findViewById(R.id.edit_session_id);
        statusView = findViewById(R.id.text_status);
        armButton = findViewById(R.id.button_arm);
        beginButton = findViewById(R.id.button_begin);
        stopButton = findViewById(R.id.button_stop);

        sessionIdInput.setText(defaultSessionId());

        armButton.setOnClickListener(v -> requestPermissionsThenArm());
        beginButton.setOnClickListener(v -> beginOfficialRecording());
        stopButton.setOnClickListener(v -> stopSession());

        render(RecordingSnapshotBus.latest());
    }

    @Override
    protected void onStart() {
        super.onStart();
        RecordingSnapshotBus.addListener(this);
        sendLifecycleMarker(MotionRecordingService.ACTION_MARK_FOREGROUND);
    }

    @Override
    protected void onStop() {
        sendLifecycleMarker(MotionRecordingService.ACTION_MARK_BACKGROUND);
        RecordingSnapshotBus.removeListener(this);
        super.onStop();
    }

    private void requestPermissionsThenArm() {
        String raw = sessionIdInput.getText().toString().trim();
        try {
            pendingSessionId = SessionId.validate(raw);
        } catch (EvidenceValidationException e) {
            Toast.makeText(
                    this,
                    "Session ID 无效：" + e.getCode(),
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        List<String> missing = new ArrayList<>();
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            missing.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }

        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    missing.toArray(new String[0]),
                    REQUEST_PERMISSIONS
            );
            return;
        }

        armIfPermitted();
    }

    private void armIfPermitted() {
        boolean fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean activity = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || hasPermission(Manifest.permission.ACTIVITY_RECOGNITION);

        PermissionGate.Result result = PermissionGate.evaluate(
                Build.VERSION.SDK_INT,
                fine,
                coarse,
                activity
        );

        if (!result.canRecordMotionSensors()) {
            Toast.makeText(
                    this,
                    "需要“身体活动/Physical activity”权限才能记录真实步态传感器。",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        Intent intent = new Intent(this, MotionRecordingService.class);
        intent.setAction(MotionRecordingService.ACTION_ARM_SESSION);
        intent.putExtra(
                MotionRecordingService.EXTRA_SESSION_ID,
                pendingSessionId
        );
        ContextCompat.startForegroundService(this, intent);

        if (!result.canRecordLocation()) {
            Toast.makeText(
                    this,
                    "定位权限未授权：本次可记录运动传感器，但 Gate-L 不可用。",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void beginOfficialRecording() {
        RecordingSnapshot snapshot = RecordingSnapshotBus.latest();
        if (snapshot.getState() != RecordingState.READY) {
            Toast.makeText(
                    this,
                    "当前尚未 READY。若存在 Step Counter，需要先获得基线回调。",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        Intent intent = new Intent(this, MotionRecordingService.class);
        intent.setAction(MotionRecordingService.ACTION_BEGIN_RECORDING);
        startService(intent);
    }

    private void stopSession() {
        if (!isSessionActive(RecordingSnapshotBus.latest().getState())) {
            return;
        }
        Intent intent = new Intent(this, MotionRecordingService.class);
        intent.setAction(MotionRecordingService.ACTION_STOP_SESSION);
        startService(intent);
    }

    private void sendLifecycleMarker(String action) {
        RecordingState state = RecordingSnapshotBus.latest().getState();
        if (!isSessionActive(state)) {
            return;
        }
        Intent intent = new Intent(this, MotionRecordingService.class);
        intent.setAction(action);
        startService(intent);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isSessionActive(RecordingState state) {
        return state == RecordingState.ARMING
                || state == RecordingState.READY
                || state == RecordingState.RECORDING
                || state == RecordingState.FINALIZING;
    }

    @Override
    public void onRecordingSnapshot(RecordingSnapshot snapshot) {
        runOnUiThread(() -> render(snapshot));
    }

    private void render(RecordingSnapshot snapshot) {
        latestSnapshot = snapshot;

        StringBuilder out = new StringBuilder();
        out.append("Application: ")
                .append(BuildContract.APPLICATION_ID)
                .append('\n');
        out.append("Evidence schema: ")
                .append(EvidenceSchema.VERSION)
                .append('\n');
        out.append("Session: ")
                .append(emptyAsDash(snapshot.getSessionId()))
                .append('\n');
        out.append("Recording state: ")
                .append(snapshot.getState())
                .append('\n');
        out.append("Cadence state: ")
                .append(snapshot.getCadenceState())
                .append('\n');
        out.append("Cadence 5 s: ")
                .append(formatDouble(snapshot.getCadence5sSpm()))
                .append(" spm\n");
        out.append("Cadence 15 s: ")
                .append(formatDouble(snapshot.getCadence15sSpm()))
                .append(" spm\n");
        out.append("Detector events: ")
                .append(snapshot.getDetectorEventCount())
                .append('\n');
        out.append("Counter absolute: ")
                .append(snapshot.getCounterAbsolute() < 0
                        ? "N/A"
                        : snapshot.getCounterAbsolute())
                .append('\n');
        out.append("Counter delta: ")
                .append(snapshot.getCounterDelta())
                .append('\n');
        out.append("Last step age: ")
                .append(formatAge(snapshot.getLastStepAgeNs()))
                .append('\n');
        out.append("Step Detector present: ")
                .append(snapshot.isDetectorPresent())
                .append('\n');
        out.append("Step Counter present: ")
                .append(snapshot.isCounterPresent())
                .append('\n');
        out.append("Provider: ")
                .append(emptyAsDash(snapshot.getProvider()))
                .append('\n');
        out.append("Latitude: ")
                .append(formatDouble(snapshot.getLatitude()))
                .append('\n');
        out.append("Longitude: ")
                .append(formatDouble(snapshot.getLongitude()))
                .append('\n');
        out.append("Speed: ")
                .append(formatDouble(snapshot.getSpeedMps()))
                .append(" m/s\n");
        out.append("Bearing: ")
                .append(formatDouble(snapshot.getBearingDeg()))
                .append(" deg\n");
        out.append("isMock: ")
                .append(snapshot.isMock())
                .append('\n');
        out.append("Lifecycle: ")
                .append(emptyAsDash(snapshot.getLifecycleState()))
                .append('\n');
        out.append("Last error: ")
                .append(snapshot.getLastError());

        statusView.setText(out.toString());

        RecordingState state = snapshot.getState();
        armButton.setEnabled(!isSessionActive(state));
        beginButton.setEnabled(state == RecordingState.READY);
        stopButton.setEnabled(
                state == RecordingState.ARMING
                        || state == RecordingState.READY
                        || state == RecordingState.RECORDING
        );
        sessionIdInput.setEnabled(!isSessionActive(state));
    }

    private static String defaultSessionId() {
        return new SimpleDateFormat(
                "'v2e_'yyyyMMdd_HHmmss",
                Locale.US
        ).format(new Date());
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "N/A";
        }
        return String.format(Locale.US, "%.3f", value);
    }

    private static String formatAge(long ageNs) {
        if (ageNs == Long.MAX_VALUE) {
            return "N/A";
        }
        return String.format(
                Locale.US,
                "%.2f s",
                ageNs / 1_000_000_000.0
        );
    }

    private static String emptyAsDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQUEST_PERMISSIONS
                && pendingSessionId != null) {
            armIfPermitted();
        }
    }
}
