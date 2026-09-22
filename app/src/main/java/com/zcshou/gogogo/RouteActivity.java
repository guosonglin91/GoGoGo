package com.zcshou.gogogo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.utils.CoordinateConverter;
import com.zcshou.route.GpxParser;
import com.zcshou.route.RoutePoint;
import com.zcshou.route.RoutePlayer;
import com.zcshou.route.RouteTestMath;
import com.zcshou.route.TestLocationSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RouteActivity extends BaseActivity {

    private static final int REQUEST_GPX = 901;
    private static final long INTERVAL_MS = 1000L;

    // If the imported final point is within this distance of the first point,
    // treat it as a duplicate closing point and keep only one canonical copy.
    private static final double CLOSING_POINT_TOLERANCE_M = 0.5;

    private MapView mapView;
    private BaiduMap baiduMap;
    private Marker movingMarker;

    private EditText speedInput;
    private Switch loopSwitch;
    private TextView statusText;
    private Button pauseButton;

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
    private volatile boolean playbackActive = false;
    private volatile long currentSessionId = 0L;
    private volatile float lastBearingDeg = 0.0f;

    private final Object measurementLock = new Object();
    private RoutePoint previousMeasuredPointWgs84;
    private long previousMeasuredTimestampMs = 0L;

    private final RoutePlayer player = new RoutePlayer(new RoutePlayer.Listener() {
        @Override
        public void onPosition(RoutePoint displayPointBd09, int index, int total) {
            if (index < 0 || index >= playbackSourceRouteWgs84.size()) {
                return;
            }

            RoutePoint sourcePointWgs84 = playbackSourceRouteWgs84.get(index);
            long nowMs = System.currentTimeMillis();

            double measuredSpeedMps;
            synchronized (measurementLock) {
                measuredSpeedMps = RouteTestMath.measuredSpeedMps(
                        previousMeasuredPointWgs84,
                        previousMeasuredTimestampMs,
                        sourcePointWgs84,
                        nowMs
                );

                previousMeasuredPointWgs84 = sourcePointWgs84;
                previousMeasuredTimestampMs = nowMs;
            }

            float bearing = calculatePlaybackBearing(index);
            lastBearingDeg = bearing;

            TestLocationSource.publishPosition(
                    currentSessionId,
                    sourcePointWgs84.latitude,
                    sourcePointWgs84.longitude,
                    displayPointBd09.latitude,
                    displayPointBd09.longitude,
                    playbackTargetSpeedMps,
                    measuredSpeedMps,
                    bearing,
                    nowMs,
                    index,
                    total
            );

            runOnUiThread(() ->
                    updatePlaybackMarker(displayPointBd09, index, total));
        }

        @Override
        public void onFinished() {
            playbackActive = false;
            resetMeasurementBaseline();

            TestLocationSource.publishState(
                    currentSessionId,
                    TestLocationSource.State.FINISHED
            );

            runOnUiThread(() -> {
                statusText.setText("回放完成");
                pauseButton.setText("暂停");
            });
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route);

        mapView = findViewById(R.id.route_map);
        baiduMap = mapView.getMap();

        speedInput = findViewById(R.id.route_speed);
        loopSwitch = findViewById(R.id.route_loop);
        statusText = findViewById(R.id.route_status);
        pauseButton = findViewById(R.id.route_pause);

        findViewById(R.id.route_import).setOnClickListener(v -> openGpx());
        findViewById(R.id.route_start).setOnClickListener(v -> startPlayback());
        pauseButton.setOnClickListener(v -> togglePause());
        findViewById(R.id.route_stop).setOnClickListener(v -> stopPlayback());

        statusText.setText("请先导入 GPX");
    }

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
                    "已导入 %d 点，规范化 %d 点，闭合=%s，约 %.1f m",
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

    private void startPlayback() {
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
                    "请输入 0～20 m/s 的测试速度",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (playbackActive && currentSessionId > 0L) {
            TestLocationSource.publishState(
                    currentSessionId,
                    TestLocationSource.State.STOPPED
            );
        }

        playbackTargetSpeedMps = speedMps;
        playbackLoopEnabled = loopSwitch.isChecked();

        double stepMeters =
                playbackTargetSpeedMps
                        * INTERVAL_MS
                        / 1000.0;

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

        currentSessionId =
                TestLocationSource.beginSession(
                        playbackTargetSpeedMps
                );

        lastBearingDeg = 0.0f;
        resetMeasurementBaseline();
        playbackActive = true;

        player.configure(
                playbackDisplayRouteBd09,
                INTERVAL_MS,
                playbackLoopEnabled
        );
        player.start();

        pauseButton.setText("暂停");

        statusText.setText(String.format(
                Locale.getDefault(),
                "Session %d：%.2f m/s，%d 点，loop=%s",
                currentSessionId,
                playbackTargetSpeedMps,
                playbackSourceRouteWgs84.size(),
                playbackLoopEnabled ? "on" : "off"
        ));
    }

    private void togglePause() {
        if (!playbackActive) {
            Toast.makeText(
                    this,
                    "请先开始路线回放",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (player.isPaused()) {
            resetMeasurementBaseline();

            TestLocationSource.publishState(
                    currentSessionId,
                    TestLocationSource.State.PLAYING
            );

            player.resume();
            pauseButton.setText("暂停");
        } else {
            player.pause();
            resetMeasurementBaseline();

            TestLocationSource.publishState(
                    currentSessionId,
                    TestLocationSource.State.PAUSED
            );

            pauseButton.setText("继续");
        }
    }

    private void stopPlayback() {
        player.stop();
        resetMeasurementBaseline();

        if (currentSessionId > 0L) {
            TestLocationSource.publishState(
                    currentSessionId,
                    TestLocationSource.State.STOPPED
            );
        }

        playbackActive = false;
        pauseButton.setText("暂停");
        statusText.setText("已停止");
    }

    private float calculatePlaybackBearing(int index) {
        if (playbackSourceRouteWgs84 == null
                || playbackSourceRouteWgs84.size() < 2) {
            return lastBearingDeg;
        }

        int currentIndex = Math.max(
                0,
                Math.min(
                        index,
                        playbackSourceRouteWgs84.size() - 1
                )
        );

        int nextIndex = currentIndex + 1;

        if (nextIndex >= playbackSourceRouteWgs84.size()) {
            if (playbackLoopEnabled) {
                nextIndex = 0;
            } else {
                return lastBearingDeg;
            }
        }

        return RouteTestMath.bearingDegrees(
                playbackSourceRouteWgs84.get(currentIndex),
                playbackSourceRouteWgs84.get(nextIndex),
                lastBearingDeg
        );
    }

    private void resetMeasurementBaseline() {
        synchronized (measurementLock) {
            previousMeasuredPointWgs84 = null;
            previousMeasuredTimestampMs = 0L;
        }
    }

    private void updatePlaybackMarker(
            RoutePoint displayPointBd09,
            int index,
            int total
    ) {
        LatLng latLng = new LatLng(
                displayPointBd09.latitude,
                displayPointBd09.longitude
        );

        if (movingMarker == null) {
            movingMarker = (Marker) baiduMap.addOverlay(
                    new MarkerOptions()
                            .position(latLng)
                            .icon(BitmapDescriptorFactory.fromResource(
                                    R.drawable.icon_gcoding
                            ))
            );
        } else {
            movingMarker.setPosition(latLng);
        }

        baiduMap.animateMapStatus(
                MapStatusUpdateFactory.newLatLng(latLng)
        );

        statusText.setText(String.format(
                Locale.getDefault(),
                "Session %d：回放中 %d / %d",
                currentSessionId,
                index + 1,
                total
        ));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        player.stop();
        resetMeasurementBaseline();

        if (playbackActive && currentSessionId > 0L) {
            TestLocationSource.publishState(
                    currentSessionId,
                    TestLocationSource.State.STOPPED
            );
        }

        playbackActive = false;

        mapView.onDestroy();
        super.onDestroy();
    }
}
