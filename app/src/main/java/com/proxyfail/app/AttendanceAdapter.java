package com.proxyfail.app;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;

public class AttendanceAdapter extends RecyclerView.Adapter<AttendanceAdapter.VH> {
    private static final String TAG = "AttendanceAdapter";
    private List<AttendanceItem> items = new ArrayList<>();
    private FirebaseFirestore db = FirebaseFirestore.getInstance();

    public static class AttendanceItem {
        public final String docId;
        public final String studentId;
        public final String details;
        public final String status;

        public AttendanceItem(String docId, String studentId, String details, String status) {
            this.docId = docId;
            this.studentId = studentId;
            this.details = details;
            this.status = status;
        }
    }

    public AttendanceAdapter(List<AttendanceItem> items) {
        this.items = items;
    }

    public void updateItems(List<AttendanceItem> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_attendance_entry, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AttendanceItem it = items.get(position);
    holder.tvStudent.setText(it.studentId == null ? "Unknown" : it.studentId);
    holder.tvDetails.setText(it.details == null ? "" : it.details);

        holder.btnApprove.setOnClickListener(v -> updateStatus(it.docId, "approved"));
        holder.btnReject.setOnClickListener(v -> updateStatus(it.docId, "rejected"));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private void updateStatus(String docId, String newStatus) {
        if (docId == null) return;
        DocumentReference dr = db.collection("attendance").document(docId);
        dr.update("status", newStatus)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Updated status for " + docId + " to " + newStatus))
                .addOnFailureListener(e -> Log.e(TAG, "Failed updating status", e));
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvStudent, tvDetails;
        Button btnApprove, btnReject;

        VH(@NonNull View itemView) {
            super(itemView);
            tvStudent = itemView.findViewById(R.id.tvStudent);
            tvDetails = itemView.findViewById(R.id.tvDetails);
            btnApprove = itemView.findViewById(R.id.btnApprove);
            btnReject = itemView.findViewById(R.id.btnReject);
        }
    }
}
