package com.proxyfail.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import android.util.JsonWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.StringWriter;
import java.io.IOException;

public class BeaconScanner {
    private static final String TAG = "BeaconScanner";
    private final Context ctx;
    private final Listener listener;
    private BluetoothLeScanner scanner;
    private final ScanCallback scanCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // smoothing / buffer
    private final int BUFFER_N = 7;
    private final int[] buffer = new int[BUFFER_N];
    private int bufferPos = 0;
    private int bufferCount = 0;
    private double ema = Double.NaN;
    // configurable EMA and thresholds (defaults)
    private double emaAlphaRise = 0.6;
    private double emaAlphaFall = 0.25;
    private int consecutiveCloseCount = 0;
    private int consecutiveRequired = 2; // default
    private int closeThreshold = -65;
    private int farThreshold = -80;
    private boolean telemetryEnabled = false;

    // scan loop control (short windows with small pause) so UI stays responsive
    private boolean loopScanning = false;
    private boolean isScanning = false;
    private final int SCAN_WINDOW_MS = 4_000;
    private final int SCAN_PAUSE_MS = 500;

    public interface Listener {
        void onBeaconFound(String payload, int rssi);
        void onScanError(String reason);
        // median is the median of recent raw samples (robust to spikes)
        void onProximityUpdate(int rawRssi, double smoothedRssi, int median, String decision, int[] recentSamples);
    }

    public BeaconScanner(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) scanner = adapter.getBluetoothLeScanner();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result == null || result.getScanRecord() == null) return;
                byte[] sd = result.getScanRecord().getServiceData(android.os.ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB"));
                if (sd != null) {
                    String payload = new String(sd, StandardCharsets.UTF_8);
                    int rssi = result.getRssi();
                    processRssiAndNotify(payload, rssi);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                listener.onScanError("scan failed: " + errorCode);
            }
        };
    }

    private void processRssiAndNotify(String payload, int rssi) {
        // update buffer
        buffer[bufferPos] = rssi;
        bufferPos = (bufferPos + 1) % BUFFER_N;
        if (bufferCount < BUFFER_N) bufferCount++;

        // EMA (asymmetric: respond faster to rising RSSI so 'approach' is detected quickly)
        if (Double.isNaN(ema)) ema = rssi;
        else {
            if (rssi > ema) {
                ema = emaAlphaRise * rssi + (1 - emaAlphaRise) * ema;
            } else {
                ema = emaAlphaFall * rssi + (1 - emaAlphaFall) * ema;
            }
        }

        // compute median of available samples for robustness
        int[] samples = new int[bufferCount];
        for (int i=0;i<bufferCount;i++) samples[i] = buffer[(bufferPos - bufferCount + i + BUFFER_N) % BUFFER_N];
        int median = computeMedian(samples);

        // decision logic
        String decision;
        // consecutive logic: require a small run of close readings, reset quickly when below close
        if (ema >= closeThreshold) {
            consecutiveCloseCount++;
        } else {
            consecutiveCloseCount = 0;
        }

        if (consecutiveCloseCount >= consecutiveRequired) decision = "close";
        else if (ema < farThreshold) decision = "far";
        else decision = "uncertain";

        // notify listener
        listener.onBeaconFound(payload, rssi);
        listener.onProximityUpdate(rssi, ema, median, decision, samples);
    }

    // setters so tuning UI can apply values at runtime
    public void setEmaAlphas(double rise, double fall) {
        this.emaAlphaRise = rise;
        this.emaAlphaFall = fall;
    }

    public void setThresholds(int close, int far) {
        this.closeThreshold = close;
        this.farThreshold = far;
    }

    public void setConsecutiveRequired(int n) {
        this.consecutiveRequired = n;
    }

    private int computeMedian(int[] arr) {
        if (arr.length == 0) return 0;
        int[] copy = arr.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length/2];
    }

    public void startScan() {
        if (scanner == null) {
            listener.onScanError("no scanner available");
            return;
        }
        try {
            // start repeating short scan windows (scan 4s, pause 500ms)
            if (loopScanning) return; // already running
            loopScanning = true;
            startScanInternal();
        } catch (SecurityException | IllegalStateException e) {
            listener.onScanError("startScan exception: " + e.getMessage());
        }
    }

    // stop the repeating scan windows
    public void stopScan() {
        loopScanning = false;
        handler.removeCallbacksAndMessages(null);
        if (scanner != null && isScanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception e) {
                Log.w(TAG, "stopScan exception", e);
            }
            isScanning = false;
        }
    }

    // internal: start one scan window and schedule stop
    private void startScanInternal() {
        if (scanner == null) return;
        if (isScanning) return;
        try {
            scanner.startScan(scanCallback);
            isScanning = true;
            handler.postDelayed(this::stopScanInternal, SCAN_WINDOW_MS);
        } catch (SecurityException | IllegalStateException e) {
            listener.onScanError("startScan internal exception: " + e.getMessage());
            // stop loop to avoid repeated exceptions
            loopScanning = false;
        }
    }

    private void stopScanInternal() {
        if (scanner == null) return;
        if (!isScanning) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception e) {
            Log.w(TAG, "stopScanInternal exception", e);
        }
        isScanning = false;
        if (loopScanning) {
            handler.postDelayed(this::startScanInternal, SCAN_PAUSE_MS);
        }
    }

    // telemetry: write a small JSON record to app external files dir
    public void writeTelemetry(int rawRssi, double smoothedRssi, int[] recentSamples, String deviceModel) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            File out = new File(dir, "beacon_telemetry.log");
            StringWriter sw = new StringWriter();
            JsonWriter jw = new JsonWriter(sw);
            jw.beginObject();
            jw.name("timestamp").value(System.currentTimeMillis());
            jw.name("device").value(deviceModel);
            jw.name("rawRssi").value(rawRssi);
            jw.name("smoothedRssi").value(smoothedRssi);
            jw.name("samples"); jw.beginArray();
            for (int s : recentSamples) jw.value(s);
            jw.endArray();
            jw.endObject(); jw.close();
            String line = sw.toString();
            FileWriter fw = new FileWriter(out, true);
            fw.write(line + "\n");
            fw.close();
            Log.i(TAG, "Wrote telemetry: " + line);
        } catch (IOException e) {
            Log.w(TAG, "telemetry write failed", e);
        }
    }
}
