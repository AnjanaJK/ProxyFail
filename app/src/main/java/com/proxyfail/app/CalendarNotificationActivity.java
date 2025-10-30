package com.proxyfail.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import androidx.appcompat.app.AlertDialog;

public class CalendarNotificationActivity extends AppCompatActivity {
    private static final String TAG = "CalendarNotification";
    private static final String CHANNEL_ID = "notification_channel";

    private TextView tvSelectedDate, tvSelectedTime;
    private EditText etNotificationTitle, etNotificationMessage;
    private Spinner spinnerNotificationType;
    private Button btnSetNotification, btnViewScheduledNotifications;

    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private long selectedDateMillis = 0;
    private int selectedHour = 9;
    private int selectedMinute = 0;

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calendar_notification);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        currentUser = auth.getCurrentUser();

        if (currentUser == null) {
            Toast.makeText(this, "Please log in again", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupToolbar();
        initializeViews();
        setupNotificationChannel();
        setupSpinner();
        setupClickListeners();

        // Request notification permission for Android 13+
        requestNotificationPermission();
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Schedule Notifications");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void initializeViews() {
        tvSelectedDate = findViewById(R.id.tvSelectedDate);
        tvSelectedTime = findViewById(R.id.tvSelectedTime);
        etNotificationTitle = findViewById(R.id.etNotificationTitle);
        etNotificationMessage = findViewById(R.id.etNotificationMessage);
        spinnerNotificationType = findViewById(R.id.spinnerNotificationType);
        btnSetNotification = findViewById(R.id.btnSetNotification);
        btnViewScheduledNotifications = findViewById(R.id.btnViewScheduledNotifications);

        updateDateDisplay();
        updateTimeDisplay();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Notification Channel",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Channel for scheduled notifications");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void setupSpinner() {
        String[] types = {
                "Class Reminder",
                "Assignment Due",
                "Exam Notification",
                "General Announcement",
                "Attendance Alert"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, types);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerNotificationType.setAdapter(adapter);
    }

    private void setupClickListeners() {
        tvSelectedDate.setOnClickListener(v -> showDatePicker());
        tvSelectedTime.setOnClickListener(v -> showTimePicker());
        btnSetNotification.setOnClickListener(v -> scheduleNotification());
        btnViewScheduledNotifications.setOnClickListener(v -> viewScheduledNotifications());
    }

    /**
     * Query Firestore for scheduled notifications for the current teacher and show them
     * in a custom dialog with improved formatting.
     */
    private void viewScheduledNotifications() {
        if (currentUser == null) {
            Toast.makeText(this, "Please log in to view scheduled notifications", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("scheduledNotifications")
                .whereEqualTo("teacherId", currentUser.getUid())
                .orderBy("scheduledTime")
                .get()
                .addOnSuccessListener(querySnapshot -> {
                    if (querySnapshot == null || querySnapshot.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Scheduled Notifications")
                                .setMessage("No scheduled notifications found.")
                                .setPositiveButton("OK", null)
                                .show();
                        return;
                    }

                    showFormattedNotificationsList(querySnapshot);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching scheduled notifications", e);
                    Toast.makeText(this, "Failed to load scheduled notifications", Toast.LENGTH_SHORT).show();
                });
    }

    private void showFormattedNotificationsList(com.google.firebase.firestore.QuerySnapshot querySnapshot) {
        // Create a custom view for the dialog
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);

        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());

        for (com.google.firebase.firestore.QueryDocumentSnapshot doc : querySnapshot) {
            // Create a card-like container for each notification
            androidx.cardview.widget.CardView cardView = new androidx.cardview.widget.CardView(this);
            android.widget.LinearLayout.LayoutParams cardParams = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, 0, 0, 24);
            cardView.setLayoutParams(cardParams);
            cardView.setCardElevation(4);
            cardView.setRadius(8);
            cardView.setContentPadding(16, 16, 16, 16);

            // Inner layout for the card content
            android.widget.LinearLayout cardContent = new android.widget.LinearLayout(this);
            cardContent.setOrientation(android.widget.LinearLayout.VERTICAL);

            // Get notification data
            String title = doc.contains("title") ? String.valueOf(doc.get("title")) : "(No title)";
            String message = doc.contains("message") ? String.valueOf(doc.get("message")) : "";
            String type = doc.contains("type") ? String.valueOf(doc.get("type")) : "General";

            Object tObj = doc.get("scheduledTime");
            String dateStr = "(unknown date)";
            String timeStr = "(unknown time)";
            if (tObj instanceof Long) {
                Date date = new Date((Long) tObj);
                dateStr = dateFormat.format(date);
                timeStr = timeFormat.format(date);
            } else if (tObj instanceof Double) {
                Date date = new Date(((Double) tObj).longValue());
                dateStr = dateFormat.format(date);
                timeStr = timeFormat.format(date);
            }

            // Title TextView
            TextView titleView = new TextView(this);
            titleView.setText(title);
            titleView.setTextSize(18);
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            titleView.setTextColor(getResources().getColor(R.color.primary_color));
            titleView.setPadding(0, 0, 0, 8);
            cardContent.addView(titleView);

            // Type TextView
            TextView typeView = new TextView(this);
            typeView.setText("Type: " + type);
            typeView.setTextSize(14);
            typeView.setTextColor(getResources().getColor(R.color.text_primary));
            typeView.setPadding(0, 0, 0, 8);
            cardContent.addView(typeView);

            // Message TextView
            if (!message.isEmpty()) {
                TextView messageView = new TextView(this);
                messageView.setText(message);
                messageView.setTextSize(14);
                messageView.setTextColor(getResources().getColor(R.color.text_primary));
                messageView.setPadding(0, 0, 0, 12);
                cardContent.addView(messageView);
            }

            // Date and Time in a horizontal layout
            android.widget.LinearLayout dateTimeLayout = new android.widget.LinearLayout(this);
            dateTimeLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            dateTimeLayout.setPadding(0, 8, 0, 0);

            // Date TextView
            TextView dateView = new TextView(this);
            dateView.setText("📅 " + dateStr);
            dateView.setTextSize(14);
            dateView.setTextColor(getResources().getColor(R.color.text_primary));
            dateView.setPadding(0, 0, 24, 0);
            dateTimeLayout.addView(dateView);

            // Time TextView
            TextView timeView = new TextView(this);
            timeView.setText("🕒 " + timeStr);
            timeView.setTextSize(14);
            timeView.setTextColor(getResources().getColor(R.color.text_primary));
            dateTimeLayout.addView(timeView);

            cardContent.addView(dateTimeLayout);
            cardView.addView(cardContent);
            layout.addView(cardView);
        }

        // Create ScrollView to contain the layout
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(layout);

        // Show the dialog
        new AlertDialog.Builder(this)
                .setTitle("Scheduled Notifications")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show();
    }

    private void showDatePicker() {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker();
        builder.setTitleText("Select Date");
        if (selectedDateMillis > 0) builder.setSelection(selectedDateMillis);
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(selection -> {
            selectedDateMillis = selection;
            updateDateDisplay();
        });
        picker.show(getSupportFragmentManager(), "DATE_PICKER");
    }

    private void showTimePicker() {
        MaterialTimePicker.Builder builder = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedHour)
                .setMinute(selectedMinute)
                .setTitleText("Select Time");
        MaterialTimePicker picker = builder.build();
        picker.addOnPositiveButtonClickListener(v -> {
            selectedHour = picker.getHour();
            selectedMinute = picker.getMinute();
            updateTimeDisplay();
        });
        picker.show(getSupportFragmentManager(), "TIME_PICKER");
    }

    private void updateDateDisplay() {
        if (selectedDateMillis > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault());
            tvSelectedDate.setText(sdf.format(new Date(selectedDateMillis)));
        } else {
            tvSelectedDate.setText("Tap to select date");
        }
    }

    private void updateTimeDisplay() {
        String timeStr = String.format(Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute);
        tvSelectedTime.setText(timeStr);
    }

    private void scheduleNotification() {
        String title = etNotificationTitle.getText().toString().trim();
        String message = etNotificationMessage.getText().toString().trim();

        if (title.isEmpty()) {
            etNotificationTitle.setError("Title required");
            return;
        }
        if (message.isEmpty()) {
            etNotificationMessage.setError("Message required");
            return;
        }
        if (selectedDateMillis == 0) {
            Toast.makeText(this, "Select date", Toast.LENGTH_SHORT).show();
            return;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(selectedDateMillis);
        cal.set(Calendar.HOUR_OF_DAY, selectedHour);
        cal.set(Calendar.MINUTE, selectedMinute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        long scheduledTime = cal.getTimeInMillis();

        if (scheduledTime <= System.currentTimeMillis()) {
            Toast.makeText(this, "Select a future time", Toast.LENGTH_SHORT).show();
            return;
        }

        String notificationId = "notification_" + System.currentTimeMillis();

        // Save in Firestore
        saveNotification(notificationId, title, message, scheduledTime);

        // Schedule local notification
        scheduleLocalNotification(notificationId, title, message, scheduledTime);

        Toast.makeText(this, "Notification scheduled!", Toast.LENGTH_SHORT).show();

        // Clear form
        etNotificationTitle.setText("");
        etNotificationMessage.setText("");
        selectedDateMillis = 0;
        updateDateDisplay();
        updateTimeDisplay();
    }

    private void saveNotification(String id, String title, String message, long time) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("title", title);
        data.put("message", message);
        data.put("scheduledTime", time);
        data.put("teacherId", currentUser.getUid());
        data.put("teacherEmail", currentUser.getEmail());
        data.put("type", spinnerNotificationType.getSelectedItem().toString());
        data.put("createdAt", FieldValue.serverTimestamp());
        data.put("isSent", false);

        // Save scheduled notification
        db.collection("scheduledNotifications").document(id).set(data)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Scheduled notification saved");
                    // Queue for FCM
                    queueFcmNotification(id, title, message, time);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error saving scheduled notification", e);
                });
    }

    private void queueFcmNotification(String id, String title, String message, long time) {
        Map<String, Object> fcmData = new HashMap<>();
        fcmData.put("notificationId", id);
        fcmData.put("title", title);
        fcmData.put("message", message);
        fcmData.put("scheduledTime", time);
        fcmData.put("teacherId", currentUser.getUid());
        fcmData.put("status", "pending");
        fcmData.put("createdAt", FieldValue.serverTimestamp());

        db.collection("fcmNotifications").document(id).set(fcmData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "FCM notification queued");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error queuing FCM notification", e);
                });
    }

    private void scheduleLocalNotification(String id, String title, String message, long time) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, NotificationReceiver.class);
        intent.putExtra("notificationId", id);
        intent.putExtra("title", title);
        intent.putExtra("message", message);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        }
        Log.d(TAG, "Local notification scheduled for: " + new Date(time));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Notifications permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000; // meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                   Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*
                   Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    public void markAttendance(String studentId, String classId, double lat, double lng,
                               String beaconId, int rssi) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("studentId", studentId);
        doc.put("classId", classId);
        doc.put("timestamp", System.currentTimeMillis());
        doc.put("lat", lat);
        doc.put("lng", lng);
        doc.put("beaconId", beaconId);
        doc.put("rssi", rssi);
        doc.put("status", "pending");

        FirebaseFirestore.getInstance().collection("attendance")
            .add(doc)
            .addOnSuccessListener(d -> Log.d("Attendance", "submitted"))
            .addOnFailureListener(e -> Log.e("Attendance", "failed", e));
    }
}