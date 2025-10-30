package com.proxyfail.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.app.AlertDialog;
import com.google.firebase.firestore.WriteBatch;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.List;

public class TeacherAttendanceReviewActivity extends AppCompatActivity {
    private static final String TAG = "TeacherAttendanceReview";

    private RecyclerView rvAttendance;
    private TextView tvTitle;
    private TextView tvSelectionCount;
    private MaterialButton btnApproveSelected, btnRejectSelected;
    private ChipGroup filterChips;
    private ProgressBar progressBarBatch;
    private FirebaseFirestore db;
    private ListenerRegistration listener;
    private AttendanceAdapter adapter;
    private String currentFilter = "all";

    private String activeSessionId = null; // should be passed via Intent or discovered
    private String lastUndoableId = null;
    private String lastUndoableStatus = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_attendance_review);

        rvAttendance = findViewById(R.id.rvAttendance);
        tvTitle = findViewById(R.id.tvTitle);
        filterChips = findViewById(R.id.filterChips);
    tvSelectionCount = findViewById(R.id.tvSelectionCount);
    btnApproveSelected = findViewById(R.id.btnApproveSelected);
    btnRejectSelected = findViewById(R.id.btnRejectSelected);
    progressBarBatch = findViewById(R.id.progressBarBatch);

        db = FirebaseFirestore.getInstance();

        rvAttendance.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AttendanceAdapter(new ArrayList<>());
        rvAttendance.setAdapter(adapter);

        setupFilterChips();

        // Try to get sessionId from Intent
        if (getIntent().hasExtra("SESSION_ID")) {
            activeSessionId = getIntent().getStringExtra("SESSION_ID");
        }

        if (activeSessionId == null) {
            tvTitle.setText("Attendance Review - No session selected");
            // In a full implementation, we would list teacher's active sessions to choose from.
            return;
        }

        tvTitle.setText("Attendance Review - Session: " + activeSessionId);
        startListening();

    btnApproveSelected.setOnClickListener(v -> approveSelected());
    btnRejectSelected.setOnClickListener(v -> rejectSelected());
    }

    private void setupFilterChips() {
        filterChips.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.chipAll) currentFilter = "all";
            else if (checkedId == R.id.chipPending) currentFilter = "pending";
            else if (checkedId == R.id.chipApproved) currentFilter = "approved";
            else if (checkedId == R.id.chipRejected) currentFilter = "rejected";
            
            if (listener != null) {
                listener.remove();
                startListening();
            }
        });
    }

    private void startListening() {
        if (activeSessionId == null) return;

        Query q = db.collection("attendance")
            .whereEqualTo("sessionId", activeSessionId);
            
        if (!currentFilter.equals("all")) {
            q = q.whereEqualTo("status", currentFilter);
        }
        
        q = q.orderBy("timestamp", Query.Direction.DESCENDING);
            
        listener = q.addSnapshotListener((snapshots, e) -> {
            if (e != null) {
                Log.e(TAG, "Listener error", e);
                return;
            }

            if (snapshots == null) return;

            List<AttendanceAdapter.AttendanceItem> items = new ArrayList<>();
            // Rebuild full list
            for (int i = 0; i < snapshots.size(); i++) {
                try {
                    com.google.firebase.firestore.DocumentSnapshot ds = snapshots.getDocuments().get(i);
                    String id = ds.getId();
                    String studentId = ds.getString("studentId");
                    String status = ds.getString("status");
                    String details = "QR: " + ds.getString("scannedQrValue") + "\nBeacon: " + ds.getString("scannedBeaconId");
                    AttendanceAdapter.AttendanceItem ai = new AttendanceAdapter.AttendanceItem(id, studentId, details, status);
                    items.add(ai);
                } catch (Exception ex) {
                    Log.e(TAG, "Error parsing attendance doc", ex);
                }
            }

            adapter.updateItems(items);
            // kick off async student name lookups
            fetchStudentNames(items);
        });
    }

    private void fetchStudentNames(List<AttendanceAdapter.AttendanceItem> items) {
        // collect unique student IDs
        Set<String> ids = new HashSet<>();
        for (AttendanceAdapter.AttendanceItem it : items) {
            if (it.studentId != null) ids.add(it.studentId);
        }

        for (String sid : ids) {
            db.collection("users").document(sid).get()
                .addOnSuccessListener(doc -> {
                    if (doc != null && doc.exists()) {
                        String name = doc.getString("name");
                        if (name == null) name = doc.getString("email");
                        adapter.setStudentName(sid, name != null ? name : sid);
                    } else {
                        adapter.setStudentName(sid, sid);
                    }
                })
                .addOnFailureListener(e -> {
                    adapter.setStudentName(sid, sid);
                });
        }
    }

    private void onSelectionChanged(int count) {
        if (tvSelectionCount != null) tvSelectionCount.setText(count + " selected");
    }

    private void approveSelected() {
        List<String> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) {
            Toast.makeText(this, "No items selected", Toast.LENGTH_SHORT).show();
            return;
        }
        // Confirmation dialog
        new AlertDialog.Builder(this)
            .setTitle("Approve selected")
            .setMessage("Approve " + ids.size() + " selected attendance records?")
            .setPositiveButton("Approve", (dlg, which) -> performBulkUpdate(ids, true))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void rejectSelected() {
        List<String> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) {
            Toast.makeText(this, "No items selected", Toast.LENGTH_SHORT).show();
            return;
        }
        // Confirmation dialog
        new AlertDialog.Builder(this)
            .setTitle("Reject selected")
            .setMessage("Reject " + ids.size() + " selected attendance records?")
            .setPositiveButton("Reject", (dlg, which) -> performBulkUpdate(ids, false))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void performBulkUpdate(List<String> ids, boolean approved) {
        if (ids == null || ids.isEmpty()) return;

        // Show progress and disable buttons
        progressBarBatch.setVisibility(View.VISIBLE);
        btnApproveSelected.setEnabled(false);
        btnRejectSelected.setEnabled(false);

        WriteBatch batch = db.batch();
        for (String id : ids) {
            com.google.firebase.firestore.DocumentReference ref = db.collection("attendance").document(id);
            Map<String, Object> updates = new HashMap<>();
            updates.put("status", approved ? "approved" : "rejected");
            updates.put("reviewedAt", FieldValue.serverTimestamp());
            updates.put("reviewedBy", FirebaseAuth.getInstance().getCurrentUser().getUid());
            batch.update(ref, updates);
        }

        batch.commit()
            .addOnSuccessListener(aVoid -> {
                Toast.makeText(this, (approved ? "Approved " : "Rejected ") + ids.size() + " records", Toast.LENGTH_SHORT).show();
                adapter.clearSelections();
                onSelectionChanged(0);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Batch update failed", e);
                Toast.makeText(this, "Batch update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            })
            .addOnCompleteListener(task -> {
                progressBarBatch.setVisibility(View.GONE);
                btnApproveSelected.setEnabled(true);
                btnRejectSelected.setEnabled(true);
            });
    }
    
    private void updateAttendanceStatus(String attendanceId, boolean approved) {
        if (attendanceId == null) return;
        
        // Get current status before update for undo
        db.collection("attendance").document(attendanceId).get()
            .addOnSuccessListener(doc -> {
                String oldStatus = doc.getString("status");
                lastUndoableId = attendanceId;
                lastUndoableStatus = oldStatus;
                
                // Perform the update
                db.collection("attendance").document(attendanceId)
                    .update(
                        "status", approved ? "approved" : "rejected",
                        "reviewedAt", FieldValue.serverTimestamp(),
                        "reviewedBy", FirebaseAuth.getInstance().getCurrentUser().getUid()
                    )
                    .addOnSuccessListener(v -> {
                        Log.d(TAG, "Attendance " + attendanceId + " marked as " + (approved ? "approved" : "rejected"));
                        showUndoSnackbar(approved);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error updating attendance status", e);
                        Toast.makeText(this, "Failed to update attendance status", Toast.LENGTH_SHORT).show();
                    });
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Error getting current status for undo", e);
                Toast.makeText(this, "Failed to update attendance status", Toast.LENGTH_SHORT).show();
            });
    }

    private void showUndoSnackbar(boolean wasApproved) {
        if (lastUndoableId == null || lastUndoableStatus == null) return;
        
        String message = "Attendance " + (wasApproved ? "approved" : "rejected");
        Snackbar.make(rvAttendance, message, Snackbar.LENGTH_LONG)
            .setAction("UNDO", v -> undoLastAction())
            .show();
    }
    
    private void undoLastAction() {
        if (lastUndoableId == null || lastUndoableStatus == null) return;
        
        String attendanceId = lastUndoableId;
        String previousStatus = lastUndoableStatus;
        
        db.collection("attendance").document(attendanceId)
            .update(
                "status", previousStatus,
                "reviewedAt", FieldValue.serverTimestamp(),
                "reviewedBy", FirebaseAuth.getInstance().getCurrentUser().getUid()
            )
            .addOnSuccessListener(v -> {
                Log.d(TAG, "Attendance " + attendanceId + " restored to " + previousStatus);
                Toast.makeText(this, "Action undone", Toast.LENGTH_SHORT).show();
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "Error undoing attendance status", e);
                Toast.makeText(this, "Failed to undo action", Toast.LENGTH_SHORT).show();
            });
            
        // Clear undo state
        lastUndoableId = null;
        lastUndoableStatus = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listener != null) listener.remove();
    }

    // Minimal RecyclerView adapter included to avoid adding extra files
    static class AttendanceAdapter extends RecyclerView.Adapter<AttendanceAdapter.VH> {
        static class AttendanceItem {
            final String id;
            final String studentId;
            String studentName;
            final String details;
            String status;
            boolean selected;

            AttendanceItem(String id, String studentId, String details, String status) {
                this.id = id;
                this.studentId = studentId;
                this.studentName = null;
                this.details = details;
                this.status = status;
                this.selected = false;
            }
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvStudentId, tvDetails, tvStatus;
            MaterialButton btnApprove, btnReject;
            MaterialCheckBox cbSelect;
            VH(@NonNull View v) {
                super(v);
                tvStudentId = v.findViewById(R.id.tvStudentId);
                tvDetails = v.findViewById(R.id.tvDetails);
                tvStatus = v.findViewById(R.id.tvStatus);
                btnApprove = v.findViewById(R.id.btnApprove);
                btnReject = v.findViewById(R.id.btnReject);
                cbSelect = v.findViewById(R.id.cbSelect);
            }
        }

        private final List<AttendanceItem> items;

        AttendanceAdapter(List<AttendanceItem> items) { this.items = items; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_attendance_review, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AttendanceItem it = items.get(position);
            String displayName = it.studentName != null ? it.studentName : it.studentId;
            holder.tvStudentId.setText(displayName != null ? displayName : "(unknown)");
            holder.tvDetails.setText(it.details);
            
            // Update status text and button states
            String status = it.status != null ? it.status : "pending";
            holder.tvStatus.setText(status);
            
            int statusColor;
            final boolean canEdit;
            switch (status.toLowerCase()) {
                case "approved":
                    statusColor = holder.itemView.getContext().getColor(android.R.color.holo_green_dark);
                    canEdit = false;
                    break;
                case "rejected":
                    statusColor = holder.itemView.getContext().getColor(android.R.color.holo_red_dark);
                    canEdit = false;
                    break;
                default:
                    statusColor = holder.itemView.getContext().getColor(android.R.color.darker_gray);
                    canEdit = true;
                    break;
            }
            holder.tvStatus.setTextColor(statusColor);

            // Wire up approve/reject buttons
            holder.btnApprove.setEnabled(canEdit);
            holder.btnReject.setEnabled(canEdit);
            
            holder.btnApprove.setOnClickListener(v -> {
                if (canEdit) {
                    ((TeacherAttendanceReviewActivity) v.getContext())
                        .updateAttendanceStatus(it.id, true);
                }
            });
            
            holder.btnReject.setOnClickListener(v -> {
                if (canEdit) {
                    ((TeacherAttendanceReviewActivity) v.getContext())
                        .updateAttendanceStatus(it.id, false);
                }
            });

            // Selection checkbox
            holder.cbSelect.setOnCheckedChangeListener(null);
            holder.cbSelect.setChecked(it.selected);
            holder.cbSelect.setOnCheckedChangeListener((buttonView, isChecked) -> {
                it.selected = isChecked;
                // notify activity about selection change
                if (holder.itemView.getContext() instanceof TeacherAttendanceReviewActivity) {
                    ((TeacherAttendanceReviewActivity) holder.itemView.getContext())
                        .onSelectionChanged(getSelectedCount());
                }
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        void updateItems(List<AttendanceItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        void setStudentName(String studentId, String name) {
            for (int i = 0; i < items.size(); i++) {
                AttendanceItem it = items.get(i);
                if (it.studentId != null && it.studentId.equals(studentId)) {
                    it.studentName = name;
                    notifyItemChanged(i);
                }
            }
        }

        List<String> getSelectedIds() {
            List<String> out = new ArrayList<>();
            for (AttendanceItem it : items) if (it.selected) out.add(it.id);
            return out;
        }

        void clearSelections() {
            boolean any = false;
            for (AttendanceItem it : items) {
                if (it.selected) { it.selected = false; any = true; }
            }
            if (any) notifyDataSetChanged();
        }

        int getSelectedCount() {
            int c = 0; for (AttendanceItem it : items) if (it.selected) c++; return c;
        }
    }
}
