package com.proxyfail.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.annotation.RequiresPermission;

import java.io.File;

public class Utils {
    private static final String TAG = "Utils";

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public static boolean ensureBluetoothAndLocationEnabled(Context context) {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null) {
                Log.e(TAG, "Bluetooth adapter is null");
                return false;
            }

            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                Log.e(TAG, "Location Manager is null");
                return false;
            }

            boolean btEnabled = bluetoothAdapter.isEnabled();
            boolean locEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);

            if (!btEnabled) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                context.startActivity(enableBtIntent);
                Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_LONG).show();
            }

            if (!locEnabled) {
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                context.startActivity(intent);
                Toast.makeText(context, "Please enable Location", Toast.LENGTH_LONG).show();
            }

            return btEnabled && locEnabled;
        } catch (Exception e) {
            Log.e(TAG, "Error ensuring Bluetooth and Location enabled", e);
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public static boolean isMockLocationOn(Context context, Location location) {
        if (location == null) {
            Log.w(TAG, "Location is null");
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return location.isMock();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return location.isFromMockProvider();
            } else {
                try {
                    String mockLocation = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION);
                    return "1".equals(mockLocation);
                } catch (Exception e) {
                    Log.e(TAG, "Error checking mock location", e);
                    return false;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking mock location", e);
            return false;
        }
    }

    public static boolean isProbablyRooted() {
        try {
            String buildTags = android.os.Build.TAGS;
            if (buildTags != null && buildTags.contains("test-keys")) {
                return true;
            }

            String[] paths = {"/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su"};
            for (String path : paths) {
                File file = new File(path);
                if (file.exists()) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking root", e);
        }
        return false;
    }

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000; // Earth's radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}