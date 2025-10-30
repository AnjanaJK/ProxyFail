package com.proxyfail.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.util.List;

public class QrCodeScannerActivity extends AppCompatActivity {

    private static final String TAG = "QrCodeScannerActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 5001;
    private static final int REQUEST_BT_PERMISSION = 5002;

    private DecoratedBarcodeView barcodeView;
    private FirebaseFirestore firestore;
    private String activeSessionId;
    private ListenerRegistration waitingListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_code_scanner);

        firestore = FirebaseFirestore.getInstance();
        barcodeView = findViewById(R.id.barcode_scanner);

        // Ensure camera permission before starting
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA_PERMISSION);
        }
    }

    // QR Scanner Callback
    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            if (result == null || result.getText() == null)
                return;

            String scannedQr = result.getText();
            Log.d(TAG, "QR Scanned: " + scannedQr);

            barcodeView.pause();
            resolveSessionByQr(scannedQr);
        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {
            // No-op
        }
    };

    private void resolveSessionByQr(String scannedQr) {
        Toast.makeText(this, "Resolving session by QR...", Toast.LENGTH_SHORT).show();

        firestore.collection("sessions")
                .whereEqualTo("qrCodeValue", scannedQr)
                .whereEqualTo("isActive", true)
                .limit(1)
                .get()
                .addOnSuccessListener(query -> {
                    if (!query.isEmpty()) {
                        DocumentSnapshot doc = query.getDocuments().get(0);
                        activeSessionId = doc.getId();
                        Log.d(TAG, "Active session found: " + activeSessionId);
                        checkBluetoothPermissionsAndStartScan();
                    } else {
                        Log.d(TAG, "No active session yet — listening for updates...");
                        install20sListener(scannedQr);
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Error resolving session: ", e));
    }

    private void install20sListener(String scannedQr) {
        waitingListener = firestore.collection("sessions")
                .whereEqualTo("qrCodeValue", scannedQr)
                .whereEqualTo("isActive", true)
                .addSnapshotListener((snapshots, e) -> {
                    if (snapshots != null && !snapshots.isEmpty()) {
                        activeSessionId = snapshots.getDocuments().get(0).getId();
                        Log.d(TAG, "Session activated during wait: " + activeSessionId);
                        Toast.makeText(this, "Session started! Scanning beacon...", Toast.LENGTH_SHORT).show();
                        removeListener();
                        checkBluetoothPermissionsAndStartScan();
                    }
                });

        // Timeout after 20 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            removeListener();
            Toast.makeText(this, "Session not found. Please rescan.", Toast.LENGTH_LONG).show();
            finish();
        }, 20000);
    }

    private void removeListener() {
        if (waitingListener != null) {
            waitingListener.remove();
            waitingListener = null;
        }
    }

    private void checkBluetoothPermissionsAndStartScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this, new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_BT_PERMISSION);
        } else {
            ActivityCompat.requestPermissions(this, new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_BT_PERMISSION);
        }
    }

    private boolean allGranted(int[] grantResults) {
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanner();
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == REQUEST_BT_PERMISSION) {
            if (allGranted(grantResults)) {
                startBeaconScan();
            } else {
                Toast.makeText(this, "Bluetooth/Location permissions required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startScanner() {
        if (barcodeView != null)
            barcodeView.decodeContinuous(callback);
    }

    private void startBeaconScan() {
        Log.d(TAG, "Starting beacon scan for session: " + activeSessionId);
        Toast.makeText(this, "Scanning for nearby teacher beacon...", Toast.LENGTH_SHORT).show();

        // ✅ Call your BeaconScanner class here and provide a callback to receive
        // results
        BeaconScanner scanner = new BeaconScanner(this, activeSessionId);
        scanner.startScan(new BeaconScanner.Callback() {
            @Override
            public void onBeaconFound(String beaconId, int rssi, String sessionIdFromBeacon) {
                runOnUiThread(() -> {
                    Toast.makeText(QrCodeScannerActivity.this, "Beacon found: " + beaconId, Toast.LENGTH_SHORT).show();
                    // You can extend this to navigate to the attendance screen or validate the
                    // session
                    finish();
                });
            }

            @Override
            public void onScanFailed(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(QrCodeScannerActivity.this, "Scan failed: " + error, Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView != null)
            barcodeView.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeView != null)
            barcodeView.resume();
    }

    @Override
    protected void onDestroy() {
        removeListener();
        super.onDestroy();
    }
}
