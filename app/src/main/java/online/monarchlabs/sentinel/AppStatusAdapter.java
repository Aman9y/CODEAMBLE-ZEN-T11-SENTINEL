package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adapter for displaying app installation/uninstallation events.
 */
public class AppStatusAdapter extends RecyclerView.Adapter<AppStatusAdapter.ViewHolder> {

    private final Context context;
    private final List<AppStatusEvent> events;
    private final PackageManager packageManager;
    private final Map<String, String> iconBase64ByPackage = new HashMap<>();
    private final LruCache<String, Bitmap> decodedIconCache = new LruCache<>(80);

    public AppStatusAdapter(Context context, List<AppStatusEvent> events) {
        this.context = context;
        this.events = events;
        this.packageManager = context.getPackageManager();
    }

    public void setIconBase64ByPackage(Map<String, String> iconsByPackage) {
        iconBase64ByPackage.clear();
        if (iconsByPackage != null) {
            iconBase64ByPackage.putAll(iconsByPackage);
        }
        decodedIconCache.evictAll();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_app_status, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppStatusEvent event = events.get(position);

        holder.tvAppName.setText(event.getAppName());
        holder.tvPackageName.setText(event.getPackageName());
        holder.tvTimestamp.setText(formatTimestamp(event.getTimestamp()));

        // App Status intentionally shows only install/uninstall events.
        if (event.isInstalled()) {
            holder.tvAction.setText("INSTALLED");
            holder.tvAction.setBackgroundResource(R.drawable.bg_status_badge_success);
            holder.tvAction.setTextColor(ContextCompat.getColor(context, R.color.success_700));
        } else {
            holder.tvAction.setText("UNINSTALLED");
            holder.tvAction.setBackgroundResource(R.drawable.bg_status_badge_error);
            holder.tvAction.setTextColor(ContextCompat.getColor(context, R.color.error_700));
        }

        loadAppIcon(holder.imgAppIcon, event);
    }

    @Override
    public int getItemCount() {
        return events != null ? events.size() : 0;
    }

    private void loadAppIcon(ImageView imageView, AppStatusEvent event) {
        String packageName = event.getPackageName();
        imageView.setImageResource(R.drawable.ic_app);

        String encodedIcon = event.getIconBase64();
        if (encodedIcon == null || encodedIcon.isEmpty()) {
            encodedIcon = iconBase64ByPackage.get(packageName);
        }
        if (encodedIcon != null && !encodedIcon.isEmpty()) {
            Bitmap bitmap = decodedIconCache.get(packageName + ":" + encodedIcon.hashCode());
            if (bitmap == null) {
                try {
                    byte[] decodedBytes = Base64.decode(encodedIcon, Base64.DEFAULT);
                    bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                    if (bitmap != null) {
                        decodedIconCache.put(packageName + ":" + encodedIcon.hashCode(), bitmap);
                    }
                } catch (Exception ignored) {
                    bitmap = null;
                }
            }
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap);
                return;
            }
        }

        try {
            if (event.isInstalled()) {
                Drawable icon = packageManager.getApplicationIcon(packageName);
                imageView.setImageDrawable(icon);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            imageView.setImageResource(R.drawable.ic_app);
        }
    }

    private String formatTimestamp(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imgAppIcon;
        TextView tvAppName;
        TextView tvPackageName;
        TextView tvTimestamp;
        TextView tvAction;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgAppIcon = itemView.findViewById(R.id.imgAppIcon);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvPackageName = itemView.findViewById(R.id.tvPackageName);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvAction = itemView.findViewById(R.id.tvAction);
        }
    }
}
