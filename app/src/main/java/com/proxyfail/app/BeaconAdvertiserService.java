package com.proxyfail.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.charset.StandardCharsets;

public class BeaconAdvertiserService extends Service {
    private static final String TAG = "BeaconAdvertiserSvc";
    private static final String ACTION_START = "com.proxyfail.app.action.START";
    private static final String ACTION_STOP = "com.proxyfail.app.action.STOP";
    private static final String EXTRA_PAYLOAD = "com.proxyfail.app.extra.PAYLOAD";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "beacon_adv_channel";

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback callback;
    private String advertisingPayload = null;

    public static void startAdvertising(Context ctx) {
        // default payload
        startAdvertising(ctx, "beacon-sample-v1");
    }

    public static void startAdvertising(Context ctx, String payload) {
        Intent i = new Intent(ctx, BeaconAdvertiserService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_PAYLOAD, payload);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i); else ctx.startService(i);
    }

    public static void stopAdvertising(Context ctx) {
        Intent i = new Intent(ctx, BeaconAdvertiserService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) {
            advertiser = adapter.getBluetoothLeAdvertiser();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            // capture payload from intent extras for use when starting advertisement
            if (intent.hasExtra(EXTRA_PAYLOAD)) advertisingPayload = intent.getStringExtra(EXTRA_PAYLOAD);
            startForegroundIfNeeded();
            startAdvertisingInternal();
        } else if (ACTION_STOP.equals(action)) {
            stopAdvertisingInternal();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startForegroundIfNeeded() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Beacon advertiser", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Beacon Advertiser")
                .setContentText("Advertising beacon payload")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .build();
        startForeground(NOTIF_ID, n);
    }

    private void startAdvertisingInternal() {
        if (advertiser == null) {
            Log.w(TAG, "No BLE advertiser available");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        // payload may have been captured in onStartCommand
        String payload = advertisingPayload != null ? advertisingPayload : "beacon-sample-v1";
        byte[] serviceData = payload.getBytes(StandardCharsets.UTF_8);

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(android.os.ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB"), serviceData)
                .setIncludeDeviceName(false)
                .build();

        callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                Log.i(TAG, "Advertise started");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                Log.w(TAG, "Advertise failed: " + errorCode);
            }
        };

        try {
            advertiser.startAdvertising(settings, data, callback);
            Log.i(TAG, "startAdvertising() invoked");
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "startAdvertising exception", e);
        }
    }

    private void stopAdvertisingInternal() {
        if (advertiser != null && callback != null) {
            try {
                advertiser.stopAdvertising(callback);
                Log.i(TAG, "Stopped advertising");
            } catch (Exception e) {
                Log.w(TAG, "stopAdvertising exception", e);
            }
            callback = null;
        }
    }

    @Override
    public void onDestroy() {
        stopAdvertisingInternal();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
