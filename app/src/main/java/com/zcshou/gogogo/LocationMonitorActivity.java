package com.zcshou.gogogo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.zcshou.route.TestLocationSource;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LocationMonitorActivity extends BaseActivity
        implements LocationListener, TestLocationSource.Listener {

    private static final int REQ_LOCATION = 1201;

    private LocationManager locationManager;

    // Android System Location.
    private TextView txtProvider;
    private TextView txtLat;
    private TextView txtLon;
    private TextView txtSpeed;
    private TextView txtBearing;
    private TextView txtAccuracy;
    private TextView txtMock;
    private TextView txtTime;
    private TextView txtElapsed;

    // Route Test Location.
    private TextView txtRouteSession;
    private TextView txtRouteState;

    private TextView txtRouteSourceLat;
    private TextView txtRouteSourceLon;

    private TextView txtRouteDisplayLat;
    private TextView txtRouteDisplayLon;

    private TextView txtRouteTargetSpeed;
    private TextView txtRouteMeasuredSpeed;
    private TextView txtRouteBearing;

    private TextView txtRouteTime;
    private TextView txtRouteAge;
    private TextView txtRouteProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_monitor);

        txtProvider = findViewById(R.id.monitor_provider);
        txtLat = findViewById(R.id.monitor_lat);
        txtLon = findViewById(R.id.monitor_lon);
        txtSpeed = findViewById(R.id.monitor_speed);
        txtBearing = findViewById(R.id.monitor_bearing);
        txtAccuracy = findViewById(R.id.monitor_accuracy);
        txtMock = findViewById(R.id.monitor_mock);
        txtTime = findViewById(R.id.monitor_time);
        txtElapsed = findViewById(R.id.monitor_elapsed);

        txtRouteSession = findViewById(R.id.route_test_session);
        txtRouteState = findViewById(R.id.route_test_state);

        txtRouteSourceLat = findViewById(R.id.route_test_source_lat);
        txtRouteSourceLon = findViewById(R.id.route_test_source_lon);

        txtRouteDisplayLat = findViewById(R.id.route_test_display_lat);
        txtRouteDisplayLon = findViewById(R.id.route_test_display_lon);

        txtRouteTargetSpeed = findViewById(R.id.route_test_target_speed);
        txtRouteMeasuredSpeed = findViewById(R.id.route_test_measured_speed);
        txtRouteBearing = findViewById(R.id.route_test_bearing);

        txtRouteTime = findViewById(R.id.route_test_time);
        txtRouteAge = findViewById(R.id.route_test_age);
        txtRouteProgress = findViewById(R.id.route_test_progress);

        locationManager =
                (LocationManager) getSystemService(LOCATION_SERVICE);

        findViewById(R.id.monitor_refresh).setOnClickListener(v -> {
            showLastKnown(LocationManager.GPS_PROVIDER);
            showLastKnown(LocationManager.NETWORK_PROVIDER);
            renderRouteSnapshot(TestLocationSource.getLatest());
        });

        renderRouteSnapshot(TestLocationSource.getLatest());
        ensurePermissionAndStart();
    }

    @Override
    protected void onStart() {
        super.onStart();
        TestLocationSource.addListener(this);
        renderRouteSnapshot(TestLocationSource.getLatest());
    }

    @Override
    protected void onStop() {
        TestLocationSource.removeListener(this);
        super.onStop();
    }

    private void ensurePermissionAndStart() {
        boolean fineGranted =
                ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        boolean coarseGranted =
                ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_LOCATION
            );
            return;
        }

        startListening();
    }

    private void startListening() {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {

                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        250L,
                        0f,
                        this
                );
            }

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {

                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        250L,
                        0f,
                        this
                );
            }

            showLastKnown(LocationManager.GPS_PROVIDER);
            showLastKnown(LocationManager.NETWORK_PROVIDER);

        } catch (Exception e) {
            txtProvider.setText(
                    "Provider: error - "
                            + e.getClass().getSimpleName()
            );
        }
    }

    private void showLastKnown(String provider) {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            Location location =
                    locationManager.getLastKnownLocation(provider);

            if (location != null) {
                updateSystemLocationUi(location);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        updateSystemLocationUi(location);
    }

    private void updateSystemLocationUi(Location location) {
        txtProvider.setText(
                "Provider: " + location.getProvider()
        );

        txtLat.setText(String.format(
                Locale.US,
                "Latitude: %.8f",
                location.getLatitude()
        ));

        txtLon.setText(String.format(
                Locale.US,
                "Longitude: %.8f",
                location.getLongitude()
        ));

        txtSpeed.setText(String.format(
                Locale.US,
                "Speed: %.3f m/s",
                location.getSpeed()
        ));

        txtBearing.setText(String.format(
                Locale.US,
                "Bearing: %.2f°",
                location.getBearing()
        ));

        txtAccuracy.setText(String.format(
                Locale.US,
                "Accuracy: %.2f m",
                location.getAccuracy()
        ));

        boolean mock;
        try {
            mock = location.isFromMockProvider();
        } catch (Exception e) {
            mock = false;
        }

        txtMock.setText("isMock: " + mock);

        String time = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
        ).format(new Date(location.getTime()));

        txtTime.setText("Location time: " + time);

        long ageMs = Math.max(
                0L,
                SystemClock.elapsedRealtimeNanos() / 1_000_000L
                        - location.getElapsedRealtimeNanos() / 1_000_000L
        );

        txtElapsed.setText("Age: " + ageMs + " ms");
    }

    @Override
    public void onRouteTestLocationChanged(
            TestLocationSource.Snapshot snapshot
    ) {
        runOnUiThread(() ->
                renderRouteSnapshot(snapshot));
    }

    private void renderRouteSnapshot(
            TestLocationSource.Snapshot snapshot
    ) {
        if (snapshot == null) {
            txtRouteSession.setText("Session: -");
            txtRouteState.setText("State: no route session");

            txtRouteSourceLat.setText("WGS84 Latitude: -");
            txtRouteSourceLon.setText("WGS84 Longitude: -");

            txtRouteDisplayLat.setText("BD09 Latitude: -");
            txtRouteDisplayLon.setText("BD09 Longitude: -");

            txtRouteTargetSpeed.setText("Target Speed: -");
            txtRouteMeasuredSpeed.setText("Measured Speed: -");
            txtRouteBearing.setText("Bearing: -");

            txtRouteTime.setText("Route time: -");
            txtRouteAge.setText("Route age: -");
            txtRouteProgress.setText("Progress: -");
            return;
        }

        txtRouteSession.setText(
                "Session: " + snapshot.sessionId
        );

        txtRouteState.setText(
                "State: " + snapshot.state.name()
        );

        txtRouteTargetSpeed.setText(String.format(
                Locale.US,
                "Target Speed: %.3f m/s",
                snapshot.targetSpeedMps
        ));

        txtRouteMeasuredSpeed.setText(String.format(
                Locale.US,
                "Measured Speed: %.3f m/s",
                snapshot.measuredSpeedMps
        ));

        txtRouteBearing.setText(String.format(
                Locale.US,
                "Bearing: %.2f°",
                snapshot.bearingDeg
        ));

        if (snapshot.hasPosition) {
            txtRouteSourceLat.setText(String.format(
                    Locale.US,
                    "WGS84 Latitude: %.8f",
                    snapshot.sourceLatitudeWgs84
            ));

            txtRouteSourceLon.setText(String.format(
                    Locale.US,
                    "WGS84 Longitude: %.8f",
                    snapshot.sourceLongitudeWgs84
            ));

            txtRouteDisplayLat.setText(String.format(
                    Locale.US,
                    "BD09 Latitude: %.8f",
                    snapshot.displayLatitudeBd09
            ));

            txtRouteDisplayLon.setText(String.format(
                    Locale.US,
                    "BD09 Longitude: %.8f",
                    snapshot.displayLongitudeBd09
            ));
        } else {
            txtRouteSourceLat.setText("WGS84 Latitude: waiting");
            txtRouteSourceLon.setText("WGS84 Longitude: waiting");

            txtRouteDisplayLat.setText("BD09 Latitude: waiting");
            txtRouteDisplayLon.setText("BD09 Longitude: waiting");
        }

        String time = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
        ).format(new Date(snapshot.timestampMs));

        txtRouteTime.setText("Route time: " + time);

        long ageMs = Math.max(
                0L,
                System.currentTimeMillis() - snapshot.timestampMs
        );

        txtRouteAge.setText("Route age: " + ageMs + " ms");

        if (snapshot.index >= 0 && snapshot.total > 0) {
            txtRouteProgress.setText(String.format(
                    Locale.US,
                    "Progress: %d / %d",
                    snapshot.index + 1,
                    snapshot.total
            ));
        } else {
            txtRouteProgress.setText("Progress: waiting");
        }
    }

    @Override
    protected void onDestroy() {
        TestLocationSource.removeListener(this);

        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
            }
        }

        super.onDestroy();
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

        if (requestCode == REQ_LOCATION) {
            startListening();
        }
    }
}
