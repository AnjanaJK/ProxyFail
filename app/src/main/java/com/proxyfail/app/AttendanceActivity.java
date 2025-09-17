package com.proxyfail.app;

import android.Manifest;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityTokenResponse;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
// QR scanning temporarily disabled due to import issues
// import com.journeyapps.barcodescanner.IntentIntegrator;
// import com.journeyapps.barcodescanner.IntentResult;
import com.proxyfail.app.databinding.ActivityAttendanceBinding;

import java.util.HashMap;
import java.util.Map;

public class AttendanceActivity extends AppCompatActivity {
    private ActivityAttendanceBinding binding;
    private FusedLocationProviderClient fusedLocationClient;
    private FirebaseFirestore db;
    private IntegrityManager integrityManager;

    private Location lastLocation;
    private boolean deviceIntegrity = false;
    private boolean mockLocationDetected = false;

    ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAttendanceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        db = FirebaseFirestore.getInstance();
        integrityManager = IntegrityManagerFactory.create(this);

    permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            Boolean fine = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
            Boolean cam = result.getOrDefault(Manifest.permission.CAMERA, false);
            if (!fine) {
                showError("GPS permission required.");
            }
            if (!cam) {
                showError("Camera permission required for QR scanning.");
            }
        });

        binding.btnScanQr.setOnClickListener(v -> startQrScan());
        binding.btnGetLocation.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                fetchLocationAndCheck();
            } else {
                showError("Location permission required. Please grant permission first.");
                requestPermissionsIfNeeded();
            }
        });
        binding.btnSubmit.setOnClickListener(v -> submitAttendance());

        requestPermissionsIfNeeded();
        startIntegrityCheck();
    }

    private void requestPermissionsIfNeeded() {
        permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA});
    }

    private void startQrScan() {
        // QR scanning temporarily disabled - show message to user
        Toast.makeText(this, "QR scanning temporarily disabled. Please enter QR value manually.", Toast.LENGTH_LONG).show();
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private void fetchLocationAndCheck() {
        // Check if we have location permission
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showError("Location permission required. Please grant permission and try again.");
            return;
        }
        
        binding.progressBar.setVisibility(View.VISIBLE);
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    binding.progressBar.setVisibility(View.GONE);
                    if (location != null) {
                        lastLocation = location;
                        mockLocationDetected = Utils.isMockLocationOn(AttendanceActivity.this, location);
                        binding.tvLocation.setText(String.format("Lat: %.6f, Lng: %.6f, Mock: %b", location.getLatitude(), location.getLongitude(), mockLocationDetected));
                        if (mockLocationDetected) showError("Mock location detected. Cannot continue.");
                    } else {
                        showError("Could not get location. Ensure GPS is on and try again.");
                    }
                })
                .addOnFailureListener(e -> {
                    binding.progressBar.setVisibility(View.GONE);
                    showError("Location error: " + e.getMessage());
                });
    }

    // Play Integrity check (client-side quick check). In production, send token to server for verification.
    private void startIntegrityCheck() {
        // NOTE: In production get a secure nonce from your server.
        String nonce = "sample-nonce-"+System.currentTimeMillis();
        IntegrityTokenRequest request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build();

        Task<IntegrityTokenResponse> integrityTask = integrityManager.requestIntegrityToken(request);
        integrityTask.addOnCompleteListener(new OnCompleteListener<IntegrityTokenResponse>() {
            @Override
            public void onComplete(@NonNull Task<IntegrityTokenResponse> task) {
                if (task.isSuccessful()) {
                    String token = task.getResult().token();
                    // It's a JWS/JSON string. Basic local decode to check if token contains deviceIntegrity verdict.
                    try {
                        String[] parts = token.split("\\.");
                        if (parts.length >= 2) {
                            String payloadJson = new String(Base64.decode(parts[1], Base64.URL_SAFE));
                            // Parse
                            Map parsed = new Gson().fromJson(payloadJson, Map.class);
                            // This is a client-side heuristic. Server verification is recommended.
                            if (parsed != null && parsed.containsKey("deviceIntegrity")) {
                                deviceIntegrity = true;
                                binding.tvIntegrity.setText("Device integrity: likely OK");
                            } else {
                                deviceIntegrity = false;
                                binding.tvIntegrity.setText("Device integrity: WARNING");
                            }
                        }
                    } catch (Exception ex) {
                        deviceIntegrity = false;
                        binding.tvIntegrity.setText("Device integrity: ERROR");
                    }
                } else {
                    deviceIntegrity = false;
                    binding.tvIntegrity.setText("Device integrity: Failed");
                }
            }
        });
    }

    private void submitAttendance() {
        if (lastLocation == null) { showError("Please fetch location first."); return; }
        if (mockLocationDetected) { showError("Mock location detected. Cannot submit."); return; }
        if (Utils.isProbablyRooted()) { showError("Device appears rooted. Cannot submit."); return; }

        String qrVal = binding.etQrValue.getText().toString().trim();
        if (qrVal.isEmpty()) { showError("Scan QR or enter QR value first."); return; }

        String uid = FirebaseAuth.getInstance().getCurrentUser() != null ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null) { showError("Not authenticated."); return; }

        Map<String, Object> attendance = new HashMap<>();
        attendance.put("sessionId", binding.etSessionId.getText().toString().trim());
        attendance.put("studentId", uid);
        attendance.put("scannedQrValue", qrVal);
        attendance.put("latitude", lastLocation.getLatitude());
        attendance.put("longitude", lastLocation.getLongitude());
        attendance.put("deviceIntegrity", deviceIntegrity);
        attendance.put("mockLocationDetected", mockLocationDetected);
        attendance.put("timestamp", Timestamp.now());
        attendance.put("status", "pending");

        binding.progressBar.setVisibility(View.VISIBLE);
        db.collection("attendance").add(attendance)
                .addOnSuccessListener(docRef -> {
                    binding.progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Attendance submitted. Pending verification.", Toast.LENGTH_LONG).show();
                })
                .addOnFailureListener(e -> {
                    binding.progressBar.setVisibility(View.GONE);
                    showError("Submit failed: " + e.getMessage());
                });
    }

    private void showError(String msg) {
        binding.tvError.setText(msg);
        binding.tvError.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // QR scanning temporarily disabled
    }
}
