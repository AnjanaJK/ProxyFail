package com.proxyfail.app;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ScheduledNotificationsActivity extends AppCompatActivity {
    
    private static final String TAG = "ScheduledNotifications";
    
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private ScheduledNotificationsAdapter adapter;
    
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scheduled_notifications);
        
        // Initialize Firebase
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        currentUser = auth.getCurrentUser();
        
        if (currentUser == null) {
            finish();
            return;
        }
        
        setupToolbar();
        initializeViews();
        setupRecyclerView();
        loadScheduledNotifications();
    }
    
    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Scheduled Notifications");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }
    
    private void initializeViews() {
        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);
    }
    
    private void setupRecyclerView() {
        adapter = new ScheduledNotificationsAdapter(new ArrayList<>());
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }
    
    private void loadScheduledNotifications() {
        progressBar.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        
        db.collection("scheduledNotifications")
            .whereEqualTo("teacherId", currentUser.getUid())
            .orderBy("scheduledTime", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener(queryDocumentSnapshots -> {
                progressBar.setVisibility(View.GONE);
                
                List<ScheduledNotification> notifications = new ArrayList<>();
                for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                    ScheduledNotification notification = document.toObject(ScheduledNotification.class);
                    notification.setId(document.getId());
                    notifications.add(notification);
                }
                
                adapter.updateNotifications(notifications);
                
                if (notifications.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("No scheduled notifications found");
                }
            })
            .addOnFailureListener(e -> {
                progressBar.setVisibility(View.GONE);
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText("Failed to load notifications");
                Log.e(TAG, "Error loading notifications", e);
            });
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
    
    // Data class for scheduled notifications
    public static class ScheduledNotification {
        private String id;
        private String title;
        private String message;
        private long scheduledTime;
        private String type;
        private boolean isSent;
        
        // Default constructor for Firestore
        public ScheduledNotification() {}
        
        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public long getScheduledTime() { return scheduledTime; }
        public void setScheduledTime(long scheduledTime) { this.scheduledTime = scheduledTime; }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public boolean isSent() { return isSent; }
        public void setSent(boolean sent) { isSent = sent; }
        
        public String getFormattedTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault());
            return sdf.format(new Date(scheduledTime));
        }
        
        public boolean isPastDue() {
            return scheduledTime < System.currentTimeMillis();
        }
    }
}
