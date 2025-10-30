package com.proxyfail.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class AttendanceActivity extends AppCompatActivity implements BeaconScanner.Callback {

    private static final String TAG = "AttendanceActivity";
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1001;
    private static final int REQUEST_LOCATION_PERMISSIONS = 1002;
    private static final int REQUEST_QR_SCAN = 2001;
    private static final int REQUEST_ENABLE_BLUETOOTH = 3001;

    // UI Components
    private TextView statusText;
    private TextView tvSessionStatus;
    private TextView tvIntegrity;
    private TextView tvBeacon;
    private TextView tvLocation;
    private TextView tvError;
    private TextInputEditText etSessionId;
    private TextInputEditText etQrValue;
    private MaterialButton btnScanQr;
    private androidx.recyclerview.widget.RecyclerView rvSessions;
    private SessionsAdapter sessionsAdapter;
    private MaterialButton btnGetLocation;
    private MaterialButton btnSubmit;
    private MaterialCardView cardError;
    private ProgressBar progressBar;

    // Firebase & Services
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FusedLocationProviderClient fusedLocationClient;
    private BeaconScanner beaconScanner;
    private ListenerRegistration sessionListener;

    // Session Data
    private String scannedQrValue = null;
    private String activeSessionId = null;
    private String requiredBeaconServiceUuid = null; // service UUID (string) expected by this session
    private String requiredBeaconSessionData = null; // optional session id encoded in serviceData
    private String requiredBeaconMac = null; // optional expected BLE device address (if teacher pins a MAC)
    private Long allowedRadiusMeters = null;
    private Integer minRequiredRssi = null;
    private Location sessionLocation = null;

    // Last beacon seen for validation
    private String lastSeenBeaconMac = null;
    private int lastSeenRssi = Integer.MIN_VALUE;

    private Location currentLocation = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attendance);

        // Initialize Firebase + services
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        if (!checkAuthentication())
            return;

        initializeViews();
        setupClickListeners();

        // Get course ID (from StudentDashboard Intent)
        if (getIntent().hasExtra(StudentDashboardActivity.EXTRA_COURSE_ID)) {
            String studentCourseId = getIntent().getStringExtra(StudentDashboardActivity.EXTRA_COURSE_ID);
            if (studentCourseId != null) {
                startSessionListenerForCourse(studentCourseId);
            } else {
                showError("Course id missing from Intent");
            }
        } else {
            showError("Course ID not provided. Open attendance from dashboard.");
        }
    }

    private boolean checkAuthentication() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return false;
        }
        return true;
    }

    private void initializeViews() {
        try {
            statusText = findViewById(R.id.statusText);
            tvSessionStatus = findViewById(R.id.tvSessionStatus);
            tvIntegrity = findViewById(R.id.tvIntegrity);
            tvBeacon = findViewById(R.id.tvBeacon);
            tvLocation = findViewById(R.id.tvLocation);
            tvError = findViewById(R.id.tvError);

            etSessionId = findViewById(R.id.etSessionId);
            etQrValue = findViewById(R.id.etQrValue);

            btnScanQr = findViewById(R.id.btnScanQr);
            btnGetLocation = findViewById(R.id.btnGetLocation);
            btnSubmit = findViewById(R.id.btnSubmit);

            rvSessions = findViewById(R.id.rvSessions);
            rvSessions.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
            sessionsAdapter = new SessionsAdapter(new java.util.ArrayList<>());
            rvSessions.setAdapter(sessionsAdapter);

            cardError = findViewById(R.id.cardError);
            progressBar = findViewById(R.id.progressBar);

            if (etSessionId != null)
                etSessionId.setEnabled(false);
            if (etQrValue != null)
                etQrValue.setEnabled(false);

            if (cardError != null)
                cardError.setVisibility(View.GONE);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing views", e);
            Toast.makeText(this, "UI initialization failed", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupClickListeners() {
        if (btnScanQr != null)
            btnScanQr.setOnClickListener(v -> startQrScan());
        if (btnGetLocation != null)
            btnGetLocation.setOnClickListener(v -> requestLocationUpdate());
        if (btnSubmit != null)
            btnSubmit.setOnClickListener(v -> handleManualSubmit());
    }

    /**
     * Start a Firestore listener for the active session for the given course.
     * When an active session appears we fetch its protection fields (beacon UUID,
     * allowedRadius, etc).
     */
    private void startSessionListenerForCourse(String courseId) {
        updateStatus("Finding active session for " + courseId + "...");
        updateSessionStatus("Searching for active session for " + courseId + "...");

        try {
            sessionListener = db.collection("sessions")
                    .whereEqualTo("courseId", courseId)
                    .whereEqualTo("isActive", true)
                    .addSnapshotListener(new EventListener<QuerySnapshot>() {
                        @Override
                        public void onEvent(@Nullable QuerySnapshot snapshots,
                                @Nullable FirebaseFirestoreException e) {
                            if (e != null) {
                                Log.e(TAG, "Session listener failed", e);
                                showError("Error fetching active session: " + e.getMessage());
                                return;
                            }
                            handleSessionUpdate(snapshots);
                            populateSessionList(snapshots);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start session listener", e);
            showError("Failed to connect to server");
        }
    }

    private void handleSessionUpdate(QuerySnapshot snapshots) {
        if (snapshots != null && !snapshots.isEmpty()) {
            try {
                DocumentSnapshot ds = snapshots.getDocuments().get(0);
                if (ds == null) {
                    updateSessionStatus("Invalid session data");
                    return;
                }

                activeSessionId = ds.getId();
                requiredBeaconServiceUuid = ds.getString("requiredBeaconServiceUuid"); // NEW field name
                requiredBeaconSessionData = ds.getString("requiredBeaconSessionData"); // optional
                requiredBeaconMac = ds.getString("requiredBeaconMac"); // optional
                Long radius = ds.contains("allowedRadiusMeters") ? ds.getLong("allowedRadiusMeters") : null;
                Integer minRssi = ds.contains("minRequiredRssi") ? ds.getLong("minRequiredRssi").intValue() : null;
                allowedRadiusMeters = radius;
                minRequiredRssi = minRssi;

                // session location
                try {
                    com.google.firebase.firestore.GeoPoint gp = ds.getGeoPoint("location");
                    if (gp != null) {
                        Location loc = new Location("session");
                        loc.setLatitude(gp.getLatitude());
                        loc.setLongitude(gp.getLongitude());
                        sessionLocation = loc;
                    } else {
                        sessionLocation = null;
                    }
                } catch (Exception ex) {
                    sessionLocation = null;
                }

                if (etSessionId != null)
                    etSessionId.setText(activeSessionId);
                updateSessionStatus("Active Session Found");

                // If a new QR code rotates, reset scanned value to force re-scan
                String newQr = ds.getString("qrCodeValue");
                if (newQr != null && !newQr.equals(scannedQrValue)) {
                    scannedQrValue = null;
                    if (etQrValue != null)
                        etQrValue.setText("");
                    updateStatus("New QR available. Please scan.");
                }

                // If we have a expected service UUID, configure scanner accordingly
                if (requiredBeaconServiceUuid != null && !requiredBeaconServiceUuid.isEmpty()) {
                    // re-create beaconScanner with this UUID
                    if (beaconScanner != null) {
                        beaconScanner.stopScan();
                        beaconScanner = null;
                    }
                    beaconScanner = new BeaconScanner(this, requiredBeaconServiceUuid);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error processing session data", e);
                showError("Error processing session data");
            }
        } else {
            activeSessionId = null;
            requiredBeaconServiceUuid = null;
            requiredBeaconSessionData = null;
            requiredBeaconMac = null;
            allowedRadiusMeters = null;
            minRequiredRssi = null;
            sessionLocation = null;
            updateSessionStatus("No active session");
            updateStatus("Waiting for teacher to start session...");
        }
    }

    private void populateSessionList(QuerySnapshot snapshots) {
        if (snapshots == null) {
            sessionsAdapter.updateItems(new java.util.ArrayList<>());
            return;
        }
        java.util.List<SessionsAdapter.SessionItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            try {
                DocumentSnapshot ds = snapshots.getDocuments().get(i);
                String id = ds.getId();
                String qr = ds.getString("qrCodeValue");
                String teacher = ds.getString("teacherId");
                SessionsAdapter.SessionItem it = new SessionsAdapter.SessionItem(id, qr, teacher);
                items.add(it);
            } catch (Exception ex) {
                Log.e(TAG, "Error parsing session doc", ex);
            }
        }
        sessionsAdapter.updateItems(items);
        sessionsAdapter.setOnSelectListener(item -> {
            activeSessionId = item.docId;
            if (etSessionId != null)
                etSessionId.setText(activeSessionId);
            updateSessionStatus("Selected session: " + activeSessionId);
            // fetch selected session details
            fetchSessionDetails(activeSessionId);
        });
    }

    private void fetchSessionDetails(String sessionId) {
        if (sessionId == null)
            return;
        db.collection("sessions").document(sessionId).get()
                .addOnSuccessListener(ds -> {
                    if (!ds.exists())
                        return;
                    requiredBeaconServiceUuid = ds.getString("requiredBeaconServiceUuid");
                    requiredBeaconSessionData = ds.getString("requiredBeaconSessionData");
                    requiredBeaconMac = ds.getString("requiredBeaconMac");
                    if (ds.contains("allowedRadiusMeters"))
                        allowedRadiusMeters = ds.getLong("allowedRadiusMeters");
                    if (ds.contains("minRequiredRssi"))
                        minRequiredRssi = ds.getLong("minRequiredRssi").intValue();
                    if (ds.getGeoPoint("location") != null) {
                        com.google.firebase.firestore.GeoPoint gp = ds.getGeoPoint("location");
                        Location loc = new Location("session");
                        loc.setLatitude(gp.getLatitude());
                        loc.setLongitude(gp.getLongitude());
                        sessionLocation = loc;
                    }
                    // recreate scanner if needed
                    if (requiredBeaconServiceUuid != null) {
                        if (beaconScanner != null) {
                            beaconScanner.stopScan();
                            beaconScanner = null;
                        }
                        beaconScanner = new BeaconScanner(this, requiredBeaconServiceUuid);
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Failed to fetch session details", e));
    }

    // QR scanning
    private void startQrScan() {
        if (scannedQrValue != null) {
            Toast.makeText(this, "QR already scanned. Proceeding...", Toast.LENGTH_SHORT).show();
            checkAndRequestPermissions();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA }, REQUEST_QR_SCAN);
            return;
        }
        try {
            Intent intent = new Intent(this, QrCodeScannerActivity.class);
            startActivityForResult(intent, REQUEST_QR_SCAN);
        } catch (Exception e) {
            Log.e(TAG, "QR Scanner not available", e);
            showError("QR Scanner not available.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_QR_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String result = data.getStringExtra("qr_result");
                if (result != null && !result.isEmpty()) {
                    scannedQrValue = result;
                    if (etQrValue != null)
                        etQrValue.setText(result);
                    Toast.makeText(this, "QR Code Scanned!", Toast.LENGTH_SHORT).show();
                    hideError();
                    // Try to resolve the scanned QR to an active session. If found, continue to
                    // permission checks.
                    resolveSessionByQr(result);
                } else {
                    showError("QR scan returned empty data");
                }
            } else {
                updateStatus("QR scan cancelled.");
            }
        } else if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            checkAndRequestPermissions();
        }
    }

    private void checkAndRequestPermissions() {
        if (scannedQrValue == null) {
            Toast.makeText(this, "Please scan QR code first", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] requiredPermissions = getRequiredPermissions();
        String[] missingPermissions = getMissingPermissions(requiredPermissions);
        if (missingPermissions.length > 0) {
            ActivityCompat.requestPermissions(this, missingPermissions, REQUEST_BLUETOOTH_PERMISSIONS);
        } else {
            ensureBluetoothAndLocationEnabled();
        }
    }

    /**
     * Resolve a scanned QR token to an active session. If a matching active session
     * exists it will set `activeSessionId` (and other session fields) and continue
     * the permission/scan flow. If no session exists yet we install a short-lived
     * listener for up to 20s while waiting for the teacher to create the session.
     */
    private void resolveSessionByQr(String qr) {
        if (qr == null || qr.isEmpty())
            return;
        updateStatus("Resolving session by QR...");

        db.collection("sessions")
                .whereEqualTo("qrCodeValue", qr)
                .whereEqualTo("isActive", true)
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (querySnapshot != null && !querySnapshot.isEmpty()) {
                        DocumentSnapshot ds = querySnapshot.getDocuments().get(0);
                        activeSessionId = ds.getId();
                        requiredBeaconServiceUuid = ds.getString("requiredBeaconServiceUuid");
                        if (etSessionId != null)
                            etSessionId.setText(activeSessionId);
                        updateSessionStatus("Active Session Found (by QR)");
                        // proceed to permissions / beacon flow
                        checkAndRequestPermissions();
                        return;
                    }

                    // No immediate match - listen briefly for the session to appear
                    updateStatus("No active session matches QR. Waiting briefly...");
                    final ListenerRegistration[] holder = new ListenerRegistration[1];
                    holder[0] = db.collection("sessions")
                            .whereEqualTo("qrCodeValue", qr)
                            .whereEqualTo("isActive", true)
                            .addSnapshotListener((snapshots, e) -> {
                                if (e != null) {
                                    Log.e(TAG, "QR listener error", e);
                                    return;
                                }
                                if (snapshots != null && !snapshots.isEmpty()) {
                                    DocumentSnapshot ds2 = snapshots.getDocuments().get(0);
                                    activeSessionId = ds2.getId();
                                    requiredBeaconServiceUuid = ds2.getString("requiredBeaconServiceUuid");
                                    if (etSessionId != null)
                                        etSessionId.setText(activeSessionId);
                                    updateSessionStatus("Active Session Found (by QR)");
                                    if (holder[0] != null)
                                        holder[0].remove();
                                    checkAndRequestPermissions();
                                }
                            });

                    // Remove listener after timeout to avoid leaks
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (holder[0] != null) {
                            holder[0].remove();
                            holder[0] = null;
                        }
                        updateStatus("Timed out waiting for session. Ask teacher to refresh the QR/session.");
                    }, 20000);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to resolve session by QR", e);
                    showError("Failed to resolve session by QR: " + e.getMessage());
                });
    }

    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            return new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
            };
        }
    }

    private String[] getMissingPermissions(String[] permissions) {
        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }

    private void ensureBluetoothAndLocationEnabled() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            showError("Bluetooth not supported on this device");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // permission missing — request it
                showError("BLUETOOTH_CONNECT permission required to proceed.");
                return;
            }
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH);
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager != null && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please enable GPS", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }

        if (activeSessionId == null || scannedQrValue == null) {
            showError("Session data incomplete. Please try again.");
            return;
        }

        // Start scanning for beacon (must match expected session UUID)
        initScanner();
    }

    private void initScanner() {
        try {
            if (beaconScanner != null) {
                beaconScanner.stopScan();
            }

            // If session provided requiredBeaconServiceUuid, use scanner configured with it
            if (requiredBeaconServiceUuid != null && !requiredBeaconServiceUuid.isEmpty()) {
                beaconScanner = new BeaconScanner(this, requiredBeaconServiceUuid);
            } else {
                // fallback to scanner with no UUID filter (less secure)
                beaconScanner = new BeaconScanner(this, null);
            }
            beaconScanner.startScan(this);

            updateStatus("Scanning for beacons...");
            updateBeaconStatus("Scanning...");
            showProgress(true);

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize scanner", e);
            showError("Failed to start beacon scanning: " + e.getMessage());
            showProgress(false);
        }
    }

    @Override
    public void onBeaconFound(String beaconMac, int rssi, String sessionIdFromBeacon) {
        Log.d(TAG, "Beacon found: " + beaconMac + " RSSI: " + rssi + " sessionData: " + sessionIdFromBeacon);

        // keep the last seen values for validation
        lastSeenBeaconMac = beaconMac;
        lastSeenRssi = rssi;

        runOnUiThread(() -> {
            updateStatus("Beacon detected! Getting location...");
            updateBeaconStatus("Found: " + beaconMac + " (RSSI: " + rssi + ")");
            showProgress(false);

            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // Validate beacon against the session before allowing submission
                if (!isBeaconValidForSession(beaconMac, rssi, sessionIdFromBeacon)) {
                    showError("Detected beacon does not match active session. Submission blocked.");
                    updateIntegrityStatus("Beacon mismatch");
                    return;
                }
                // Good to proceed — fetch location and submit
                markAttendance(beaconMac, rssi, activeSessionId);
            } else {
                showError("Location permission required for attendance submission");
            }
        });
    }

    @Override
    public void onScanFailed(String error) {
        Log.e(TAG, "Beacon scan failed: " + error);
        runOnUiThread(() -> {
            showError("Scan failed: " + error);
            updateBeaconStatus("Scan failed");
            showProgress(false);
        });
    }

    private boolean isBeaconValidForSession(String beaconMac, int rssi, String beaconSessionData) {
        // 1) If session expects a specific MAC, ensure it matches
        if (requiredBeaconMac != null && !requiredBeaconMac.isEmpty()) {
            if (!requiredBeaconMac.equalsIgnoreCase(beaconMac)) {
                Log.w(TAG, "Beacon MAC mismatch. expected=" + requiredBeaconMac + " got=" + beaconMac);
                return false;
            }
        }

        // 2) If session expects serviceData (encoded sessionId) ensure it matches
        if (requiredBeaconSessionData != null && !requiredBeaconSessionData.isEmpty()) {
            if (beaconSessionData == null || !requiredBeaconSessionData.equals(beaconSessionData)) {
                Log.w(TAG, "Beacon service data mismatch. expected=" + requiredBeaconSessionData + " got="
                        + beaconSessionData);
                return false;
            }
        }

        // 3) Check RSSI threshold (if present)
        if (minRequiredRssi != null) {
            if (rssi < minRequiredRssi) {
                Log.w(TAG, "Beacon RSSI too weak: " + rssi + " < minRequiredRssi(" + minRequiredRssi + ")");
                return false;
            }
        }

        return true;
    }

    // manual submit is now allowed only if we have a recent validated beacon (seen
    // within last scan)
    private void handleManualSubmit() {
        if (scannedQrValue == null) {
            Toast.makeText(this, "Please scan QR code first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (activeSessionId == null) {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show();
            return;
        }

        // Require that beacon was seen and validated
        if (lastSeenBeaconMac == null) {
            showError("No beacon detected yet. Please scan and ensure teacher beacon is broadcasting.");
            return;
        }

        // Re-validate against session fields before proceeding
        if (!isBeaconValidForSession(lastSeenBeaconMac, lastSeenRssi, requiredBeaconSessionData)) {
            showError("Last detected beacon failed validation. Cannot submit.");
            return;
        }

        // fetch location and submit
        markAttendance(lastSeenBeaconMac, lastSeenRssi, activeSessionId);
    }

    private void markAttendance(String beaconId, int rssi, String sessionId) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show();
            return;
        }

        if (sessionId == null || scannedQrValue == null) {
            showError("Incomplete attendance data (Session/QR missing)");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showError("Location permission required");
            return;
        }

        showProgress(true);
        updateStatus("Getting location...");
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        currentLocation = location;
                        updateLocationStatus(location);

                        // Integrity checks
                        if (isProbablyRooted() || isMockLocationEnabled(location)) {
                            showError("Device integrity failed (Root or Mock Location detected). Submission blocked.");
                            showProgress(false);
                            updateIntegrityStatus("FAILED");
                            return;
                        }

                        // CLIENT-SIDE: Check that location is within allowed radius (if provided)
                        if (sessionLocation != null && allowedRadiusMeters != null) {
                            float[] results = new float[1];
                            Location.distanceBetween(sessionLocation.getLatitude(), sessionLocation.getLongitude(),
                                    location.getLatitude(), location.getLongitude(), results);
                            float distance = results[0];
                            if (distance > allowedRadiusMeters) {
                                showError("You are " + Math.round(distance) + "m away (allowed " + allowedRadiusMeters
                                        + "m). Submission blocked.");
                                showProgress(false);
                                updateIntegrityStatus("OutOfRange");
                                return;
                            }
                        }

                        // If requiredBeaconMac was set by teacher and we didn't detect the same MAC,
                        // block
                        if (requiredBeaconMac != null && !requiredBeaconMac.isEmpty()) {
                            if (beaconId == null || !requiredBeaconMac.equalsIgnoreCase(beaconId)) {
                                showError(
                                        "Detected beacon does not match expected teacher beacon. Submission blocked.");
                                showProgress(false);
                                updateIntegrityStatus("BeaconMismatch");
                                return;
                            }
                        }

                        // Check RSSI threshold
                        if (minRequiredRssi != null && (rssi < minRequiredRssi)) {
                            showError("Beacon signal too weak (" + rssi + "). Move closer and try again.");
                            showProgress(false);
                            updateIntegrityStatus("WeakSignal");
                            return;
                        }

                        // Finally check server time skew and submit
                        checkServerTimeSkewAndSubmit(user, sessionId, beaconId, rssi, location);

                    } else {
                        showProgress(false);
                        showError("Location unavailable. Please enable GPS and try again.");
                        updateStatus("Location acquisition failed.");
                        // optionally restart scanner
                    }
                })
                .addOnFailureListener(e -> {
                    showProgress(false);
                    Log.e(TAG, "Location fetch failed", e);
                    showError("Failed to get location: " + e.getMessage());
                });
    }

    private void submitAttendanceData(FirebaseUser user, String sessionId,
            String beaconId, int rssi, Location location) {
        Map<String, Object> attendanceData = new HashMap<>();
        attendanceData.put("sessionId", sessionId);
        attendanceData.put("scannedQrValue", scannedQrValue);
        attendanceData.put("studentId", user.getUid());
        attendanceData.put("deviceIntegrity", true);
        attendanceData.put("mockLocationDetected", isMockLocationEnabled(location));
        attendanceData.put("latitude", location.getLatitude());
        attendanceData.put("longitude", location.getLongitude());
        attendanceData.put("accuracy", location.getAccuracy());

        if (beaconId != null) {
            attendanceData.put("scannedBeaconId", beaconId);
            attendanceData.put("beaconRssi", rssi);
        }

        attendanceData.put("status", "pending");
        attendanceData.put("timestamp", FieldValue.serverTimestamp());

        // IMPORTANT: server must validate these fields on creation (Cloud Function /
        // Security Rules)
        db.collection("attendance").add(attendanceData)
                .addOnSuccessListener(documentReference -> {
                    showProgress(false);
                    updateStatus("Attendance submitted successfully!");
                    updateIntegrityStatus("Verified");
                    updateLocationStatus(location);
                    hideError();
                    Toast.makeText(this, "Attendance submitted for verification!", Toast.LENGTH_LONG).show();
                    Log.d(TAG, "Attendance submitted: " + documentReference.getId());
                })
                .addOnFailureListener(e -> {
                    showProgress(false);
                    Log.e(TAG, "Attendance submission failed", e);
                    showError("Submission failed: " + e.getMessage());
                    updateStatus("Submission failed. Please try again.");
                });
    }

    private void checkServerTimeSkewAndSubmit(FirebaseUser user, String sessionId, String beaconId, int rssi,
            Location location) {
        if (sessionId == null) {
            showError("Session missing");
            return;
        }

        db.collection("sessions").document(sessionId).get()
                .addOnSuccessListener(ds -> {
                    if (!ds.exists()) {
                        showError("Session not found on server");
                        return;
                    }
                    Timestamp created = ds.getTimestamp("createdAt");
                    long serverMs = created == null ? System.currentTimeMillis() : created.toDate().getTime();
                    long deviceMs = System.currentTimeMillis();
                    long skew = Math.abs(serverMs - deviceMs);
                    long maxSkew = 2 * 60 * 1000; // 2 minutes
                    if (skew > maxSkew) {
                        new androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Time mismatch")
                                .setMessage("Your device time differs from server by " + (skew / 1000)
                                        + " seconds. Continue?")
                                .setPositiveButton("Continue",
                                        (d, w) -> submitAttendanceData(user, sessionId, beaconId, rssi, location))
                                .setNegativeButton("Cancel", (d, w) -> {
                                    hideError();
                                })
                                .show();
                    } else {
                        submitAttendanceData(user, sessionId, beaconId, rssi, location);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get session for time check", e);
                    submitAttendanceData(user, sessionId, beaconId, rssi, location);
                });
    }

    // ========== Helpers ==========

    private boolean isMockLocationEnabled(Location location) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return location.isMock();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return location.isFromMockProvider();
            } else {
                return Settings.Secure.getString(getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION).equals("1");
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
            String[] paths = { "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
                    "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su" };
            for (String path : paths) {
                File file = new File(path);
                if (file.exists())
                    return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking root", e);
        }
        return false;
    }

    private void requestLocationUpdate() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION },
                    REQUEST_LOCATION_PERMISSIONS);
            return;
        }

        try {
            // Check if Google Play Services is available
            if (fusedLocationClient == null) {
                Log.e(TAG, "FusedLocationClient is null - Google Play Services may not be available");
                Toast.makeText(this, "Location services not available", Toast.LENGTH_LONG).show();
                return;
            }

            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0)
                    .setMinUpdateIntervalMillis(0).build();
            LocationSettingsRequest settingsRequest = new LocationSettingsRequest.Builder().addLocationRequest(request)
                    .build();
            SettingsClient settingsClient = LocationServices.getSettingsClient(this);
            settingsClient.checkLocationSettings(settingsRequest)
                    .addOnSuccessListener(s -> fetchCurrentLocation())
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Location settings check failed", e);
                        if (e instanceof ResolvableApiException) {
                            try {
                                ((ResolvableApiException) e).startResolutionForResult(this,
                                        REQUEST_LOCATION_PERMISSIONS);
                            } catch (Exception ex) {
                                Log.e(TAG, "Failed to resolve location settings", ex);
                                Toast.makeText(this, "Enable Location services", Toast.LENGTH_LONG).show();
                            }
                        } else {
                            Toast.makeText(this, "Location settings not satisfied: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        } catch (Exception ex) {
            Log.e(TAG, "Error checking location settings", ex);
            Toast.makeText(this, "Location services error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void fetchCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;
        showProgress(true);
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(location -> {
                        showProgress(false);
                        if (location != null) {
                            currentLocation = location;
                            updateLocationStatus(location);
                            Toast.makeText(this, "Location updated", Toast.LENGTH_SHORT).show();
                        } else {
                            fusedLocationClient.getLastLocation()
                                    .addOnSuccessListener(last -> {
                                        if (last != null) {
                                            currentLocation = last;
                                            updateLocationStatus(last);
                                            Toast.makeText(this, "Used last known location", Toast.LENGTH_SHORT).show();
                                        } else {
                                            Toast.makeText(this, "Location unavailable.", Toast.LENGTH_LONG).show();
                                        }
                                    });
                        }
                    })
                    .addOnFailureListener(e -> {
                        showProgress(false);
                        Log.e(TAG, "getCurrentLocation failed", e);
                        Toast.makeText(this, "Failed to get current location", Toast.LENGTH_SHORT).show();
                    });
        } catch (Exception e) {
            showProgress(false);
            Log.e(TAG, "Error requesting current location", e);
        }
    }

    // UI update helpers
    private void updateStatus(String message) {
        if (statusText != null)
            runOnUiThread(() -> statusText.setText(message));
    }

    private void updateSessionStatus(String message) {
        if (tvSessionStatus != null)
            runOnUiThread(() -> tvSessionStatus.setText("Session: " + message));
    }

    private void updateBeaconStatus(String message) {
        if (tvBeacon != null)
            runOnUiThread(() -> tvBeacon.setText("Beacon: " + message));
    }

    private void updateIntegrityStatus(String message) {
        if (tvIntegrity != null)
            runOnUiThread(() -> tvIntegrity.setText("Integrity: " + message));
    }

    private void updateLocationStatus(Location location) {
        if (tvLocation != null && location != null) {
            String locText = String.format("Location: %.4f, %.4f (±%.0fm)", location.getLatitude(),
                    location.getLongitude(), location.getAccuracy());
            runOnUiThread(() -> tvLocation.setText(locText));
        }
    }

    private void showError(String message) {
        if (tvError != null && cardError != null)
            runOnUiThread(() -> {
                tvError.setText(message);
                cardError.setVisibility(View.VISIBLE);
            });
        Log.e(TAG, "Error: " + message);
    }

    private void hideError() {
        if (cardError != null)
            runOnUiThread(() -> cardError.setVisibility(View.GONE));
    }

    private void showProgress(boolean show) {
        if (progressBar != null)
            runOnUiThread(() -> progressBar.setVisibility(show ? View.VISIBLE : View.GONE));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (beaconScanner != null) {
            beaconScanner.stopScan();
            beaconScanner = null;
        }
        if (sessionListener != null) {
            sessionListener.remove();
            sessionListener = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (beaconScanner != null)
            beaconScanner.stopScan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAuthentication();
    }
}
