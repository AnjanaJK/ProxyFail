package com.proxyfail.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.util.List;

public class QrCodeScannerActivity extends AppCompatActivity {
    private static final String TAG = "QrCodeScannerActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 5001;
    private DecoratedBarcodeView barcodeView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_code_scanner);

        barcodeView = findViewById(R.id.barcode_scanner);

        // Ensure camera permission before starting
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startScanner();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            if (result == null || result.getText() == null) return;

            String contents = result.getText();
            Log.d(TAG, "QR Scanned: " + contents);

            Intent out = new Intent();
            out.putExtra("qr_result", contents);
            setResult(RESULT_OK, out);

            // stop camera and finish
            barcodeView.pause();
            finish();
        }

        @Override
        public void possibleResultPoints(List<ResultPoint> resultPoints) {
            // no-op
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeView != null) barcodeView.resume();
    }

    @Override
    protected void onPause() {
        if (barcodeView != null) barcodeView.pause();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanner();
            } else {
                Log.e(TAG, "Camera permission denied");
                setResult(RESULT_CANCELED);
                finish();
            }
        }
    }

    private void startScanner() {
        if (barcodeView != null) barcodeView.decodeContinuous(callback);
    }
}

