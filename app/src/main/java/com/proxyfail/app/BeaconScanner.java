package com.proxyfail.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class BeaconScanner {
    private static final String TAG = "BeaconScanner";
    private final Context context;
    private final BluetoothLeScanner bluetoothLeScanner;
    private final Handler scanHandler = new Handler();
    private Callback callback;
    private boolean isScanning = false;

    // optional expected service UUID (string form). If null — accept any.
    private final String expectedServiceUuid;

    // last found
    private String foundBeaconId = null;
    private int foundRssi = Integer.MIN_VALUE;

    // SCAN period
    private static final long SCAN_PERIOD = 10_000L;

    public interface Callback {
        void onBeaconFound(String beaconId, int rssi, String sessionIdFromBeacon);
        void onScanFailed(String error);
    }

    /**
     * @param context app context
     * @param expectedServiceUuid optional service UUID string (e.g. "1234..."). If non-null, scanner filters for that UUID.
     */
    public BeaconScanner(Context context, String expectedServiceUuid) {
        this.context = context;
        this.expectedServiceUuid = expectedServiceUuid;
        final BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            bluetoothLeScanner = null;
            Log.e(TAG, "Bluetooth not supported on this device.");
            return;
        }
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        bluetoothLeScanner = (bluetoothAdapter != null) ? bluetoothAdapter.getBluetoothLeScanner() : null;
    }

    public void startScan(@NonNull Callback callback) {
        if (bluetoothLeScanner == null) {
            callback.onScanFailed("Bluetooth LE not available.");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            callback.onScanFailed("Bluetooth scan permission missing.");
            return;
        }

        this.callback = callback;
        this.foundBeaconId = null;
        this.foundRssi = Integer.MIN_VALUE;

        Log.d(TAG, "Starting BLE scan (expected UUID=" + expectedServiceUuid + ")");

        // Schedule stop
        scanHandler.postDelayed(this::stopScan, SCAN_PERIOD);

        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        try {
            // NOTE: Not using ScanFilter here to maximize detection across devices.
            bluetoothLeScanner.startScan(null, settings, leScanCallback);
            isScanning = true;
        } catch (SecurityException e) {
            callback.onScanFailed("Security exception: Bluetooth permissions denied.");
        } catch (Exception e) {
            callback.onScanFailed("Failed to start scan: " + e.getMessage());
        }
    }

    public void stopScan() {
        if (isScanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner.stopScan(leScanCallback);
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when stopping scan.", e);
            } catch (Exception e) {
                Log.e(TAG, "Exception stopping scan", e);
            }
            isScanning = false;
        }
        scanHandler.removeCallbacks(this::stopScan);

        if (callback != null && foundBeaconId == null) {
            callback.onScanFailed("Beacon scan timed out or required beacon not found.");
        }
    }

    private final ScanCallback leScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            ScanRecord record = result.getScanRecord();
            if (record != null) {
                List<ParcelUuid> uuids = record.getServiceUuids();
                boolean uuidMatches = (expectedServiceUuid == null);
                String sessionIdFromBeacon = null;

                if (uuids != null) {
                    for (ParcelUuid uuid : uuids) {
                        if (uuid != null) {
                            String u = uuid.getUuid().toString();
                            if (expectedServiceUuid != null && expectedServiceUuid.equalsIgnoreCase(u)) {
                                uuidMatches = true;
                            }
                            // try to read serviceData; may be null
                            byte[] data = record.getServiceData(uuid);
                            if (data != null && data.length > 0) {
                                try {
                                    sessionIdFromBeacon = new String(data, StandardCharsets.UTF_8);
                                } catch (Exception ex) {
                                    // ignore, not critical
                                }
                            }
                        }
                    }
                }

                // If expectedServiceUuid was provided and didn't match, ignore this advertising packet.
                if (!uuidMatches) {
                    // still log
                    Log.d(TAG, "Advert ignored (UUID mismatch) device=" + (result.getDevice() != null ? result.getDevice().getAddress() : "null"));
                } else {
                    String beaconId = result.getDevice() != null ? result.getDevice().getAddress() : null;
                    int rssi = result.getRssi();

                    if (beaconId != null) {
                        foundBeaconId = beaconId;
                        foundRssi = rssi;

                        Log.d(TAG, "Beacon found: " + beaconId + " rssi=" + rssi + " sessionData=" + sessionIdFromBeacon);
                        if (callback != null) {
                            callback.onBeaconFound(beaconId, rssi, sessionIdFromBeacon);
                        }

                        // Once we found a matching beacon, stop scanning to save battery and avoid duplicates
                        stopScan();
                    }
                }
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            // no-op (individual onScanResult already handles)
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            stopScan();
            if (callback != null) {
                callback.onScanFailed("BLE Scan failed with code: " + errorCode);
            }
        }
    };
}
