package com.proxyfail.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.proxyfail.app.databinding.ActivityStudentDashboardBinding;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public class StudentDashboardActivity extends AppCompatActivity {
    private static final String TAG = "StudentDashboard";

    // Dashboard configuration
    private static final String DASHBOARD_HOST = "https://project-proxyfail.web.app";
    private static final String DASHBOARD_PATH = "/student-dashboard";
    private static final String LOGIN_ENDPOINT = "/sessionLogin";

    // NEW: Key for passing data to Attendance Activity
    public static final String EXTRA_COURSE_ID = "COURSE_ID";

    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int GEOLOCATION_REQUEST_CODE = 124;
    private static final int PERMISSION_POST_NOTIFICATIONS = 101; // For notification permission

    private static final long PERMISSION_CHECK_DELAY = 500;
    private static final long DASHBOARD_LOAD_DELAY = 2000;

    private ActivityStudentDashboardBinding binding;
    private Handler statusHandler = new Handler();

    // NEW: Firebase & Data variables
    private FirebaseFirestore db;
    private String studentCourseId = null; // Course ID (fetched from profile). Leave null so clicks trigger fetch if not ready.

    // Notification service
    private StudentNotificationService notificationService;

    // Geolocation permission handling
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStudentDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize Firebase
        db = FirebaseFirestore.getInstance();
        notificationService = new StudentNotificationService(this);
        notificationService.setupNotificationChannel();

        setupUI();

        // Force notification permission on startup
        checkAndRequestNotificationPermission();

        checkPermissions();
        fetchStudentCourseId(); // Fetch course ID
        setupWebView();
    }

    // ==================== UI Setup ====================

    private void setupUI() {
        // Sign-out button
        binding.btnSignOut.setOnClickListener(v -> handleSignOut());

        // Attendance button
        binding.btnAttendance.setOnClickListener(v -> {
            if (this.studentCourseId != null && !this.studentCourseId.isEmpty()) {
                Intent intent = new Intent(this, AttendanceActivity.class);
                intent.putExtra(EXTRA_COURSE_ID, this.studentCourseId);
                startActivity(intent);
                return;
            }
            // Not available yet — fetch from Firestore
            updateLoadingStatus("Resolving course...");
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser == null) {
                showError("Not authenticated. Please sign in again.");
                return;
            }
            db.collection("users").document(currentUser.getUid()).get()
                    .addOnSuccessListener(doc -> {
                        hideLoadingStatus();
                        if (doc.exists()) {
                            String cid = doc.getString("courseId");
                            if (cid != null) cid = cid.trim();
                            if (cid != null && !cid.isEmpty()) {
                                this.studentCourseId = cid;
                                Intent intent = new Intent(this, AttendanceActivity.class);
                                intent.putExtra(EXTRA_COURSE_ID, this.studentCourseId);
                                startActivity(intent);
                                return;
                            }
                        }
                        promptToEnterCourseCode(currentUser.getUid());
                    })
                    .addOnFailureListener(e -> {
                        hideLoadingStatus();
                        showError("Failed to resolve course. Try again later.");
                    });
        });

        updateLoadingStatus("Initializing...");
    }

    private void handleSignOut() {
        updateLoadingStatus("Signing out...");
        if (binding.webview != null) {
            binding.webview.clearCache(true);
            binding.webview.clearHistory();
            CookieManager.getInstance().removeAllCookies(null);
        }
        FirebaseAuth.getInstance().signOut();
        finish();
    }

    // ==================== Data Fetching ====================

    private void fetchStudentCourseId() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        db.collection("users").document(user.getUid()).get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String courseId = doc.getString("courseId");
                        if (courseId != null) courseId = courseId.trim();
                        if (courseId != null && !courseId.isEmpty()) {
                            this.studentCourseId = courseId;
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    // handle failure if needed
                });
    }

    private void promptToEnterCourseCode(String uid) {
        runOnUiThread(() -> {
            updateLoadingStatus("Fetching course suggestions...");
            db.collection("sessions").whereEqualTo("isActive", true).get()
                    .addOnSuccessListener(q -> {
                        hideLoadingStatus();
                        java.util.Set<String> suggestions = new java.util.HashSet<>();
                        for (com.google.firebase.firestore.DocumentSnapshot ds : q.getDocuments()) {
                            String c = ds.getString("courseId");
                            if (c != null && !c.trim().isEmpty()) suggestions.add(c.trim());
                        }
                        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
                        builder.setTitle("Enter Course Code");
                        final android.widget.LinearLayout container = new android.widget.LinearLayout(this);
                        container.setOrientation(android.widget.LinearLayout.VERTICAL);
                        final android.widget.EditText input = new android.widget.EditText(this);
                        input.setHint("e.g. CS401-FALL25");
                        container.addView(input);
                        if (!suggestions.isEmpty()) {
                            final android.widget.TextView tv = new android.widget.TextView(this);
                            tv.setText("Suggestions: ");
                            container.addView(tv);
                            for (String s : suggestions) {
                                final android.widget.Button b = new android.widget.Button(this);
                                b.setText(s);
                                b.setOnClickListener(v -> input.setText(s));
                                container.addView(b);
                            }
                        }
                        builder.setView(container);
                        builder.setPositiveButton("Save", (dialog, which) -> {
                            String entered = input.getText() == null ? "" : input.getText().toString().trim();
                            if (entered.isEmpty()) {
                                showError("Course code cannot be empty.");
                                return;
                            }
                            updateLoadingStatus("Saving course code...");
                            java.util.Map<String, Object> update = new java.util.HashMap<>();
                            update.put("courseId", entered);
                            db.collection("users").document(uid).update(update)
                                    .addOnSuccessListener(aVoid -> {
                                        hideLoadingStatus();
                                        this.studentCourseId = entered;
                                        Intent intent = new Intent(this, AttendanceActivity.class);
                                        intent.putExtra(EXTRA_COURSE_ID, this.studentCourseId);
                                        startActivity(intent);
                                    })
                                    .addOnFailureListener(e -> {
                                        hideLoadingStatus();
                                        showError("Failed to save course. Please try again.");
                                    });
                        });
                        builder.setNegativeButton("Cancel", (dialog, which) -> { hideLoadingStatus(); dialog.dismiss(); });
                        builder.setCancelable(false);
                        androidx.appcompat.app.AlertDialog dialog = builder.create();
                        dialog.show();
                    })
                    .addOnFailureListener(e -> {
                        hideLoadingStatus();
                        // fallback prompt
                        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
                        builder.setTitle("Enter Course Code");
                        final android.widget.EditText input = new android.widget.EditText(this);
                        input.setHint("e.g. CS401-FALL25");
                        builder.setView(input);
                        builder.setPositiveButton("Save", (dialog, which) -> {
                            String entered = input.getText() == null ? "" : input.getText().toString().trim();
                            if (entered.isEmpty()) {
                                showError("Course code cannot be empty.");
                                return;
                            }
                            updateLoadingStatus("Saving course code...");
                            java.util.Map<String, Object> update = new java.util.HashMap<>();
                            update.put("courseId", entered);
                            db.collection("users").document(uid).update(update)
                                    .addOnSuccessListener(aVoid -> {
                                        hideLoadingStatus();
                                        this.studentCourseId = entered;
                                        Intent intent = new Intent(this, AttendanceActivity.class);
                                        intent.putExtra(EXTRA_COURSE_ID, this.studentCourseId);
                                        startActivity(intent);
                                    })
                                    .addOnFailureListener(err -> {
                                        hideLoadingStatus();
                                        showError("Failed to save course. Please try again.");
                                    });
                        });
                        builder.setNegativeButton("Cancel", (dialog, which) -> { hideLoadingStatus(); dialog.dismiss(); });
                        builder.setCancelable(false);
                        androidx.appcompat.app.AlertDialog dialog = builder.create();
                        dialog.show();
                    });
        });
    }

    // ==================== Permission Management ====================

    private void checkPermissions() {
        boolean hasCameraPermission = checkPermission(Manifest.permission.CAMERA);
        boolean hasLocationPermission = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION);

        updatePermissionStatus(binding.btnCameraStatus, binding.tvCameraStatus,
                hasCameraPermission, "Camera");
        updatePermissionStatus(binding.btnLocationStatus, binding.tvLocationStatus,
                hasLocationPermission, "Location");

        if (!hasCameraPermission || !hasLocationPermission) {
            requestMissingPermissions();
        }
    }

    private boolean checkPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMissingPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
    }

    private void updatePermissionStatus(Button statusButton, TextView statusText,
                                        boolean hasPermission, String permissionName) {
        if (hasPermission) {
            statusButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")));
            statusText.setText("Granted");
            statusText.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            statusButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#f44336")));
            statusText.setText("Required");
            statusText.setTextColor(Color.parseColor("#f44336"));
        }
    }

    // ==================== Loading Status UI ====================

    private void updateLoadingStatus(String message) {
        runOnUiThread(() -> {
            binding.tvLoadingStatus.setText(message);
            binding.loadingSection.setVisibility(View.VISIBLE);
        });
    }

    private void hideLoadingStatus() {
        runOnUiThread(() -> binding.loadingSection.setVisibility(View.GONE));
    }

    private void showError(String message) {
        Log.e(TAG, "Error: " + message);
        runOnUiThread(() -> {
            binding.tvErrorMessage.setText(message);
            binding.tvErrorMessage.setVisibility(View.VISIBLE);
            binding.webview.setVisibility(View.GONE);
            hideLoadingStatus();
        });
    }

    private void hideError() {
        runOnUiThread(() -> {
            binding.tvErrorMessage.setVisibility(View.GONE);
            binding.webview.setVisibility(View.VISIBLE);
        });
    }

    // ==================== WebView Setup ====================

    private void setupWebView() {
        updateLoadingStatus("Setting up dashboard...");

        WebView webView = binding.webview;
        configureWebSettings(webView);
        configureCookieManager(webView);
        setWebChromeClient(webView);
        setWebViewClient(webView);

        statusHandler.postDelayed(this::getFirebaseTokenAndLoadDashboard, DASHBOARD_LOAD_DELAY);
    }

    private void configureWebSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
    }

    private void configureCookieManager(WebView webView) {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }
    }

    private void setWebChromeClient(WebView webView) {
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    if (newProgress % 20 == 0) {
                        updateLoadingStatus("Loading dashboard... " + newProgress + "%");
                    }
                } else {
                    hideLoadingStatus();
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                handleGeolocationRequest(origin, callback);
            }
        });
    }

    private void handleGeolocationRequest(String origin, GeolocationPermissions.Callback callback) {
        if (checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            callback.invoke(origin, true, false);
        } else {
            pendingGeoCallback = callback;
            pendingGeoOrigin = origin;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    GEOLOCATION_REQUEST_CODE);
        }
    }

    private void setWebViewClient(WebView webView) {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideLoadingStatus();
                hideError();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    String errorMsg = String.format(
                            "Failed to load dashboard.\nError Code: %d\nPlease check your internet connection.",
                            error.getErrorCode()
                    );
                    showError(errorMsg);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith(DASHBOARD_HOST)) {
                    return false;
                }
                return super.shouldOverrideUrlLoading(view, request);
            }
        });
    }

    // ==================== Firebase Authentication ====================

    private void getFirebaseTokenAndLoadDashboard() {
        updateLoadingStatus("Authenticating...");
        FirebaseAuth auth = FirebaseAuth.getInstance();

        if (auth.getCurrentUser() == null) {
            showError("User not authenticated. Please sign in again.");
            return;
        }

        auth.getCurrentUser().getIdToken(true)
                .addOnSuccessListener(result -> {
                    String idToken = result.getToken();
                    loadDashboardWithToken(idToken);
                })
                .addOnFailureListener(e -> {
                    showError("Authentication failed. Please try signing in again.");
                });
    }

    private void loadDashboardWithToken(String idToken) {
        updateLoadingStatus("Connecting to server...");
        WebView webView = binding.webview;

        if (idToken == null || idToken.isEmpty()) {
            webView.loadUrl(DASHBOARD_HOST + DASHBOARD_PATH);
            return;
        }

        try {
            String encodedToken = URLEncoder.encode(idToken, "UTF-8");
            String postData = "idToken=" + encodedToken;
            byte[] postDataBytes = postData.getBytes("UTF-8");
            String loginUrl = DASHBOARD_HOST + LOGIN_ENDPOINT;
            webView.postUrl(loginUrl, postDataBytes);
        } catch (UnsupportedEncodingException e) {
            webView.loadUrl(DASHBOARD_HOST + DASHBOARD_PATH);
        }
    }

    // ==================== Permission Results ====================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE || requestCode == GEOLOCATION_REQUEST_CODE) {
            // Recheck permissions after request
            statusHandler.postDelayed(this::checkPermissions, PERMISSION_CHECK_DELAY);
            handleGeolocationPermissionResult();
        } else if (requestCode == PERMISSION_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
            } else {
                // User denied, show alert
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Notification Permission Needed")
                        .setMessage("This app requires notification permission to notify you about important updates. Please enable it in settings.")
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> {
                            // Optionally handle denial
                        })
                        .setCancelable(false)
                        .show();
            }
        }
    }

    private void handleGeolocationPermissionResult() {
        if (pendingGeoCallback == null) return;

        boolean locationGranted = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        pendingGeoCallback.invoke(pendingGeoOrigin, locationGranted, false);
        pendingGeoCallback = null;
        pendingGeoOrigin = null;
    }

    // ==================== Activity Lifecycle ====================

    @Override
    public void onBackPressed() {
        if (binding.webview.canGoBack()) {
            binding.webview.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (binding.webview != null) {
            binding.webview.onPause();
            binding.webview.pauseTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding.webview != null) {
            binding.webview.onResume();
            binding.webview.resumeTimers();
        }
    }

    @Override
    protected void onDestroy() {
        if (statusHandler != null) {
            statusHandler.removeCallbacksAndMessages(null);
        }
        if (binding.webview != null) {
            binding.webview.loadUrl("about:blank");
            binding.webview.clearHistory();
            binding.webview.clearCache(true);
            binding.webview.removeAllViews();
            binding.webview.destroy();
        }
        super.onDestroy();
    }

    // ==================== Notification permission check ====================

    private void checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                // Prompt user
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_POST_NOTIFICATIONS);
            }
        }
    }
}