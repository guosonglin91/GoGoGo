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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LocationMonitorActivity extends BaseActivity implements LocationListener {

    private static final int REQ_LOCATION = 1201;

    private LocationManager locationManager;

    private TextView txtProvider;
    private TextView txtLat;
    private TextView txtLon;
    private TextView txtSpeed;
    private TextView txtBearing;
    private TextView txtAccuracy;
    private TextView txtMock;
    private TextView txtTime;
    private TextView txtElapsed;

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

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        findViewById(R.id.monitor_refresh).setOnClickListener(v -> {
            showLastKnown(LocationManager.GPS_PROVIDER);
            showLastKnown(LocationManager.NETWORK_PROVIDER);
        });

        ensurePermissionAndStart();
    }

    private void ensurePermissionAndStart() {
        boolean fineGranted =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        boolean coarseGranted =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[] {
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
                    this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {

                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        250L,
                        0f,
                        this
                );
            }

            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {

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
            txtProvider.setText("Provider: error - " + e.getClass().getSimpleName());
        }
    }

    private void showLastKnown(String provider) {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            Location location = locationManager.getLastKnownLocation(provider);
            if (location != null) {
                updateUi(location);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        updateUi(location);
    }

    private void updateUi(Location location) {
        txtProvider.setText("Provider: " + location.getProvider());
        txtLat.setText(String.format(Locale.US, "Latitude: %.8f", location.getLatitude()));
        txtLon.setText(String.format(Locale.US, "Longitude: %.8f", location.getLongitude()));
        txtSpeed.setText(String.format(Locale.US, "Speed: %.3f m/s", location.getSpeed()));
        txtBearing.setText(String.format(Locale.US, "Bearing: %.2f°", location.getBearing()));
        txtAccuracy.setText(String.format(Locale.US, "Accuracy: %.2f m", location.getAccuracy()));

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
    protected void onDestroy() {
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
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_LOCATION) {
            startListening();
        }
    }
}
