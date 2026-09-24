package com.zcshou.gogogo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.utils.CoordinateConverter;
import com.zcshou.motion.MotionSessionId;
import com.zcshou.motion.ProducerSessionExporter;
import com.zcshou.route.GpxParser;
import com.zcshou.route.RoutePlan;
import com.zcshou.route.RoutePoint;
import com.zcshou.route.RouteSessionState;
import com.zcshou.route.RouteSnapshot;
import com.zcshou.route.RouteStartResult;
import com.zcshou.route.RouteTestMath;
import com.zcshou.service.ServiceGo;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RouteActivity extends BaseActivity {

    private static final int REQUEST_GPX = 901;
    private static final long UI_REFRESH_MS = 250L;
    private static final double CLOSING_POINT_TOLERANCE_M = 0.5;

    private MapView mapView;
    private BaiduMap baiduMap;
    private Marker movingMarker;

    private EditText speedInput;
    private EditText evidenceSessionInput;
    private Switch loopSwitch;
    private TextView statusText;
    private Button pauseButton;
    private Button exportEvidenceButton;

    // Canonical route data is WGS84. BD09 is display-only.
    private List<RoutePoint> sourceRouteWgs84 = new ArrayList<>();
    private List<RoutePoint> displayRouteBd09 = new ArrayList<>();
    private boolean importedWasClosedLoop = false;

    // Playback keeps source/display arrays one-to-one by resampling WGS84 first,
    // then converting the resampled points to BD09.
    private List<RoutePoint> playbackSourceRouteWgs84 = new ArrayList<>();
    private List<RoutePoint> playbackDisplayRouteBd09 = new ArrayList<>();

    private volatile double playbackTargetSpeedMps = 0.0;
    private volatile boolean playbackLoopEnabled = false;

    // V2-C Binder state
    private ServiceGo.ServiceGoBinder mServiceBinder;
    private boolean mBound = false;
    private long mCurrentSessionId = 0L;
    private RouteSnapshot mLastSnapshot;
    private String mCurrentEvidenceSessionId;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceBinder = (ServiceGo.ServiceGoBinder) service;
            mBound = true;

            // Restore existing active session snapshot
            RouteSnapshot snap = mServiceBinder.getRouteSnapshot();
            if (snap != null && snap.getSessionId() > 0L) {
                mCurrentSessionId = snap.getSessionId();
                updateFromSnapshot(snap);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBinder = null;
            mBound = false;
        }
    };

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mUiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mBound || mServiceBinder == null) {
                mUiHandler.postDelayed(this, UI_REFRESH_MS);
                return;
            }

            RouteSnapshot snap = mServiceBinder.getRouteSnapshot();
            if (snap != null) {
                mLastSnapshot = snap;
                updateFromSnapshot(snap);
            }

            mUiHandler.postDelayed(this, UI_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route);

        mapView = findViewById(R.id.route_map);
        baiduMap = mapView.getMap();

        speedInput = findViewById(R.id.route_speed);
        evidenceSessionInput = findViewById(R.id.route_evidence_session);
        loopSwitch = findViewById(R.id.route_loop);
        statusText = findViewById(R.id.route_status);
        pauseButton = findViewById(R.id.route_pause);
        exportEvidenceButton = findViewById(R.id.route_export_evidence);

        findViewById(R.id.route_import).setOnClickListener(v -> openGpx());
        findViewById(R.id.route_start).setOnClickListener(v -> startRouteViaService());
        pauseButton.setOnClickListener(v -> togglePause());
        findViewById(R.id.route_stop).setOnClickListener(v -> stopRouteViaService());
        findViewById(R.id.route_monitor).setOnClickListener(v ->
                startActivity(new Intent(RouteActivity.this, LocationMonitorActivity.class)));
        exportEvidenceButton.setOnClickListener(v -> exportProducerEvidence());

        evidenceSessionInput.setText(defaultEvidenceSessionId());
        statusText.setText("请先导入 GPX");
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Ensure ServiceGo is running without position extras
        Intent serviceIntent = new Intent(this, ServiceGo.class);
        startForegroundService(serviceIntent);

        // Bind to ServiceGo
        bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);

        // Start UI polling
        mUiHandler.post(mUiRefreshRunnable);
    }

    @Override
    protected void onStop() {
        // Stop UI polling
        mUiHandler.removeCallbacks(mUiRefreshRunnable);

        // Unbind from ServiceGo — do NOT stop route
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
            mServiceBinder = null;
        }

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Only release UI/map resources — do NOT stop the route
        mapView.onDestroy();
        super.onDestroy();
    }

    // ---- GPX Import ----

    private void openGpx() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/gpx+xml",
                "application/xml",
                "text/xml",
                "text/plain"
        });
        startActivityForResult(intent, REQUEST_GPX);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_GPX
                || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        try (InputStream inputStream =
                     getContentResolver().openInputStream(uri)) {

            List<RoutePoint> parsedGpsRouteWgs84 =
                    GpxParser.parse(inputStream);

            if (parsedGpsRouteWgs84.size() < 2) {
                Toast.makeText(
                        this,
                        "GPX 中至少需要 2 个轨迹点",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            importedWasClosedLoop = RouteTestMath.isClosedLoop(
                    parsedGpsRouteWgs84,
                    CLOSING_POINT_TOLERANCE_M
            );

            sourceRouteWgs84 =
                    RouteTestMath.stripDuplicateClosingPoint(
                            parsedGpsRouteWgs84,
                            CLOSING_POINT_TOLERANCE_M
                    );

            if (sourceRouteWgs84.size() < 2) {
                Toast.makeText(
                        this,
                        "去除重复闭合点后，路线有效点不足 2 个",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            displayRouteBd09 =
                    convertGpsToBaidu(sourceRouteWgs84);

            drawRoute(
                    displayRouteBd09,
                    importedWasClosedLoop
            );

            double distanceMeters =
                    RouteTestMath.totalDistanceMeters(
                            sourceRouteWgs84,
                            importedWasClosedLoop
                    );

            statusText.setText(String.format(
                    Locale.getDefault(),
                    "已导入 %d 点，规范 %d 点，闭合=%s，约 %.1f m",
                    parsedGpsRouteWgs84.size(),
                    sourceRouteWgs84.size(),
                    importedWasClosedLoop ? "是" : "否",
                    distanceMeters
            ));
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "GPX 读取失败：" + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // ---- Coordinate Conversion (WGS84 → BD09 for display only) ----

    private List<RoutePoint> convertGpsToBaidu(
            List<RoutePoint> gpsPoints
    ) {
        List<RoutePoint> converted =
                new ArrayList<>(gpsPoints.size());

        for (RoutePoint point : gpsPoints) {
            CoordinateConverter converter =
                    new CoordinateConverter()
                            .from(CoordinateConverter.CoordType.GPS)
                            .coord(new LatLng(
                                    point.latitude,
                                    point.longitude
                            ));

            LatLng bd09 = converter.convert();

            converted.add(new RoutePoint(
                    bd09.latitude,
                    bd09.longitude
            ));
        }

        return converted;
    }

    // ---- Map Drawing ----

    private void drawRoute(
            List<RoutePoint> pointsBd09,
            boolean closeForDisplay
    ) {
        baiduMap.clear();
        movingMarker = null;

        List<LatLng> mapPoints =
                new ArrayList<>(pointsBd09.size() + 1);

        for (RoutePoint point : pointsBd09) {
            mapPoints.add(new LatLng(
                    point.latitude,
                    point.longitude
            ));
        }

        if (closeForDisplay && mapPoints.size() >= 2) {
            mapPoints.add(mapPoints.get(0));
        }

        baiduMap.addOverlay(
                new PolylineOptions()
                        .width(8)
                        .points(mapPoints)
        );

        if (!mapPoints.isEmpty()) {
            baiduMap.animateMapStatus(
                    MapStatusUpdateFactory.newLatLngZoom(
                            mapPoints.get(0),
                            18.0f
                    )
            );
        }
    }

    // ---- V2-C Route Control (via Binder) ----

    private void startRouteViaService() {
        if (sourceRouteWgs84.size() < 2) {
            Toast.makeText(
                    this,
                    "请先导入 GPX",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        double speedMps;

        try {
            speedMps = Double.parseDouble(
                    speedInput.getText().toString().trim()
            );
        } catch (Exception e) {
            speedMps = -1.0;
        }

        if (speedMps <= 0.0 || speedMps > 20.0) {
            Toast.makeText(
                    this,
                    "请输入 0~20 m/s 的测试速度",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (!mBound || mServiceBinder == null) {
            Toast.makeText(
                    this,
                    "ServiceGo 未连接，请稍后重试",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        String evidenceSessionId;
        try {
            evidenceSessionId = MotionSessionId.validate(
                    evidenceSessionInput.getText().toString().trim()
            );
        } catch (IllegalArgumentException e) {
            Toast.makeText(
                    this,
                    "V2-E Session ID 无效",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        playbackTargetSpeedMps = speedMps;
        playbackLoopEnabled = loopSwitch.isChecked();

        // Resample route for consistent polyline display
        double stepMeters = playbackTargetSpeedMps * 1000.0 / 1000.0;

        playbackSourceRouteWgs84 =
                RouteTestMath.prepareForPlayback(
                        sourceRouteWgs84,
                        stepMeters,
                        playbackLoopEnabled,
                        CLOSING_POINT_TOLERANCE_M
                );

        if (playbackSourceRouteWgs84.size() < 2) {
            Toast.makeText(
                    this,
                    "路线重采样后有效点不足 2 个",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        playbackDisplayRouteBd09 =
                convertGpsToBaidu(playbackSourceRouteWgs84);

        // Create RoutePlan and send to ServiceGo through Binder
        RoutePlan plan = RoutePlan.request(
                sourceRouteWgs84,
                importedWasClosedLoop,
                loopSwitch.isChecked(),
                speedMps,
                100L,
                evidenceSessionId
        );

        RouteStartResult result = mServiceBinder.startRoute(plan);

        if (result.isSuccess()) {
            mCurrentSessionId = result.getSessionId();
            mCurrentEvidenceSessionId = evidenceSessionId;
            exportEvidenceButton.setEnabled(false);
            evidenceSessionInput.setEnabled(false);
            pauseButton.setText("暂停");

            statusText.setText(String.format(
                    Locale.getDefault(),
                    "Session %d / %s: %.2f m/s, %d 点, loop=%s",
                    mCurrentSessionId,
                    evidenceSessionId,
                    playbackTargetSpeedMps,
                    playbackSourceRouteWgs84.size(),
                    playbackLoopEnabled ? "on" : "off"
            ));
        } else {
            Toast.makeText(
                    this,
                    "路线启动失败: " + result.getMessage(),
                    Toast.LENGTH_LONG
            ).show();

            statusText.setText("启动失败: " + result.getMessage());
        }
    }

    private void exportProducerEvidence() {
        String evidenceId = mCurrentEvidenceSessionId;
        if (evidenceId == null || evidenceId.isEmpty()) {
            evidenceId = evidenceSessionInput.getText().toString().trim();
        }

        try {
            evidenceId = MotionSessionId.validate(evidenceId);

            File base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (base == null) {
                base = getFilesDir();
            }

            File sessionDir = new File(
                    base,
                    "v2e/producer/session_" + evidenceId
            );
            File exportDir = new File(base, "v2e/exports");

            File zip = ProducerSessionExporter.exportSession(
                    sessionDir,
                    exportDir,
                    evidenceId
            );

            Uri uri = FileProvider.getUriForFile(
                    this,
                    BuildConfig.APPLICATION_ID + ".fileProvider",
                    zip
            );

            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(
                    send,
                    "Share V2-E producer evidence"
            ));
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "证据尚未完成或导出失败: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private static String defaultEvidenceSessionId() {
        return new SimpleDateFormat(
                "'v2e_'yyyyMMdd_HHmmss",
                Locale.US
        ).format(new Date());
    }

    private void togglePause() {
        if (!mBound || mServiceBinder == null || mCurrentSessionId <= 0L) {
            Toast.makeText(
                    this,
                    "请先开始路线回放",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (mLastSnapshot != null
                && mLastSnapshot.getState() == RouteSessionState.PAUSED) {
            // Resume
            boolean ok = mServiceBinder.resumeRoute(mCurrentSessionId);
            if (ok) {
                pauseButton.setText("暂停");
            }
        } else {
            // Pause
            boolean ok = mServiceBinder.pauseRoute(mCurrentSessionId);
            if (ok) {
                pauseButton.setText("继续");
            }
        }
    }

    private void stopRouteViaService() {
        if (!mBound || mServiceBinder == null || mCurrentSessionId <= 0L) {
            return;
        }

        mServiceBinder.stopRoute(mCurrentSessionId);
        pauseButton.setText("暂停");
        statusText.setText("已停止");
    }

    // ---- UI Snapshot Update ----

    private void updateFromSnapshot(RouteSnapshot snap) {
        if (snap == null) return;

        long sessionId = snap.getSessionId();
        RouteSessionState state = snap.getState();

        // Update status text
        String status;
        switch (state) {
            case PLAYING:
                status = String.format(
                        Locale.getDefault(),
                        "Session %d: 回放中 %.1f / %.1f m (%.0f%%)",
                        sessionId,
                        snap.getDistanceMeters(),
                        snap.getRouteLengthMeters(),
                        snap.getProgressFraction() * 100.0
                );
                pauseButton.setText("暂停");
                break;
            case PAUSED:
                status = String.format(
                        Locale.getDefault(),
                        "Session %d: 已暂停 %.1f / %.1f m",
                        sessionId,
                        snap.getDistanceMeters(),
                        snap.getRouteLengthMeters()
                );
                pauseButton.setText("继续");
                break;
            case STOPPED:
                status = String.format(
                        Locale.getDefault(),
                        "Session %d: 已停止 %.1f / %.1f m",
                        sessionId,
                        snap.getDistanceMeters(),
                        snap.getRouteLengthMeters()
                );
                pauseButton.setText("暂停");
                exportEvidenceButton.setEnabled(true);
                evidenceSessionInput.setEnabled(true);
                break;
            case FINISHED:
                status = "回放完成";
                pauseButton.setText("暂停");
                exportEvidenceButton.setEnabled(true);
                evidenceSessionInput.setEnabled(true);
                break;
            case ERROR:
                status = "错误: " + snap.getErrorReason();
                pauseButton.setText("暂停");
                exportEvidenceButton.setEnabled(true);
                evidenceSessionInput.setEnabled(true);
                break;
            default:
                status = "状态: " + state.name();
        }

        statusText.setText(status);

        // Update map marker — convert WGS84 snapshot position to BD09
        double wgs84Lat = snap.getLatitudeWgs84();
        double wgs84Lon = snap.getLongitudeWgs84();

        if (Double.isFinite(wgs84Lat) && Double.isFinite(wgs84Lon)) {
            CoordinateConverter converter = new CoordinateConverter()
                    .from(CoordinateConverter.CoordType.GPS)
                    .coord(new LatLng(wgs84Lat, wgs84Lon));

            LatLng bd09Pos = converter.convert();

            if (movingMarker == null) {
                movingMarker = (Marker) baiduMap.addOverlay(
                        new MarkerOptions()
                                .position(bd09Pos)
                                .icon(BitmapDescriptorFactory.fromResource(
                                        R.drawable.icon_gcoding
                                ))
                );
            } else {
                movingMarker.setPosition(bd09Pos);
            }

            baiduMap.animateMapStatus(
                    MapStatusUpdateFactory.newLatLng(bd09Pos)
            );
        }
    }
}