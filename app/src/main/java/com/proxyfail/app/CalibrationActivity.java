package com.proxyfail.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.content.Context;
import android.os.Handler;
import java.util.ArrayList;
import java.util.List;

public class CalibrationActivity extends Activity {
    private BeaconScanner scanner;
    private TextView tvStatus;
    private Button btnStart;
    private SharedPreferences prefs;
    private final List<Integer> collected = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // reuse layout for simplicity
        tvStatus = findViewById(R.id.tvLog);
        btnStart = findViewById(R.id.btnStartScan);
        prefs = getSharedPreferences("beacon_prefs", Context.MODE_PRIVATE);

        scanner = new BeaconScanner(this, new BeaconScanner.Listener() {
            @Override
            public void onBeaconFound(String payload, int rssi) {
                // handled in onProximityUpdate
            }

            @Override
            public void onScanError(String reason) {
                runOnUiThread(() -> tvStatus.append("Calib scan error: " + reason + "\n"));
            }

            @Override
            public void onProximityUpdate(int rawRssi, double smoothedRssi, String decision, int[] recentSamples) {
                // collect recent samples into our list
                runOnUiThread(() -> tvStatus.append("Calib rssi: " + rawRssi + " smoothed:" + String.format("%.1f", smoothedRssi) + "\n"));
                synchronized (collected) {
                    for (int s : recentSamples) collected.add(s);
                }
            }
        });

        btnStart.setOnClickListener(v -> {
            tvStatus.setText("Calibrating: stand at 1 meter and tap Start Scanning...\n");
            // run short scan and gather samples then save median as txPower proxy
            collected.clear();
            scanner.startScan();
            new Handler().postDelayed(() -> {
                scanner.stopScan();
                int[] arr;
                synchronized (collected) {
                    arr = new int[collected.size()];
                    for (int i=0;i<collected.size();i++) arr[i] = collected.get(i);
                }
                if (arr.length == 0) {
                    Toast.makeText(this, "No samples collected, try again", Toast.LENGTH_LONG).show();
                    return;
                }
                java.util.Arrays.sort(arr);
                int median = arr[arr.length/2];
                prefs.edit().putInt("calibrated_tx_power", median).apply();
                Toast.makeText(this, "Calibration saved (median RSSI) = " + median, Toast.LENGTH_LONG).show();
            }, 5000);
        });
    }
}
