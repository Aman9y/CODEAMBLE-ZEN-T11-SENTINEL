package online.monarchlabs.sentinel.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import online.monarchlabs.sentinel.R;
import online.monarchlabs.sentinel.models.PermissionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for displaying permission change events
 */
public class PermissionEventAdapter extends RecyclerView.Adapter<PermissionEventAdapter.EventViewHolder> {

    private List<PermissionEvent> events = new ArrayList<>();
    private Context context;

    public PermissionEventAdapter(Context context) {
        this.context = context;
    }

    public void setEvents(List<PermissionEvent> events) {
        this.events = events;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_permission_event, parent, false);
        return new EventViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EventViewHolder holder, int position) {
        PermissionEvent event = events.get(position);
        holder.bind(event);
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    class EventViewHolder extends RecyclerView.ViewHolder {
        private ImageView ivEventIcon;
        private TextView tvPermissionName;
        private TextView tvAction;
        private TextView tvEffect;
        private TextView tvDate;
        private TextView tvTime;

        public EventViewHolder(@NonNull View itemView) {
            super(itemView);
            ivEventIcon = itemView.findViewById(R.id.ivEventIcon);
            tvPermissionName = itemView.findViewById(R.id.tvPermissionName);
            tvAction = itemView.findViewById(R.id.tvAction);
            tvEffect = itemView.findViewById(R.id.tvEffect);
            tvDate = itemView.findViewById(R.id.tvDate);
            tvTime = itemView.findViewById(R.id.tvTime);
        }

        public void bind(PermissionEvent event) {
            String permissionName = cleanDisplayText(event.getPermissionName());
            String action = cleanDisplayText(event.getAction());
            String effect = cleanDisplayText(event.getEffect());

            tvPermissionName.setText(permissionName);
            tvAction.setText(action);
            tvEffect.setText(effect);
            tvDate.setText(event.getDateFormatted());
            tvTime.setText(event.getTimeFormatted());

            // Set icon based on permission type
            int iconRes = getIconForPermission(permissionName);
            ivEventIcon.setImageResource(iconRes);

            // Color based on action
            if (event.isDeactivation()) {
                tvAction.setBackgroundResource(R.drawable.bg_status_badge_error);
                tvAction.setTextColor(ContextCompat.getColor(context, R.color.error_700));
                ivEventIcon.setBackgroundResource(R.drawable.bg_circle_error_soft);
                ivEventIcon.setColorFilter(ContextCompat.getColor(context, R.color.error_500));
            } else {
                tvAction.setBackgroundResource(R.drawable.bg_status_badge_success);
                tvAction.setTextColor(ContextCompat.getColor(context, R.color.success_700));
                ivEventIcon.setBackgroundResource(R.drawable.bg_circle_success_soft);
                ivEventIcon.setColorFilter(ContextCompat.getColor(context, R.color.success_600));
            }
        }

        private String cleanDisplayText(String value) {
            if (value == null) {
                return "";
            }
            String cleaned = value.replace("\uFFFD", "");
            cleaned = cleaned.replaceAll("[\u00C3\u00C2\u00E2\u00F0][^\\s]*\\s*", "");
            cleaned = cleaned.replaceAll("\\s+", " ").trim();
            if (cleaned.contains("App Blocked")) {
                return "App Blocked";
            }
            if (cleaned.contains("App Unblocked")) {
                return "App Unblocked";
            }
            if (cleaned.contains("Uninstall Protection")) {
                return cleaned.substring(cleaned.indexOf("Uninstall Protection"));
            }
            if (cleaned.contains("Protection Restored")) {
                return cleaned.substring(cleaned.indexOf("Protection Restored"));
            }
            if (cleaned.contains("Usage synced")) {
                return cleaned.substring(cleaned.indexOf("Usage synced"));
            }
            return cleaned;
        }
        private int getIconForPermission(String permissionName) {
            if (permissionName == null)
                return R.drawable.ic_security;

            if (permissionName.contains("Accessibility")) {
                return R.drawable.ic_security;
            } else if (permissionName.contains("Usage")) {
                return R.drawable.ic_chart;
            } else if (permissionName.contains("Notification")) {
                return R.drawable.ic_notifications;
            } else if (permissionName.contains("Battery")) {
                return R.drawable.ic_battery;
            }
            return R.drawable.ic_security;
        }
    }
}
