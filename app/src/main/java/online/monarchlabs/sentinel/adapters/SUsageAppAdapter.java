package online.monarchlabs.sentinel.adapters;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.content.ContextCompat;

import online.monarchlabs.sentinel.R;
import online.monarchlabs.sentinel.models.SUsageAppInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecyclerView adapter for displaying app usage items.
 * Loads app icons dynamically from packageName.
 */
public class SUsageAppAdapter extends RecyclerView.Adapter<SUsageAppAdapter.ViewHolder> {

    private List<SUsageAppInfo> appUsageList;
    private OnItemClickListener listener;
    private PackageManager packageManager;
    private boolean isRemoteMode = false; // True when viewing remote child's data on parent

    public interface OnItemClickListener {
        void onItemClick(SUsageAppInfo appUsageInfo);
    }

    public SUsageAppAdapter() {
        this.appUsageList = new ArrayList<>();
    }

    public void setPackageManager(PackageManager pm) {
        this.packageManager = pm;
    }

    /**
     * Set remote mode - when true, don't try to load app icons
     * Use this on parent device when viewing child's apps
     */
    public void setRemoteMode(boolean isRemote) {
        this.isRemoteMode = isRemote;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<SUsageAppInfo> newData) {
        this.appUsageList = newData != null ? new ArrayList<>(newData) : new ArrayList<>();
        Collections.sort(this.appUsageList, (a, b) -> {
            if (a.isUninstalled() != b.isUninstalled()) {
                return a.isUninstalled() ? -1 : 1;
            }
            return Long.compare(b.getUsageTimeMillis(), a.getUsageTimeMillis());
        });
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_susage_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SUsageAppInfo appUsage = appUsageList.get(position);
        holder.bind(appUsage);
    }

    @Override
    public int getItemCount() {
        return appUsageList.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView appIcon;
        private final TextView appName;
        private final TextView usageTime;
        private final TextView categoryText;
        private final TextView statusBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.appIcon);
            appName = itemView.findViewById(R.id.appName);
            usageTime = itemView.findViewById(R.id.usageTime);
            categoryText = itemView.findViewById(R.id.categoryText);
            statusBadge = itemView.findViewById(R.id.usageStatusBadge);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onItemClick(appUsageList.get(position));
                }
            });
        }

        void bind(SUsageAppInfo appUsage) {
            appName.setText(appUsage.getAppName());
            usageTime.setText(appUsage.getFormattedUsageTime());

            if (categoryText != null) {
                categoryText.setText(appUsage.getCategory());
            }

            applyInstallState(appUsage);

            // Try to load icon from Base64 first (for remote/parent viewing)
            if (appUsage.getIconBase64() != null && !appUsage.getIconBase64().isEmpty()) {
                try {
                    byte[] decodedBytes = android.util.Base64.decode(appUsage.getIconBase64(),
                            android.util.Base64.NO_WRAP);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0,
                            decodedBytes.length);
                    appIcon.setImageBitmap(bitmap);
                    return; // Icon loaded successfully
                } catch (Exception e) {
                    // Fall through to try PackageManager
                }
            }

            // Load app icon from package name (for local child viewing)
            // Skip icon loading in remote mode (parent viewing child's apps)
            if (!isRemoteMode && packageManager != null) {
                try {
                    Drawable icon = packageManager.getApplicationIcon(appUsage.getPackageName());
                    appIcon.setImageDrawable(icon);
                } catch (PackageManager.NameNotFoundException e) {
                    appIcon.setImageResource(R.mipmap.ic_launcher);
                }
            } else {
                // Remote mode or no package manager - use default icon
                appIcon.setImageResource(R.mipmap.ic_launcher);
            }
        }

        private void applyInstallState(SUsageAppInfo appUsage) {
            boolean uninstalled = appUsage != null && appUsage.isUninstalled();
            if (statusBadge != null) {
                statusBadge.setVisibility(uninstalled ? View.VISIBLE : View.GONE);
                if (uninstalled) {
                    statusBadge.setText("UNINSTALLED");
                    GradientDrawable badgeBackground = new GradientDrawable();
                    badgeBackground.setColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.error_50));
                    badgeBackground.setCornerRadius(dp(10));
                    statusBadge.setBackground(badgeBackground);
                }
            }

            GradientDrawable rowBackground = new GradientDrawable();
            rowBackground.setColor(ContextCompat.getColor(
                    itemView.getContext(),
                    uninstalled ? R.color.neutral_100 : android.R.color.transparent));
            rowBackground.setCornerRadius(dp(8));
            itemView.setBackground(rowBackground);
        }

        private float dp(int value) {
            return value * itemView.getResources().getDisplayMetrics().density;
        }
    }
}
