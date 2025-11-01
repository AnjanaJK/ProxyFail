package com.proxyfail.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

public class QrDisplayActivity extends Activity {
    public static final String EXTRA_PAYLOAD = "payload";

    private String payload = "session:12345";
    private ImageView ivQr;
    private TextView tvPayload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_display);

        ivQr = findViewById(R.id.ivQr);
        tvPayload = findViewById(R.id.tvPayload);
        Button btnStop = findViewById(R.id.btnStopAdvAndClose);

        if (getIntent() != null && getIntent().hasExtra(EXTRA_PAYLOAD)) {
            payload = getIntent().getStringExtra(EXTRA_PAYLOAD);
        }

        tvPayload.setText("Payload: " + payload);

        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bm = encoder.encodeBitmap(payload, BarcodeFormat.QR_CODE, 600, 600);
            ivQr.setImageBitmap(bm);
        } catch (WriterException e) {
            tvPayload.setText("Failed to render QR: " + e.getMessage());
        }

        // start advertising with this payload
        BeaconAdvertiserService.startAdvertising(this, payload);

        btnStop.setOnClickListener(v -> {
            BeaconAdvertiserService.stopAdvertising(this);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        BeaconAdvertiserService.stopAdvertising(this);
        super.onDestroy();
    }
}
