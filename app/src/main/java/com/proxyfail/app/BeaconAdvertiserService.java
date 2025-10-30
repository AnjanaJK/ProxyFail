package com.proxyfail.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BeaconAdvertiserService extends Service {

    private static final String TAG = "BeaconAdvertiserService";
    private static final String CHANNEL_ID = "ble_advertising_channel";

    public static final String EXTRA_SERVICE_UUID = "EXTRA_SERVICE_UUID";
    public static final String EXTRA_SESSION_DATA = "EXTRA_SESSION_DATA"; // optional serviceData
    public static final String EXTRA_SHOW_DEVICE_NAME = "EXTRA_SHOW_DEVICE_NAME";

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    /**
     * Expects extras in the start intent:
     * - EXTRA_SERVICE_UUID (String) mandatory: UUID to advertise
     * - EXTRA_SESSION_DATA (String) optional: serviceData bytes (e.g. sessionId)
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String serviceUuidStr = null;
        String sessionData = null;
        boolean includeDeviceName = false;

        if (intent != null) {
            serviceUuidStr = intent.getStringExtra(EXTRA_SERVICE_UUID);
            sessionData = intent.getStringExtra(EXTRA_SESSION_DATA);
            includeDeviceName = intent.getBooleanExtra(EXTRA_SHOW_DEVICE_NAME, false);
        }

        if (serviceUuidStr == null || serviceUuidStr.isEmpty()) {
            Log.e(TAG, "Service UUID missing in intent. Stopping advertiser service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationForForeground();

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled() && bluetoothAdapter.isMultipleAdvertisementSupported()) {
                advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            } else {
                Log.e(TAG, "Bluetooth not supported/disabled or advertising not supported.");
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            Log.e(TAG, "BluetoothManager null");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Check permission (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_ADVERTISE permission not granted. Stopping service.");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true    )
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setTimeout(0)
                .build();

        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder()
                .setIncludeDeviceName(includeDeviceName)
                .setIncludeTxPowerLevel(true);

        try {
            dataBuilder.addServiceUuid(new android.os.ParcelUuid(UUID.fromString(serviceUuidStr)));
            if (sessionData != null && !sessionData.isEmpty()) {
                dataBuilder.addServiceData(new android.os.ParcelUuid(UUID.fromString(serviceUuidStr)), sessionData.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "Invalid UUID or failed to build serviceData", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        AdvertiseData data = dataBuilder.build();

        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                Log.d(TAG, "BLE Advertising started successfully");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                Log.e(TAG, "BLE Advertising failed: code=" + errorCode);
                stopSelf();
            }
        };

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
            Log.d(TAG, "Beacon advertiser started with UUID=" + serviceUuidStr);
        } catch (Exception e) {
            Log.e(TAG, "Exception starting advertising", e);
            stopSelf();
        }

        return START_STICKY;
    }

    private void createNotificationForForeground() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ProxyFail")
                .setContentText("BLE Beacon advertising active")
                .setSmallIcon(R.drawable.attendancee)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(1, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "BLE Advertising", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("BLE beacon advertising for attendance");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                advertiser.stopAdvertising(advertiseCallback);
                Log.d(TAG, "BLE Advertising stopped successfully");
            } catch (Exception e) {
                Log.e(TAG, "Exception stopping advertising", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAdvertising();
        Log.d(TAG, "BeaconAdvertiserService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
