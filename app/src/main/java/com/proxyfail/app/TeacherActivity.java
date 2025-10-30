package com.proxyfail.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.firestore.FieldValue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeacherActivity extends AppCompatActivity {

    private static final String TAG = "TeacherActivity";
    private static final String TEACHER_COURSE_ID = "CS401-FALL25"; // Must match student's course ID
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_BLUETOOTH_ENABLE = 101;

    // Use a fixed UUID instead of MAC address (more reliable across Android versions)
    private static final String BEACON_UUID = "12345678-1234-5678-1234-56789abcdef0";

    private TextView statusText;
    private Button startStopButton;
    private Button btnStartBeacon;
    private Button btnStopBeacon;
    private FirebaseFirestore db;
    private FirebaseAuth auth;
    private FusedLocationProviderClient fusedLocationClient;

    private boolean isSessionActive = false;
    private String currentSessionId = null;
    private boolean isBeaconServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher);

        initializeViews();
        initializeFirebase();
        setupClickListeners();
        checkSessionStatus();
    }

    private void initializeViews() {
        statusText = findViewById(R.id.teacherStatusText);
        startStopButton = findViewById(R.id.startStopButton);
        btnStartBeacon = findViewById(R.id.btnStartBeacon);
        btnStopBeacon = findViewById(R.id.btnStopBeacon);

        // Initially disable stop beacon button
        if (btnStopBeacon != null) {
            btnStopBeacon.setEnabled(false);
        }
    }

    private void initializeFirebase() {
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    private void setupClickListeners() {
        startStopButton.setOnClickListener(v -> toggleSession());

        if (btnStartBeacon != null) {
            btnStartBeacon.setOnClickListener(v -> startBeaconAdvertising());
        }

        if (btnStopBeacon != null) {
            btnStopBeacon.setOnClickListener(v -> stopBeaconAdvertising());
        }
    }

    // ==================== Session State Management ====================

    private void checkSessionStatus() {
        statusText.setText("Checking for active sessions...");

        db.collection("sessions")
                .whereEqualTo("courseId", TEACHER_COURSE_ID)
                .whereEqualTo("isActive", true)
                .limit(1)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (!querySnapshot.isEmpty()) {
                        currentSessionId = querySnapshot.getDocuments().get(0).getId();
                        isSessionActive = true;
                        updateUI(true, "Active Session: " + currentSessionId);
                    } else {
                        updateUI(false, "Ready to start class " + TEACHER_COURSE_ID);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error checking session status", e);
                    statusText.setText("Error: Could not check session status.");
                });
    }

    private void toggleSession() {
        if (isSessionActive) {
            stopSession();
        } else {
            if (checkAllPermissions()) {
                startSession();
            } else {
                requestAllPermissions();
            }
        }
    }

    private void updateUI(boolean active, String status) {
        isSessionActive = active;
        statusText.setText(status);

        if (active) {
            startStopButton.setText("STOP SESSION");
            startStopButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light));
        } else {
            startStopButton.setText("START SESSION");
            startStopButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light));
        }
    }

    // ==================== Beacon Advertising ====================

    private void startBeaconAdvertising() {
        if (isBeaconServiceRunning) {
            Toast.makeText(this, "Beacon already running", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check Bluetooth availability
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth adapter not available", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_BLUETOOTH_ENABLE);
            return;
        }

        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "BLUETOOTH_ADVERTISE permission required", Toast.LENGTH_SHORT).show();
                requestAllPermissions();
                return;
            }
        }

        // Check if advertising is supported
        if (!bluetoothAdapter.isMultipleAdvertisementSupported()) {
            Toast.makeText(this, "BLE advertising not supported on this device", Toast.LENGTH_LONG).show();
            return;
        }

        // Start the beacon service
        try {
            Intent serviceIntent = new Intent(this, BeaconAdvertiserService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            isBeaconServiceRunning = true;
            btnStartBeacon.setEnabled(false);
            btnStopBeacon.setEnabled(true);
            Toast.makeText(this, "Beacon advertising started", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Beacon service started with UUID: " + BEACON_UUID);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start beacon service", e);
            Toast.makeText(this, "Failed to start beacon: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopBeaconAdvertising() {
        try {
            Intent serviceIntent = new Intent(this, BeaconAdvertiserService.class);
            stopService(serviceIntent);

            isBeaconServiceRunning = false;
            btnStartBeacon.setEnabled(true);
            btnStopBeacon.setEnabled(false);
            Toast.makeText(this, "Beacon advertising stopped", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Beacon service stopped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop beacon service", e);
            Toast.makeText(this, "Failed to stop beacon: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== Start Session Logic ====================

    private void startSession() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show();
            requestAllPermissions();
            return;
        }

        statusText.setText("Getting location...");

        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null) {
                // Use the fixed BEACON_UUID instead of MAC address
                String beaconIdentifier = BEACON_UUID;

                // Create Session Data
                Map<String, Object> sessionData = new HashMap<>();
                sessionData.put("courseId", TEACHER_COURSE_ID);
                sessionData.put("isActive", true);
                sessionData.put("location", new GeoPoint(location.getLatitude(), location.getLongitude()));
                sessionData.put("requiredBeaconId", beaconIdentifier);
                sessionData.put("allowedRadiusMeters", 50);
                sessionData.put("minRequiredRssi", -80);
                sessionData.put("createdAt", FieldValue.serverTimestamp());

                // Submit to Firestore
                db.collection("sessions").add(sessionData)
                        .addOnSuccessListener(documentReference -> {
                            currentSessionId = documentReference.getId();
                            String statusMsg = String.format(
                                    "Session Started!\nID: %s\nLocation: %.4f, %.4f\nBeacon: %s",
                                    currentSessionId,
                                    location.getLatitude(),
                                    location.getLongitude(),
                                    beaconIdentifier.substring(0, 8) + "..."
                            );
                            updateUI(true, statusMsg);
                            Toast.makeText(this, "Session started successfully!", Toast.LENGTH_LONG).show();
                            Log.d(TAG, "Session created: " + currentSessionId);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Error starting session", e);
                            statusText.setText("Failed to start session: " + e.getMessage());
                            Toast.makeText(this, "DB Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
            } else {
                statusText.setText("Could not get GPS location. Enable GPS and try again.");
                Toast.makeText(this, "Location unavailable. Enable GPS.", Toast.LENGTH_LONG).show();
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get location", e);
            statusText.setText("Location error: " + e.getMessage());
        });
    }

    // ==================== Stop Session Logic ====================

    private void stopSession() {
        if (currentSessionId == null) {
            updateUI(false, "Session already stopped.");
            return;
        }

        statusText.setText("Stopping session...");

        db.collection("sessions").document(currentSessionId)
                .update("isActive", false, "endedAt", FieldValue.serverTimestamp())
                .addOnSuccessListener(aVoid -> {
                    currentSessionId = null;
                    updateUI(false, "Session successfully stopped.");
                    Toast.makeText(this, "Session stopped.", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Session stopped");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error stopping session", e);
                    statusText.setText("Failed to stop session: " + e.getMessage());
                });
    }

    // ==================== Permissions ====================

    private boolean checkAllPermissions() {
        boolean locationGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean bluetoothConnectGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            boolean bluetoothAdvertiseGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;

            return locationGranted && bluetoothConnectGranted && bluetoothAdvertiseGranted;
        }

        return locationGranted;
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] permissions = {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN
            };
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        } else {
            String[] permissions = {
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, "Permissions granted. You can now start session.",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some permissions denied. App may not work correctly.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_BLUETOOTH_ENABLE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
                // Try starting beacon again
                startBeaconAdvertising();
            } else {
                Toast.makeText(this, "Bluetooth is required for beacon advertising",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ==================== Lifecycle ====================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up beacon service if running
        if (isBeaconServiceRunning) {
            stopBeaconAdvertising();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh session status
        if (!isSessionActive) {
            checkSessionStatus();
        }
    }
}