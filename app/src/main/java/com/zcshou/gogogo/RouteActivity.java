package com.zcshou.gogogo;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
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
import com.zcshou.route.RouteInterpolator;
import com.zcshou.route.RoutePlayer;
import com.zcshou.route.RoutePoint;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RouteActivity extends BaseActivity {

    private static final int REQUEST_GPX = 901;
    private static final long INTERVAL_MS = 1000L;

    private MapView mapView;
    private BaiduMap baiduMap;
    private Marker movingMarker;

    private EditText speedInput;
    private Switch loopSwitch;
    private TextView statusText;
    private Button pauseButton;

    // GPX 原始坐标为 GPS/WGS84；绘制到百度地图前统一转为百度地图坐标。
    private List<RoutePoint> displayRoute = new ArrayList<>();
    private List<RoutePoint> playbackRoute = new ArrayList<>();

    private final RoutePlayer player = new RoutePlayer(new RoutePlayer.Listener() {
        @Override
        public void onPosition(RoutePoint point, int index, int total) {
            runOnUiThread(() -> updatePlaybackMarker(point, index, total));
        }

        @Override
        public void onFinished() {
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
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_GPX || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            List<RoutePoint> gpsRoute = GpxParser.parse(inputStream);
            if (gpsRoute.size() < 2) {
                Toast.makeText(this, "GPX 中至少需要 2 个轨迹点", Toast.LENGTH_LONG).show();
                return;
            }

            displayRoute = convertGpsToBaidu(gpsRoute);
            drawRoute(displayRoute);

            double distance = RouteInterpolator.totalDistanceMeters(displayRoute);
            statusText.setText(String.format(
                    Locale.getDefault(),
                    "已导入 %d 点，路线约 %.1f m",
                    displayRoute.size(),
                    distance
            ));
        } catch (Exception e) {
            Toast.makeText(this, "GPX 读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private List<RoutePoint> convertGpsToBaidu(List<RoutePoint> gpsPoints) {
        List<RoutePoint> converted = new ArrayList<>(gpsPoints.size());

        for (RoutePoint p : gpsPoints) {
            CoordinateConverter converter = new CoordinateConverter()
                    .from(CoordinateConverter.CoordType.GPS)
                    .coord(new LatLng(p.latitude, p.longitude));

            LatLng bd = converter.convert();
            converted.add(new RoutePoint(bd.latitude, bd.longitude));
        }

        return converted;
    }

    private void drawRoute(List<RoutePoint> points) {
        baiduMap.clear();
        movingMarker = null;

        List<LatLng> mapPoints = new ArrayList<>(points.size());
        for (RoutePoint p : points) {
            mapPoints.add(new LatLng(p.latitude, p.longitude));
        }

        baiduMap.addOverlay(new PolylineOptions()
                .width(8)
                .points(mapPoints));

        if (!mapPoints.isEmpty()) {
            baiduMap.animateMapStatus(
                    MapStatusUpdateFactory.newLatLngZoom(mapPoints.get(0), 18.0f)
            );
        }
    }

    private void startPlayback() {
        if (displayRoute.size() < 2) {
            Toast.makeText(this, "请先导入 GPX", Toast.LENGTH_SHORT).show();
            return;
        }

        double speed;
        try {
            speed = Double.parseDouble(speedInput.getText().toString().trim());
        } catch (Exception e) {
            speed = -1.0;
        }

        if (speed <= 0.0 || speed > 20.0) {
            Toast.makeText(this, "请输入 0～20 m/s 的测试速度", Toast.LENGTH_LONG).show();
            return;
        }

        double stepMeters = speed * INTERVAL_MS / 1000.0;
        playbackRoute = RouteInterpolator.resample(displayRoute, stepMeters);

        player.configure(playbackRoute, INTERVAL_MS, loopSwitch.isChecked());
        player.start();

        pauseButton.setText("暂停");
        statusText.setText(String.format(
                Locale.getDefault(),
                "开始回放：%.2f m/s，%d 个重采样点",
                speed,
                playbackRoute.size()
        ));
    }

    private void togglePause() {
        if (player.isPaused()) {
            player.resume();
            pauseButton.setText("暂停");
        } else {
            player.pause();
            pauseButton.setText("继续");
        }
    }

    private void stopPlayback() {
        player.stop();
        pauseButton.setText("暂停");
        statusText.setText("已停止");
    }

    private void updatePlaybackMarker(RoutePoint point, int index, int total) {
        LatLng latLng = new LatLng(point.latitude, point.longitude);

        if (movingMarker == null) {
            movingMarker = (Marker) baiduMap.addOverlay(
                    new MarkerOptions()
                            .position(latLng)
                            .icon(BitmapDescriptorFactory.fromResource(R.drawable.icon_gcoding))
            );
        } else {
            movingMarker.setPosition(latLng);
        }

        baiduMap.animateMapStatus(MapStatusUpdateFactory.newLatLng(latLng));

        statusText.setText(String.format(
                Locale.getDefault(),
                "回放中：%d / %d",
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
        mapView.onDestroy();
        super.onDestroy();
    }
}
