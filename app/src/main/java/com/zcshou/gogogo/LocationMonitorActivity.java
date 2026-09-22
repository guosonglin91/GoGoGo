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

import com.zcshou.route.RouteSessionState;
import com.zcshou.route.RouteSnapshot;
import com.zcshou.route.ServiceLocationMode;
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

    // Route Test Location (V2-C diagnostic).
    private TextView txtRouteSession;
    private TextView txtRouteState;

    private TextView txtRouteSourceLat;
    private TextView txtRouteSourceLon;

    private TextView txtRouteTargetSpeed;
    private TextView txtRouteOutputSpeed;
    private TextView txtRouteBearing;

    private TextView txtRouteDistance;
    private TextView txtRouteLap;
    private TextView txtRouteTime;
    private TextView txtRouteAge;
    private TextView txtRouteProgress;
    private TextView txtRouteErrorReason;

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

        txtRouteTargetSpeed = findViewById(R.id.route_test_target_speed);
        txtRouteOutputSpeed = findViewById(R.id.route_test_output_speed);
        txtRouteBearing = findViewById(R.id.route_test_bearing);

        txtRouteDistance = findViewById(R.id.route_test_distance);
        txtRouteLap = findViewById(R.id.route_test_lap);
        txtRouteTime = findViewById(R.id.route_test_time);
        txtRouteAge = findViewById(R.id.route_test_age);
        txtRouteProgress = findViewById(R.id.route_test_progress);
        txtRouteErrorReason = findViewById(R.id.route_test_error_reason);

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
            // Check mock provider status via LocationManager method
            // (isFromMockProvider() deprecated but standard for API 31+)
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

    // ---- Route Snapshot Listener (V2-C diagnostic) ----

    @Override
    public void onRouteTestLocationChanged(
            RouteSnapshot snapshot
    ) {
        runOnUiThread(() ->
                renderRouteSnapshot(snapshot));
    }

    private void renderRouteSnapshot(
            RouteSnapshot snapshot
    ) {
        if (snapshot == null) {
            txtRouteSession.setText("Session: -");
            txtRouteState.setText("State: no route session");

            txtRouteSourceLat.setText("WGS84 Latitude: -");
            txtRouteSourceLon.setText("WGS84 Longitude: -");

            txtRouteTargetSpeed.setText("Target Speed: -");
            txtRouteOutputSpeed.setText("Output Speed: -");
            txtRouteBearing.setText("Bearing: -");

            txtRouteDistance.setText("Distance: -");
            txtRouteLap.setText("Lap: -");
            txtRouteTime.setText("Route time: -");
            txtRouteAge.setText("Route age: -");
            txtRouteProgress.setText("Progress: -");
            txtRouteErrorReason.setText("Error: -");
            return;
        }

        txtRouteSession.setText(
                "Session: " + snapshot.getSessionId()
        );

        txtRouteState.setText(
                "State: " + snapshot.getState().name()
        );

        txtRouteTargetSpeed.setText(String.format(
                Locale.US,
                "Target Speed: %.3f m/s",
                snapshot.getTargetSpeedMps()
        ));

        txtRouteOutputSpeed.setText(String.format(
                Locale.US,
                "Output Speed: %.3f m/s",
                snapshot.getOutputSpeedMps()
        ));

        txtRouteBearing.setText(String.format(
                Locale.US,
                "Bearing: %.2f°",
                snapshot.getBearingDeg()
        ));

        double lat = snapshot.getLatitudeWgs84();
        double lon = snapshot.getLongitudeWgs84();

        if (Double.isFinite(lat) && Double.isFinite(lon)) {
            txtRouteSourceLat.setText(String.format(
                    Locale.US,
                    "WGS84 Latitude: %.8f",
                    lat
            ));

            txtRouteSourceLon.setText(String.format(
                    Locale.US,
                    "WGS84 Longitude: %.8f",
                    lon
            ));
        } else {
            txtRouteSourceLat.setText("WGS84 Latitude: waiting");
            txtRouteSourceLon.setText("WGS84 Longitude: waiting");
        }

        txtRouteDistance.setText(String.format(
                Locale.US,
                "Distance: %.2f / %.2f m",
                snapshot.getDistanceMeters(),
                snapshot.getRouteLengthMeters()
        ));

        txtRouteLap.setText(String.format(
                Locale.US,
                "Lap: %d",
                snapshot.getLapCount()
        ));

        String time = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
        ).format(new Date(snapshot.getTimestampMs()));

        txtRouteTime.setText("Route time: " + time);

        long ageMs = Math.max(
                0L,
                System.currentTimeMillis() - snapshot.getTimestampMs()
        );

        txtRouteAge.setText("Route age: " + ageMs + " ms");

        txtRouteProgress.setText(String.format(
                Locale.US,
                "Progress: %.1f %%",
                snapshot.getProgressFraction() * 100.0
        ));

        String error = snapshot.getErrorReason();
        if (error != null && !error.isEmpty()) {
            txtRouteErrorReason.setText("Error: " + error);
        } else {
            txtRouteErrorReason.setText("Error: none");
        }

        // Show mode info
        ServiceLocationMode mode = snapshot.getMode();
        if (mode != null) {
            txtRouteState.setText(
                    "State: " + snapshot.getState().name()
                            + " | Mode: " + mode.name()
            );
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