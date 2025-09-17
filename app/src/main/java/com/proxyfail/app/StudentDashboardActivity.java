package com.proxyfail.app;

import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.proxyfail.app.databinding.ActivityStudentDashboardBinding;

public class StudentDashboardActivity extends AppCompatActivity {
    private ActivityStudentDashboardBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStudentDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Simple sign-out + button to go to Attendance screen
        binding.btnSignOut.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            finish();
        });

        binding.btnAttendance.setOnClickListener(v -> {
            startActivity(new android.content.Intent(this, AttendanceActivity.class));
        });

        WebView webView = binding.webview;
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        // Replace with your streamlit teacher dashboard URL
        webView.loadUrl("https://www.google.com");
    }
}
