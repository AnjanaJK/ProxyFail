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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BeaconAdvertiserService extends Service {

    private static final String TAG = "BeaconAdvertiserService";
    private static final String CHANNEL_ID = "BeaconAdvertiserChannel";

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback advertiseCallback;

    public static final String EXTRA_SERVICE_UUID = "EXTRA_SERVICE_UUID";
    public static final String EXTRA_SESSION_DATA = "EXTRA_SESSION_DATA";
    public static final String EXTRA_SHOW_DEVICE_NAME = "EXTRA_SHOW_DEVICE_NAME";

    // Shared constant UUID (same for both teacher and student)
    private static final ParcelUuid PROXYFAIL_UUID =
            new ParcelUuid(UUID.nameUUIDFromBytes("ProxyFail".getBytes(StandardCharsets.UTF_8)));

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, createNotification("Preparing beacon..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String sessionId = intent.getStringExtra("sessionId");
        if (sessionId != null) {
            // Delay start to ensure Bluetooth stack fully ready
            new Handler(Looper.getMainLooper()).postDelayed(() -> startAdvertising(sessionId), 700);
        } else {
            Log.e(TAG, "No sessionId provided for beacon advertising");
        }
        return START_STICKY;
    }

    private void startAdvertising(String sessionId) {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is off or unavailable");
            stopSelf();
            return;
        }

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising not supported on this device");
            stopSelf();
            return;
        }

        // ✅ Use short beacon payload under 10 bytes
        String beaconPayload = sessionId.length() > 6 ? sessionId.substring(0, 6) : sessionId;
        byte[] sessionBytes = beaconPayload.getBytes(StandardCharsets.UTF_8);

        // Shared constant UUID between teacher & student
        final ParcelUuid PROXYFAIL_UUID = new ParcelUuid(
                UUID.nameUUIDFromBytes("ProxyFail".getBytes(StandardCharsets.UTF_8))
        );

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build();

        // ✅ Keep data packet minimal — no device name, no extra UUIDs
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(PROXYFAIL_UUID, sessionBytes) // only serviceData (saves bytes)
                .setIncludeTxPowerLevel(false)
                .setIncludeDeviceName(false)
                .build();

        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "✅ Beacon advertising started successfully");
                updateNotification("Beacon active (Session ID: " + beaconPayload + ")");
            }

            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "❌ Beacon advertising failed: " + errorCode);
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    Log.e(TAG, "⚠️ Advertisement too large — retrying with shorter payload");
                }
                stopSelf();
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission missing: BLUETOOTH_ADVERTISE");
            stopSelf();
            return;
        }

        try {
            Log.d(TAG, "🛰 Starting advertiser with payload=" + beaconPayload);
            advertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException starting advertising", se);
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception", e);
            stopSelf();
        }
    }


    private void stopAdvertising() {
        if (advertiser != null && advertiseCallback != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ActivityCompat.checkSelfPermission(this,
                                Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Missing permission when stopping advertising");
                    return;
                }
                advertiser.stopAdvertising(advertiseCallback);
                Log.d(TAG, "Advertising stopped successfully");
            } catch (Exception e) {
                Log.w(TAG, "Error stopping advertising", e);
            } finally {
                advertiseCallback = null;
            }
        }
    }

    @Override
    public void onDestroy() {
        stopAdvertising();
        super.onDestroy();
    }

    private Notification createNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ProxyFail Beacon")
                .setContentText(text)
                .setSmallIcon(android.R.mipmap.sym_def_app_icon)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        Notification notification = createNotification(text);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "ProxyFail Beacon Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
