package com.proxyfail.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TeacherDashboardActivity extends AppCompatActivity {
    private static final String TAG = "TeacherDashboardActivity";
    private static final String BEACON_UUID = "12345678-1234-5678-1234-56789abcdef0";
    private static final int REQUEST_BEACON_PERMS = 201;

    private MaterialButton btnStartSession, btnEndSession, btnSignOut, btnScheduleNotifications, btnViewAttendance;
    private ImageView qrImage;
    private TextView tvSessionStatus, tvBeaconStatus;
    private TextView tvBeaconDevice, tvBeaconUuid, tvServiceDataHex, tvTxPower, tvAdvertiseMode, tvConnectable;
    private ProgressBar progressBar;

    private FirebaseAuth auth;
    private FirebaseFirestore db;

    private String activeSessionId = null;
    private String courseId = "CS401-FALL25";
    private boolean isBeaconRunning = false;

    // Permission launcher for Bluetooth advertising/connect (Android 12+)
    // Requests multiple permissions (BLUETOOTH_CONNECT + BLUETOOTH_ADVERTISE)
    // together
    private final ActivityResultLauncher<String[]> bluetoothPermissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!Boolean.TRUE.equals(granted)) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    // All required Bluetooth permissions granted -> continue starting session
                    startSession();
                } else {
                    Toast.makeText(this, "Bluetooth permissions are required to start the session.", Toast.LENGTH_LONG)
                            .show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_dashboard);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        btnStartSession = findViewById(R.id.btnStartSession);
        btnEndSession = findViewById(R.id.btnEndSession);
        btnSignOut = findViewById(R.id.btnSignOut);
        btnScheduleNotifications = findViewById(R.id.btnScheduleNotifications);
        btnViewAttendance = findViewById(R.id.btnViewAttendance);
        qrImage = findViewById(R.id.qrImage);
        tvSessionStatus = findViewById(R.id.tvSessionStatus);
        tvBeaconStatus = findViewById(R.id.tvBeaconStatus);

        // NEW fields (must exist in activity_teacher_dashboard.xml)
        tvBeaconDevice = findViewById(R.id.tvBeaconDevice);
        tvBeaconUuid = findViewById(R.id.tvBeaconUuid);
        tvServiceDataHex = findViewById(R.id.tvServiceDataHex);
        tvTxPower = findViewById(R.id.tvTxPower);
        tvAdvertiseMode = findViewById(R.id.tvAdvertiseMode);
        tvConnectable = findViewById(R.id.tvConnectable);

        progressBar = findViewById(R.id.progressBar);

        btnStartSession.setOnClickListener(v -> startSession());
        btnEndSession.setOnClickListener(v -> endSession());
        btnSignOut.setOnClickListener(v -> handleSignOut());
        btnScheduleNotifications
                .setOnClickListener(v -> startActivity(new Intent(this, CalendarNotificationActivity.class)));
        btnViewAttendance.setOnClickListener(v -> {
            Intent i = new Intent(this, TeacherAttendanceReviewActivity.class);
            if (activeSessionId != null)
                i.putExtra("SESSION_ID", activeSessionId);
            startActivity(i);
        });

        updateBeaconStatus(false);
        btnEndSession.setEnabled(false);

        // Attempt to request Bluetooth permissions on Android 12+ so we can read
        // adapter address
        ensureConnectPermissions();
    }

    private void handleSignOut() {
        if (isBeaconRunning)
            stopBeaconAdvertising();
        activeSessionId = null;
        isBeaconRunning = false;
        updateBeaconStatus(false);
        FirebaseAuth.getInstance().signOut();
        Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void startSession() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Please log in again", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Ensure both ADVERTISE and CONNECT permissions are available on Android 12+
        if (!ensureConnectPermissions()) {
            // permission request will continue via the registered launcher -> callback
            return;
        }

        // Quick pre-flight Bluetooth capability checks so teachers see explicit errors
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Toast.makeText(this, "Bluetooth not available on this device.", Toast.LENGTH_LONG).show();
            updateBeaconStatus(false);
            return;
        }
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth adapter not found.", Toast.LENGTH_LONG).show();
            updateBeaconStatus(false);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth and try again.", Toast.LENGTH_LONG).show();
            if (tvBeaconStatus != null)
                tvBeaconStatus.setText("Beacon: Bluetooth OFF");
            updateBeaconStatus(false);
            return;
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported()) {
            Toast.makeText(this, "This device does not support BLE advertising (peripheral mode).", Toast.LENGTH_LONG)
                    .show();
            if (tvBeaconStatus != null)
                tvBeaconStatus.setText("Beacon: Advertising not supported");
            updateBeaconStatus(false);
            return;
        }

        try {
            if (progressBar != null)
                progressBar.setVisibility(ProgressBar.VISIBLE);
            String qrCodeValue = "QR-" + UUID.randomUUID().toString().substring(0, 8);

            Map<String, Object> session = new HashMap<>();
            session.put("courseId", courseId);
            session.put("requiredBeaconServiceUuid", BEACON_UUID);
            session.put("qrCodeValue", qrCodeValue);
            session.put("isActive", true);
            session.put("teacherId", user.getUid());
            session.put("createdAt", FieldValue.serverTimestamp());

            db.collection("sessions").add(session)
                    .addOnSuccessListener(docRef -> {
                        activeSessionId = docRef.getId();
                        generateQRCode(qrCodeValue);

                        // Persist the required beacon serviceData (we use session id as serviceData)
                        final String serviceDataAscii = activeSessionId;
                        final String serviceDataHex = bytesToHex(serviceDataAscii.getBytes());

                        docRef.update("requiredBeaconServiceUuid", BEACON_UUID,
                                "requiredBeaconSessionData", serviceDataAscii)
                                .addOnSuccessListener(a -> Log.d(TAG, "Session doc updated with beacon info"))
                                .addOnFailureListener(e -> Log.w(TAG, "Failed to update session with beacon info", e));

                        // Update the UI with exact values for nRF Connect
                        if (tvBeaconUuid != null)
                            tvBeaconUuid.setText("Beacon UUID: " + BEACON_UUID);
                        if (tvServiceDataHex != null)
                            tvServiceDataHex.setText("ServiceData (hex): " + serviceDataHex);
                        if (tvTxPower != null)
                            tvTxPower.setText("TX Power: HIGH");
                        if (tvAdvertiseMode != null)
                            tvAdvertiseMode.setText("Advertise Mode: LOW_LATENCY");
                        if (tvConnectable != null)
                            tvConnectable.setText("Connectable: false");

                        // Try to display Bluetooth adapter address (may be restricted on Android S+)
                        if (tvBeaconDevice != null)
                            tvBeaconDevice.setText("Device: " + getLocalBluetoothAddress());

                        // Start advertising via in-app service (extras include service UUID and session
                        // data)
                        try {
                            startBeaconAdvertising();
                        } catch (Exception se) {
                            Log.e(TAG, "Failed to start beacon advertising", se);
                            Toast.makeText(this, "Failed to start beacon advertising: " + se.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            updateBeaconStatus(false);
                        }

                        if (progressBar != null)
                            progressBar.setVisibility(ProgressBar.GONE);
                        tvSessionStatus.setText("Session Active: " + activeSessionId);
                        btnEndSession.setEnabled(true);
                        Toast.makeText(this, "Session & Beacon started!", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> {
                        if (progressBar != null)
                            progressBar.setVisibility(ProgressBar.GONE);
                        Log.e(TAG, "Failed to start session", e);
                        Toast.makeText(this, "Failed to start session: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
        } catch (Exception ex) {
            Log.e(TAG, "Unhandled exception in startSession", ex);
            if (progressBar != null)
                progressBar.setVisibility(ProgressBar.GONE);
            updateBeaconStatus(false);
            Toast.makeText(this, "Failed to start session: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void endSession() {
        if (activeSessionId == null) {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show();
            return;
        }

        if (progressBar != null)
            progressBar.setVisibility(ProgressBar.VISIBLE);
        DocumentReference ref = db.collection("sessions").document(activeSessionId);
        ref.update("isActive", false, "endedAt", FieldValue.serverTimestamp())
                .addOnSuccessListener(aVoid -> {
                    stopBeaconAdvertising();
                    if (progressBar != null)
                        progressBar.setVisibility(ProgressBar.GONE);
                    tvSessionStatus.setText("Session ended.");
                    qrImage.setImageBitmap(null);
                    btnEndSession.setEnabled(false);
                    activeSessionId = null;
                })
                .addOnFailureListener(e -> {
                    if (progressBar != null)
                        progressBar.setVisibility(ProgressBar.GONE);
                    Log.e(TAG, "Failed to end session", e);
                    Toast.makeText(this, "Failed to end session: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void startBeaconAdvertising() {
        // Use the existing BeaconAdvertiserService (it reads extras for service UUID
        // and session data)
        startServiceWithExtras();
        isBeaconRunning = true;
        updateBeaconStatus(true);
    }

    // 🧠 AUTO-BLUETOOTH RESTART + DELAY FIX FOR ANDROID 12
    private void startServiceWithExtras() {
        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter btAdapter = btManager != null ? btManager.getAdapter() : null;

        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not available on this device", Toast.LENGTH_SHORT).show();
            return;
        }

        // Android 12+ permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, perms, 101);
                    Toast.makeText(this, "Grant Bluetooth permissions and retry", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }

        // If Bluetooth is OFF, enable it first
        if (!btAdapter.isEnabled()) {
            Toast.makeText(this, "Turning Bluetooth ON...", Toast.LENGTH_SHORT).show();
            btAdapter.enable(); // programmatic enable — allowed for system apps & most OEMs
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                waitForBluetoothReadyAndStart(btAdapter);
            }, 2000); // small delay before polling
        } else {
            waitForBluetoothReadyAndStart(btAdapter);
        }
    }

    // Waits for adapter to reach STATE_ON then launches service
    private void waitForBluetoothReadyAndStart(BluetoothAdapter btAdapter) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            int attempts = 0;
            @Override
            public void run() {
                attempts++;
                if (btAdapter.isEnabled() && btAdapter.getBluetoothLeAdvertiser() != null) {
                    Log.d("TeacherDashboardActivity", "Bluetooth ready — launching beacon service!");
                    launchBeaconService();
                } else if (attempts < 6) { // retry up to ~3 seconds
                    handler.postDelayed(this, 500);
                } else {
                    Toast.makeText(TeacherDashboardActivity.this,
                            "Bluetooth not ready. Try again.", Toast.LENGTH_SHORT).show();
                }
            }
        }, 500);
    }

    // Finally starts the beacon service cleanly
    private void launchBeaconService() {
        Intent i = new Intent(this, BeaconAdvertiserService.class);
        i.putExtra("sessionId", activeSessionId); // your teacher’s current session ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(i);
        else
            startService(i);

        Toast.makeText(this, "Beacon advertising started", Toast.LENGTH_SHORT).show();
    }


    private void stopBeaconAdvertising() {
        stopService(new Intent(this, BeaconAdvertiserService.class));
        isBeaconRunning = false;
        updateBeaconStatus(false);
    }

    private void updateBeaconStatus(boolean ready) {
        if (tvBeaconStatus != null) {
            tvBeaconStatus.setText(ready ? "Beacon: Ready ✅" : "Beacon: Not Ready ❌");
            tvBeaconStatus.setTextColor(ContextCompat.getColor(this,
                    ready ? android.R.color.holo_green_dark : android.R.color.holo_red_dark));
        }
    }

    private void generateQRCode(String data) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, 512, 512);
            Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565);
            for (int x = 0; x < 512; x++)
                for (int y = 0; y < 512; y++)
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            qrImage.setImageBitmap(bitmap);
        } catch (WriterException e) {
            Log.e(TAG, "QR Generation failed", e);
        }
    }

    // Helper: convert bytes to lowercase hex string (no separators)
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    // Helper: attempt to read local Bluetooth adapter address (may be restricted on
    // Android S+)
    private String getLocalBluetoothAddress() {
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null)
                return "N/A";
            BluetoothAdapter adapter = bm.getAdapter();
            if (adapter == null)
                return "N/A";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return "BLUETOOTH_CONNECT required";
                }
            }
            String addr = adapter.getAddress();
            return addr == null ? "unknown" : addr;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read Bluetooth address", e);
            return "unknown";
        }
    }

    // Ensure Bluetooth CONNECT + ADVERTISE permissions on Android S+
    private boolean ensureConnectPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            return true;
        boolean connectGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        boolean advertiseGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
        if (connectGranted && advertiseGranted)
            return true;
        // Request both permissions together; callback will re-enter startSession on
        // success
        bluetoothPermissionsLauncher.launch(new String[] { Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE });
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBeaconRunning)
            stopBeaconAdvertising();
    }
}
