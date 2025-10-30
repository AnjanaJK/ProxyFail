package com.proxyfail.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import java.util.HashMap;
import java.util.Map;

public class StudentNotificationService {
    
    private static final String TAG = "StudentNotificationService";
    private static final String CHANNEL_ID = "student_notifications";
    
    private Context context;
    private FirebaseFirestore db;
    private FirebaseAuth auth;
    
    public StudentNotificationService(Context context) {
        this.context = context;
        this.db = FirebaseFirestore.getInstance();
        this.auth = FirebaseAuth.getInstance();
    }
    
    public void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Student Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Notifications for students from teachers");
            
            NotificationManager notificationManager = (NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    public void showNotification(String title, String message, String notificationId) {
        setupNotificationChannel();
        
        NotificationManager notificationManager = (NotificationManager) 
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        // Create intent for when notification is tapped
        Intent intent = new Intent(context, StudentDashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("notificationId", notificationId);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            notificationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Build notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.attendancee)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL);
        
        // Show notification
        int notificationIdInt = notificationId.hashCode();
        notificationManager.notify(notificationIdInt, builder.build());
        
        Log.d(TAG, "Student notification displayed: " + title);
    }
    
    public void markNotificationAsRead(String notificationId) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;
        
        Map<String, Object> update = new HashMap<>();
        update.put("readAt", System.currentTimeMillis());
        update.put("isRead", true);
        
        db.collection("studentNotifications")
            .document(notificationId)
            .update(update)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "Notification marked as read: " + notificationId);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Failed to mark notification as read", e);
            });
    }
    
    public void saveStudentNotification(String notificationId, String title, String message, String teacherId) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;
        
        Map<String, Object> notification = new HashMap<>();
        notification.put("id", notificationId);
        notification.put("title", title);
        notification.put("message", message);
        notification.put("teacherId", teacherId);
        notification.put("studentId", user.getUid());
        notification.put("receivedAt", System.currentTimeMillis());
        notification.put("isRead", false);
        
        db.collection("studentNotifications")
            .document(notificationId)
            .set(notification)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "Student notification saved");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Failed to save student notification", e);
            });
    }
}
