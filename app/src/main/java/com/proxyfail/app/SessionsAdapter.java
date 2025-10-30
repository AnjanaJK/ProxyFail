package com.proxyfail.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SessionsAdapter extends RecyclerView.Adapter<SessionsAdapter.VH> {
    public interface OnSelectListener { void onSelect(SessionItem item); }
    private OnSelectListener listener;

    public static class SessionItem {
        public final String docId;
        public final String qr;
        public final String teacherId;
        public SessionItem(String d, String q, String t) { docId = d; qr=q; teacherId=t; }
    }

    private List<SessionItem> items = new ArrayList<>();

    public SessionsAdapter(List<SessionItem> items) { this.items = items; }

    public void updateItems(List<SessionItem> newItems) { this.items = newItems; notifyDataSetChanged(); }
    public void setOnSelectListener(OnSelectListener l) { this.listener = l; }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        SessionItem it = items.get(position);
        holder.title.setText(it.docId);
        holder.subtitle.setText("QR: " + (it.qr==null?"-":it.qr) + " teacher:" + (it.teacherId==null?"-":it.teacherId));
        holder.itemView.setOnClickListener(v -> { if (listener!=null) listener.onSelect(it); });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView title, subtitle;
        VH(@NonNull View itemView) { super(itemView); title = itemView.findViewById(android.R.id.text1); subtitle = itemView.findViewById(android.R.id.text2); }
    }
}
