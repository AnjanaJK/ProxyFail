package com.proxyfail.app;

import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.provider.Settings;
import java.io.File;

public class Utils {

    public static boolean isMockLocationOn(Context context, Location location) {
        if (location == null) return false;
        if (Build.VERSION.SDK_INT >= 18) {
            return location.isFromMockProvider();
        } else {
            // Older devices
            return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION) != null;
        }
    }

    public static boolean isProbablyRooted() {
        // Simple root checks (not exhaustive)
        String buildTags = android.os.Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) return true;
        String[] paths = {"/system/app/Superuser.apk","/sbin/su","/system/bin/su","/system/xbin/su","/data/local/xbin/su","/data/local/bin/su"};
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        return false;
    }

    // Haversine distance in meters
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000; // meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }
}
