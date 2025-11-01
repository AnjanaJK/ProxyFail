package com.proxyfail.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

public class TuningActivity extends Activity {
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.proxyfail.app.R.layout.activity_tuning);
        prefs = getSharedPreferences("beacon_prefs", MODE_PRIVATE);

        SeekBar sbEmaRise = findViewById(R.id.sbEmaRise);
        SeekBar sbEmaFall = findViewById(R.id.sbEmaFall);
        SeekBar sbClose = findViewById(R.id.sbCloseThresh);
        SeekBar sbFar = findViewById(R.id.sbFarThresh);
        SeekBar sbConsec = findViewById(R.id.sbConsec);
        SeekBar sbPathN = findViewById(R.id.sbPathN);

        TextView tvEmaRiseVal = findViewById(R.id.tvEmaRiseVal);
        TextView tvEmaFallVal = findViewById(R.id.tvEmaFallVal);
        TextView tvCloseVal = findViewById(R.id.tvCloseThreshVal);
        TextView tvFarVal = findViewById(R.id.tvFarThreshVal);
        TextView tvConsecVal = findViewById(R.id.tvConsecVal);
        TextView tvPathNVal = findViewById(R.id.tvPathNVal);

        // load saved values or defaults
        float emaRise = prefs.getFloat("ema_rise", 0.6f);
        float emaFall = prefs.getFloat("ema_fall", 0.25f);
        int close = prefs.getInt("close_threshold", -65);
        int far = prefs.getInt("far_threshold", -80);
        int consec = prefs.getInt("consecutive_required", 2);
        float pathN = prefs.getFloat("path_loss_n", 2.0f);

        sbEmaRise.setProgress((int) (emaRise * 100));
        sbEmaFall.setProgress((int) (emaFall * 100));
        sbClose.setProgress(close + 90); // map -90..-30 -> 0..60
        sbFar.setProgress(far + 100); // map -100..-40 -> 0..60 (we used 60 max)
        sbConsec.setProgress(Math.max(0, consec - 1));
        sbPathN.setProgress((int) (pathN * 10));

        tvEmaRiseVal.setText(String.format("%.2f", emaRise));
        tvEmaFallVal.setText(String.format("%.2f", emaFall));
        tvCloseVal.setText(String.valueOf(close));
        tvFarVal.setText(String.valueOf(far));
        tvConsecVal.setText(String.valueOf(consec));
        tvPathNVal.setText(String.format("%d (%.1f)", (int)(pathN*10), pathN));

        sbEmaRise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { float v = p/100f; tvEmaRiseVal.setText(String.format("%.2f", v)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbEmaFall.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { float v = p/100f; tvEmaFallVal.setText(String.format("%.2f", v)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbClose.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { int v = p - 90; tvCloseVal.setText(String.valueOf(v)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbFar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { int v = p - 100; tvFarVal.setText(String.valueOf(v)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbConsec.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { int v = p + 1; tvConsecVal.setText(String.valueOf(v)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbPathN.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { float v = p/10f; tvPathNVal.setText(String.format("%d (%.1f)", p, v)); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        Button btnSave = findViewById(R.id.btnSaveTune);
        btnSave.setOnClickListener(v -> {
            float newEmaRise = sbEmaRise.getProgress() / 100f;
            float newEmaFall = sbEmaFall.getProgress() / 100f;
            int newClose = sbClose.getProgress() - 90;
            int newFar = sbFar.getProgress() - 100;
            int newConsec = sbConsec.getProgress() + 1;
            float newPathN = sbPathN.getProgress() / 10f;

            prefs.edit()
                    .putFloat("ema_rise", newEmaRise)
                    .putFloat("ema_fall", newEmaFall)
                    .putInt("close_threshold", newClose)
                    .putInt("far_threshold", newFar)
                    .putInt("consecutive_required", newConsec)
                    .putFloat("path_loss_n", newPathN)
                    .apply();
            finish();
        });
    }
}
