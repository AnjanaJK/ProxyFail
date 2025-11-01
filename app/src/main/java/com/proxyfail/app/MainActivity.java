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
    private String lastPayloadFound = null;
    private boolean qrScanInProgress = false;
    private final int REQ_SCAN_QR = 200;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final int BLE_WAIT_TIMEOUT_MS = 10_000; // wait up to 10s for close proximity
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
                // store last seen payload and log
                lastPayloadFound = payload;
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
                // If decision says close and we have a payload, start QR scan flow (only once)
                if ("close".equals(decision) && lastPayloadFound != null && !qrScanInProgress) {
                    qrScanInProgress = true;
                    // stop BLE scanning while we do QR scan
                    scanner.stopScan();
                    appendLog("Close proximity detected for payload=" + lastPayloadFound + ", launching QR scanner");
                    // start QR scanner for result
                    Intent scanIntent = new Intent(MainActivity.this, QrScanActivity.class);
                    startActivityForResult(scanIntent, REQ_SCAN_QR);
                }
                if (telemetryEnabled) {
                    scanner.writeTelemetry(rawRssi, smoothedRssi, recentSamples, Build.MODEL);
                }
            }
        });

        Button btnStartAdv = findViewById(R.id.btnStartAdv);
        Button btnStopAdv = findViewById(R.id.btnStopAdv);
        Button btnStartScan = findViewById(R.id.btnStartScan);
        Button btnStopScan = findViewById(R.id.btnStopScan);
    Button btnShowQr = findViewById(R.id.btnShowQr);
    Button btnBleThenQr = findViewById(R.id.btnBleThenQr);

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

        btnShowQr.setOnClickListener(v -> {
            // ask user for payload (session id) then show QR and start advertising
            final android.widget.EditText et = new android.widget.EditText(this);
            et.setHint("session:ABC123");
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Enter QR payload")
                    .setView(et)
                    .setPositiveButton("Show", (d, which) -> {
                        String payload = et.getText() == null || et.getText().toString().isEmpty() ? "session:static" : et.getText().toString();
                        Intent i = new Intent(this, QrDisplayActivity.class);
                        i.putExtra(QrDisplayActivity.EXTRA_PAYLOAD, payload);
                        startActivity(i);
                        appendLog("Showing QR and advertising payload=" + payload);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnBleThenQr.setOnClickListener(v -> {
            if (!ensurePermissions()) return;
            appendLog("Starting BLE-first scan (waiting for close proximity)...");
            lastPayloadFound = null;
            qrScanInProgress = false;
            scanner.startScan();
            // timeout if we don't see close in time
            uiHandler.postDelayed(() -> {
                if (!qrScanInProgress) {
                    scanner.stopScan();
                    appendLog("BLE-first: timed out waiting for close - show failure");
                    // display failure in log window
                }
            }, BLE_WAIT_TIMEOUT_MS);
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
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN_QR) {
            qrScanInProgress = false;
            if (resultCode == RESULT_OK && data != null && data.hasExtra(QrScanActivity.EXTRA_QR_PAYLOAD)) {
                String scanned = data.getStringExtra(QrScanActivity.EXTRA_QR_PAYLOAD);
                appendLog("QR scanned: " + scanned + " expected=" + lastPayloadFound);
                if (lastPayloadFound != null && lastPayloadFound.equals(scanned)) {
                    appendLog("SUCCESS: QR matches BLE payload and device is within range");
                } else {
                    appendLog("FAILURE: QR does not match expected payload or payload missing");
                }
            } else {
                appendLog("QR scan cancelled or failed");
            }
        }
    }
}
