package online.monarchlabs.sentinel;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class PresetListAdapter extends RecyclerView.Adapter<PresetListAdapter.ViewHolder> {
    private Context context;
    private List<FocusModePreset> presetList;
    private OnPresetActionListener actionListener;

    public interface OnPresetActionListener {
        void onTogglePreset(FocusModePreset preset);
        void onDeletePreset(FocusModePreset preset);
        void onViewDetails(FocusModePreset preset);
    }

    public PresetListAdapter(Context context, List<FocusModePreset> presetList) {
        this.context = context;
        this.presetList = presetList;
    }

    public void setOnPresetActionListener(OnPresetActionListener listener) {
        this.actionListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_app_preset, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FocusModePreset preset = presetList.get(position);
        holder.tvDeviceName.setText(preset.deviceName);
        holder.tvBlockedCount.setText("Blocked Apps: " + preset.blockedAppPackages.size());
        holder.btnToggle.setText(preset.isActive ? "Deactivate" : "Activate");

        holder.btnToggle.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onTogglePreset(preset);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onDeletePreset(preset);
        });
        holder.itemView.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onViewDetails(preset);
        });
    }

    @Override
    public int getItemCount() {
        return presetList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvDeviceName, tvBlockedCount;
        Button btnToggle, btnDelete;
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvDeviceName = itemView.findViewById(R.id.tvDeviceName);
            tvBlockedCount = itemView.findViewById(R.id.tvBlockedCount);
            btnToggle = itemView.findViewById(R.id.btnToggle);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
} 