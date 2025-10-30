package com.proxyfail.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ScheduledNotificationsAdapter extends RecyclerView.Adapter<ScheduledNotificationsAdapter.ViewHolder> {
    
    private List<ScheduledNotificationsActivity.ScheduledNotification> notifications;
    
    public ScheduledNotificationsAdapter(List<ScheduledNotificationsActivity.ScheduledNotification> notifications) {
        this.notifications = notifications;
    }
    
    public void updateNotifications(List<ScheduledNotificationsActivity.ScheduledNotification> newNotifications) {
        this.notifications = newNotifications;
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_scheduled_notification, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ScheduledNotificationsActivity.ScheduledNotification notification = notifications.get(position);
        
        holder.tvTitle.setText(notification.getTitle());
        holder.tvMessage.setText(notification.getMessage());
        holder.tvType.setText(notification.getType());
        holder.tvScheduledTime.setText(notification.getFormattedTime());
        
        // Set status
        if (notification.isSent()) {
            holder.tvStatus.setText("Sent");
            holder.tvStatus.setTextColor(holder.itemView.getContext().getColor(android.R.color.holo_green_dark));
        } else if (notification.isPastDue()) {
            holder.tvStatus.setText("Past Due");
            holder.tvStatus.setTextColor(holder.itemView.getContext().getColor(android.R.color.holo_red_dark));
        } else {
            holder.tvStatus.setText("Scheduled");
            holder.tvStatus.setTextColor(holder.itemView.getContext().getColor(android.R.color.holo_blue_dark));
        }
    }
    
    @Override
    public int getItemCount() {
        return notifications.size();
    }
    
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvMessage, tvType, tvScheduledTime, tvStatus;
        
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvMessage = itemView.findViewById(R.id.tvMessage);
            tvType = itemView.findViewById(R.id.tvType);
            tvScheduledTime = itemView.findViewById(R.id.tvScheduledTime);
            tvStatus = itemView.findViewById(R.id.tvStatus);
        }
    }
}
