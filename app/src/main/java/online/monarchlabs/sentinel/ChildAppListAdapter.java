package online.monarchlabs.sentinel;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ChildAppListAdapter extends RecyclerView.Adapter<ChildAppListAdapter.AppViewHolder> {
    
    private List<AppInfo> appList;
    private OnAppSelectionListener listener;
    
    public interface OnAppSelectionListener {
        void onAppSelected(AppInfo appInfo, boolean isSelected);
    }
    
    public ChildAppListAdapter(List<AppInfo> appList, OnAppSelectionListener listener) {
        this.appList = appList;
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_child_app, parent, false);
        return new AppViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo appInfo = appList.get(position);
        holder.bind(appInfo);
    }
    
    @Override
    public int getItemCount() {
        return appList.size();
    }
    
    class AppViewHolder extends RecyclerView.ViewHolder {
        private CheckBox checkBox;
        private TextView tvAppName;
        private TextView tvPackageName;
        private TextView tvCategory;
        private TextView tvSystemApp;
        
        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.cbSelectApp);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvPackageName = itemView.findViewById(R.id.tvPackageName);
            tvCategory = itemView.findViewById(R.id.tvCategory);
            tvSystemApp = itemView.findViewById(R.id.tvSystemApp);
        }
        
        public void bind(AppInfo appInfo) {
            tvAppName.setText(appInfo.name != null ? appInfo.name : appInfo.packageName);
            tvPackageName.setText(appInfo.packageName);
            tvCategory.setText(appInfo.category != null ? appInfo.category : "Unknown");
            
            // Show system app indicator
            if (appInfo.isSystemApp) {
                tvSystemApp.setVisibility(View.VISIBLE);
                tvSystemApp.setText("System App");
            } else {
                tvSystemApp.setVisibility(View.GONE);
            }
            
            // Set checkbox listener
            checkBox.setOnCheckedChangeListener(null); // Clear previous listener
            checkBox.setChecked(appInfo.isSelected);
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                appInfo.isSelected = isChecked;
                if (listener != null) {
                    listener.onAppSelected(appInfo, isChecked);
                }
            });
            
            // Make the entire item clickable
            itemView.setOnClickListener(v -> {
                boolean newState = !checkBox.isChecked();
                checkBox.setChecked(newState);
            });
        }
    }
} 