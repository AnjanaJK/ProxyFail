# Calendar Notification System

## Overview
The calendar notification system allows teachers to schedule notifications for students at specific dates and times. Students will receive these notifications on their phones even when the app is not actively running.

## Features

### For Teachers:
1. **Schedule Notifications**: Set specific date and time for notifications
2. **Notification Types**: Choose from different notification types (Class Reminder, Assignment Due, Exam Notification, etc.)
3. **Custom Messages**: Write custom title and message for notifications
4. **View Scheduled**: See all scheduled notifications with their status
5. **Real-time Updates**: Notifications are sent to students via Firebase Cloud Messaging

### For Students:
1. **Receive Notifications**: Get notifications on their phones at scheduled times
2. **Notification History**: View received notifications
3. **Interactive Notifications**: Tap notifications to open the app

## How to Use

### Teacher Side:
1. Open the Teacher Dashboard
2. Click "Schedule Notifications" button
3. Select date and time using the date/time pickers
4. Choose notification type from dropdown
5. Enter title and message
6. Click "Schedule Notification"
7. View scheduled notifications by clicking "View Scheduled Notifications"

### Student Side:
1. Students automatically receive notifications at scheduled times
2. Notifications appear in the system notification tray
3. Tapping a notification opens the Student Dashboard
4. Students can view notification history in the app

## Technical Implementation

### Components:
- **CalendarNotificationActivity**: Main UI for scheduling notifications
- **ScheduledNotificationsActivity**: View all scheduled notifications
- **NotificationReceiver**: Handles local scheduled notifications
- **MyFirebaseMessagingService**: Handles Firebase Cloud Messaging
- **StudentNotificationService**: Manages student-side notifications

### Data Flow:
1. Teacher schedules notification → Saved to Firestore
2. Local alarm scheduled → Android AlarmManager
3. At scheduled time → NotificationReceiver triggers
4. Notification displayed → Student sees notification
5. Firebase FCM → Sends to all students in course

### Permissions Required:
- `POST_NOTIFICATIONS`: To display notifications
- `SCHEDULE_EXACT_ALARM`: To schedule exact alarm times
- `USE_EXACT_ALARM`: To use exact alarm functionality
- `WAKE_LOCK`: To wake device for notifications

## Database Structure

### Firestore Collections:
- `scheduledNotifications`: Teacher-scheduled notifications
- `fcmNotifications`: FCM notification queue
- `studentNotifications`: Student notification history

### Document Structure:
```json
{
  "id": "notification_1234567890",
  "title": "Class Reminder",
  "message": "Don't forget about tomorrow's class",
  "scheduledTime": 1703123400000,
  "teacherId": "teacher_uid",
  "type": "Class Reminder",
  "isSent": false,
  "createdAt": "timestamp"
}
```

## Testing

### Manual Testing:
1. Schedule a notification for 1-2 minutes in the future
2. Wait for the notification to appear
3. Verify notification content and actions
4. Check Firestore for data persistence

### Integration Testing:
1. Teacher schedules notification
2. Student receives notification at scheduled time
3. Notification opens correct activity when tapped
4. Data persists in Firestore

## Future Enhancements

1. **Recurring Notifications**: Schedule daily/weekly notifications
2. **Notification Templates**: Pre-defined notification templates
3. **Bulk Notifications**: Send to multiple courses at once
4. **Rich Notifications**: Images, actions, and custom layouts
5. **Analytics**: Track notification open rates and engagement
6. **Push Notifications**: Real-time notifications via FCM

## Troubleshooting

### Common Issues:
1. **Notifications not appearing**: Check notification permissions
2. **Scheduled time not working**: Verify device time zone settings
3. **FCM not working**: Check Firebase configuration
4. **Data not saving**: Verify Firestore rules and authentication

### Debug Steps:
1. Check Android logs for error messages
2. Verify Firestore data is being saved
3. Test with different time intervals
4. Check notification channel setup
5. Verify Firebase project configuration
