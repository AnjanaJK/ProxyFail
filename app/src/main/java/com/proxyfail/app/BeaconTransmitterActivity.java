package com.proxyfail.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BeaconTransmitterActivity extends AppCompatActivity {
    private BluetoothLeAdvertiser advertiser;
    private static final String TAG = "BeaconTransmitterActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // This Activity will not directly start advertising any longer — use BeaconAdvertiserService
        // Prepare an Intent to start the service with dynamic UUID/session data
    }

    /**
     * Call this from your teacher UI when starting a session.
     * Pass the desired UUID and sessionId (or serviceData) to advertise.
     */
    public void startBeaconForSession(String serviceUuidStr, String sessionData) {
        Intent serviceIntent = new Intent(this, BeaconAdvertiserService.class);
        serviceIntent.putExtra(BeaconAdvertiserService.EXTRA_SERVICE_UUID, serviceUuidStr);
        if (sessionData != null) serviceIntent.putExtra(BeaconAdvertiserService.EXTRA_SESSION_DATA, sessionData);
        serviceIntent.putExtra(BeaconAdvertiserService.EXTRA_SHOW_DEVICE_NAME, false);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Log.d(TAG, "Requested beacon advertising start with UUID=" + serviceUuidStr);
    }

    public void stopBeacon() {
        Intent serviceIntent = new Intent(this, BeaconAdvertiserService.class);
        stopService(serviceIntent);
        Log.d(TAG, "Requested beacon advertising stop");
    }
}
