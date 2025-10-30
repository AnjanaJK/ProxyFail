package com.proxyfail.app;

import android.app.Application;
import android.util.Log;
import androidx.annotation.NonNull; // Good practice for clarity

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.messaging.FirebaseMessaging;

// Import the correct, non-deprecated API for Google Play Services check
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.ConnectionResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class App extends Application {
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Initialize Firebase
        // This is crucial and must be done before other Firebase services are accessed
        FirebaseApp.initializeApp(this);

        // 2. Setup the custom crash logger first, so it can catch exceptions
        setupCrashLogger();

        // 3. Check Google Play Services availability (needed for FCM, etc.)
        checkGooglePlayServices();

        // 4. Re-register FCM token if user is already signed in
        registerFcmTokenIfLoggedIn();
    }

    // --- Private Methods ---

    /**
     * Checks for Google Play Services availability using the current API (GoogleApiAvailability).
     * Logs the status or error details.
     */
    private void checkGooglePlayServices() {
        try {
            // Get the singleton instance of GoogleApiAvailability
            GoogleApiAvailability api = GoogleApiAvailability.getInstance();
            int resultCode = api.isGooglePlayServicesAvailable(this);

            if (resultCode != ConnectionResult.SUCCESS) {
                Log.w(TAG, "Google Play Services not available. Code: " + resultCode);

                // Determine if the user can resolve the issue (e.g., update the services)
                if (api.isUserResolvableError(resultCode)) {
                    // In a real Activity, you would show the dialog:
                    // api.showErrorDialogFragment(activity, resultCode, REQUEST_CODE);
                    Log.w(TAG, "Google Play Services error is user-resolvable");
                } else {
                    Log.e(TAG, "Google Play Services not supported on this device");
                }
            } else {
                Log.d(TAG, "Google Play Services is available: " + api.getErrorString(resultCode));
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error checking Google Play Services", e);
        }
    }

    /**
     * Checks if a user is logged in and, if so, retrieves the FCM token and updates
     * it in Firestore for targeted messaging.
     * This helps ensure the app has a fresh token on startup.
     */
    private void registerFcmTokenIfLoggedIn() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user != null) {
            Log.d(TAG, "User logged in. Attempting to get/register FCM token.");
            FirebaseMessaging.getInstance().getToken()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            String token = task.getResult();
                            FirebaseFirestore db = FirebaseFirestore.getInstance();
                            String userId = user.getUid();

                            Map<String, Object> data = new HashMap<>();
                            data.put("uid", userId);
                            // Using FieldValue.serverTimestamp() is best practice
                            data.put("createdAt", FieldValue.serverTimestamp());

                            // Use the token as the document ID for easy lookup/targeting
                            db.collection("deviceTokens").document(token)
                                    .set(data)
                                    .addOnSuccessListener(aVoid ->
                                            Log.d(TAG, "FCM token re-registered on app start for uid: " + userId))
                                    .addOnFailureListener(e ->
                                            Log.e(TAG, "Failed to re-register FCM token on app start", e));
                        } else {
                            Log.e(TAG, "FCM token retrieval failed: ", task.getException());
                        }
                    });
        }
    }

    /**
     * Installs a default uncaught exception handler to log stack traces to an internal file.
     * This is useful for getting crash logs from users who can't provide logcat output.
     */
    private void setupCrashLogger() {
        // Keep a reference to any previous handler (e.g., Firebase Crashlytics)
        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((@NonNull Thread thread, @NonNull Throwable throwable) -> {
            // Log the crash details to a file in internal storage (crash_log.txt)
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                String stack = sw.toString();

                File out = new File(getFilesDir(), "crash_log.txt");
                try (FileOutputStream fos = new FileOutputStream(out, true)) {
                    fos.write(("--- Crash at " + System.currentTimeMillis() + " ---\n").getBytes());
                    fos.write(stack.getBytes());
                    fos.write('\n');
                    Log.e(TAG, "Crash log written to crash_log.txt");
                }
            } catch (Exception e) {
                // Best-effort only; log the failure to write the crash, but don't rethrow
                Log.e(TAG, "Failed to write crash log to file", e);
            }

            // Delegate to the previous handler (e.g., Crashlytics) to ensure it can report the crash
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                // No previous handler: let the JVM exit gracefully
                System.exit(2);
            }
        });
    }
}