package online.monarchlabs.sentinel.adapters;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class DaySelectorAdapter extends RecyclerView.Adapter<DaySelectorAdapter.ViewHolder> {

    private final List<Calendar> days;
    private int selectedIndex = -1;
    private OnDayClickListener listener;

    // Colors
    private int selectedColor = Color.parseColor("#4A6CF7");
    private int unselectedTextColor = Color.parseColor("#6B7280");
    private int selectedTextColor = Color.WHITE;

    public interface OnDayClickListener {
        void onDayClick(int index, Calendar day);
    }

    public DaySelectorAdapter(List<Calendar> days, OnDayClickListener listener) {
        this.days = days;
        this.listener = listener;
        this.selectedIndex = days.size() - 1; // Default to last (Today)
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(online.monarchlabs.sentinel.R.layout.item_day_selector, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Calendar cal = days.get(position);
        boolean isSelected = position == selectedIndex;

        // Setup text
        SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", Locale.US);
        SimpleDateFormat dateFormat = new SimpleDateFormat("d", Locale.US);

        holder.tvDay.setText(dayFormat.format(cal.getTime()));
        holder.tvDate.setText(dateFormat.format(cal.getTime()));

        // Styling
        if (isSelected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(selectedColor);
            bg.setCornerRadius(24f);
            holder.itemView.setBackground(bg);

            holder.tvDay.setTextColor(selectedTextColor);
            holder.tvDate.setTextColor(selectedTextColor);
            holder.tvDay.setTypeface(null, Typeface.BOLD);
        } else {
            holder.itemView.setBackground(null);
            holder.tvDay.setTextColor(unselectedTextColor);
            holder.tvDate.setTextColor(Color.BLACK);
            holder.tvDay.setTypeface(null, Typeface.NORMAL);
        }

        holder.itemView.setOnClickListener(v -> {
            int oldIndex = selectedIndex;
            selectedIndex = holder.getAdapterPosition();
            notifyItemChanged(oldIndex);
            notifyItemChanged(selectedIndex);
            if (listener != null)
                listener.onDayClick(selectedIndex, cal);
        });
    }

    @Override
    public int getItemCount() {
        return days.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDay;
        TextView tvDate;

        ViewHolder(View itemView) {
            super(itemView);
            tvDay = itemView.findViewById(online.monarchlabs.sentinel.R.id.tvDay);
            tvDate = itemView.findViewById(online.monarchlabs.sentinel.R.id.tvDate);
        }
    }
}
