package com.proxyfail.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class NotificationReceiver extends BroadcastReceiver {
    
    private static final String TAG = "NotificationReceiver";
    private static final String CHANNEL_ID = "notification_channel";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Notification received");
        
        String notificationId = intent.getStringExtra("notificationId");
        String title = intent.getStringExtra("title");
        String message = intent.getStringExtra("message");
        
        if (title == null || message == null) {
            Log.e(TAG, "Missing notification data");
            return;
        }
        
        // Create notification
        createNotification(context, notificationId, title, message);
        
        // Update Firestore to mark as sent
        updateNotificationStatus(context, notificationId);
    }
    
    private void createNotification(Context context, String notificationId, String title, String message) {
        NotificationManager notificationManager = (NotificationManager) 
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        
        // Create notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Scheduled Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Notifications scheduled by teachers");
            notificationManager.createNotificationChannel(channel);
        }
        
        // Create intent for when notification is tapped
        Intent tapIntent = new Intent(context, StudentDashboardActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            notificationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Build notification
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.attendancee) // Use your app icon
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(Notification.DEFAULT_ALL)
            .build();
        
        // Show notification
        int notificationIdInt = notificationId.hashCode();
        notificationManager.notify(notificationIdInt, notification);
        
        Log.d(TAG, "Notification displayed: " + title);
    }
    
    private void updateNotificationStatus(Context context, String notificationId) {
        // This would typically update Firestore to mark the notification as sent
        // For now, we'll just log it
        Log.d(TAG, "Notification status updated for: " + notificationId);
    }
}
