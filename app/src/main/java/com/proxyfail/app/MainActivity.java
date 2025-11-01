package com.proxyfail.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 101;

    private TextView tvLog;
    private BeaconScanner scanner;
    private TextView tvRawRssi;
    private TextView tvSmoothedRssi;
    private TextView tvProximity;
    private Button btnCalibrate;
    private Button btnToggleTelemetry;
    private boolean telemetryEnabled = false;
    private com.proxyfail.app.RadarView radarView;
    private android.content.SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tvLog);
        tvRawRssi = findViewById(R.id.tvRawRssi);
        tvSmoothedRssi = findViewById(R.id.tvSmoothedRssi);
        tvProximity = findViewById(R.id.tvProximity);
        btnCalibrate = findViewById(R.id.btnCalibrate);
        btnToggleTelemetry = findViewById(R.id.btnToggleTelemetry);
        scanner = new BeaconScanner(this, new BeaconScanner.Listener() {
            @Override
            public void onBeaconFound(String payload, int rssi) {
                appendLog("Found beacon: payload=" + payload + " rssi=" + rssi);
            }

            @Override
            public void onScanError(String reason) {
                appendLog("Scan error: " + reason);
            }
            @Override
            public void onProximityUpdate(int rawRssi, double smoothedRssi, int median, String decision, int[] recentSamples) {
                runOnUiThread(() -> {
                    tvRawRssi.setText("Raw RSSI: " + rawRssi);
                    tvSmoothedRssi.setText(String.format("Smoothed RSSI: %.1f (median %d)", smoothedRssi, median));
                    // compute approximate distance using median of recent samples (more responsive)
                    int tx = prefs.getInt("calibrated_tx_power", -59);
                    double n = 2.0; // path-loss exponent (environment)
                    double distance = Math.pow(10.0, (tx - median) / (10.0 * n));
                    // cap unrealistic values for display
                    if (Double.isNaN(distance) || distance > 50) distance = Double.POSITIVE_INFINITY;
                    String distText = Double.isFinite(distance) ? String.format(" (%.2fm)", distance) : "";
                    tvProximity.setText("Proximity: " + decision + distText);
                    if (radarView != null) {
                        radarView.setMaxMeters(6f);
                        radarView.setDistanceMeters(Double.isFinite(distance) ? distance : -1);
                    }
                });
                if (telemetryEnabled) {
                    scanner.writeTelemetry(rawRssi, smoothedRssi, recentSamples, Build.MODEL);
                }
            }
        });

        Button btnStartAdv = findViewById(R.id.btnStartAdv);
        Button btnStopAdv = findViewById(R.id.btnStopAdv);
        Button btnStartScan = findViewById(R.id.btnStartScan);
        Button btnStopScan = findViewById(R.id.btnStopScan);

        btnStartAdv.setOnClickListener(v -> {
            if (ensurePermissions()) {
                BeaconAdvertiserService.startAdvertising(this);
                appendLog("Requested advertiser start");
            }
        });

        btnStopAdv.setOnClickListener(v -> {
            BeaconAdvertiserService.stopAdvertising(this);
            appendLog("Requested advertiser stop");
        });

        btnStartScan.setOnClickListener(v -> {
            if (ensurePermissions()) {
                scanner.startScan();
                appendLog("Scanner started");
            }
        });

        btnStopScan.setOnClickListener(v -> {
            scanner.stopScan();
            appendLog("Scanner stopped");
        });

        btnCalibrate.setOnClickListener(v -> {
            // simple: start calibration activity
            startActivity(new Intent(this, CalibrationActivity.class));
        });

        prefs = getSharedPreferences("beacon_prefs", MODE_PRIVATE);
        radarView = findViewById(R.id.radarView);

        btnToggleTelemetry.setOnClickListener(v -> {
            telemetryEnabled = !telemetryEnabled;
            btnToggleTelemetry.setText(telemetryEnabled ? "Telemetry: ON" : "Telemetry: OFF");
            appendLog("Telemetry " + (telemetryEnabled ? "enabled" : "disabled"));
        });
    }

    private void appendLog(String s) {
        runOnUiThread(() -> {
            tvLog.append(s + "\n");
            ScrollView sv = findViewById(R.id.svLog);
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        });
    }

    private boolean ensurePermissions() {
        String[] perms = new String[] {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
        };

        boolean ok = true;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ok = false; break;
            }
        }

        if (!ok) {
            ActivityCompat.requestPermissions(this, perms, REQUEST_PERMISSIONS);
        }
        return ok;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = true;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
        if (granted) appendLog("Permissions granted"); else appendLog("Permissions denied");
    }
}
