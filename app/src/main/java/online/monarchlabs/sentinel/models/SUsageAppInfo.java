package online.monarchlabs.sentinel.models;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;

/**
 * Firebase-serializable model for app usage information.
 * Adapted from SUSAGE AppUsageInfo - doesn't include Drawable icon for Firebase
 * serialization.
 */
@IgnoreExtraProperties
public class SUsageAppInfo {
    private String packageName;
    private String appName;
    private long usageTimeMillis;
    private String category;
    private String iconBase64; // App icon encoded as Base64 for Firebase sync
    private boolean installed;
    private String status;
    private long uninstalledAt;
    private long reinstalledAt;

    // Required empty constructor for Firebase
    public SUsageAppInfo() {
        this.packageName = "";
        this.appName = "";
        this.usageTimeMillis = 0;
        this.category = "Other";
        this.iconBase64 = null;
        this.installed = true;
        this.status = "installed";
        this.uninstalledAt = 0L;
        this.reinstalledAt = 0L;
    }

    public SUsageAppInfo(String packageName, String appName, long usageTimeMillis, String category) {
        this.packageName = packageName;
        this.appName = appName;
        this.usageTimeMillis = usageTimeMillis;
        this.category = category != null ? category : "Other";
        this.iconBase64 = null;
        this.installed = true;
        this.status = "installed";
        this.uninstalledAt = 0L;
        this.reinstalledAt = 0L;
    }

    // Getters and Setters
    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public long getUsageTimeMillis() {
        return usageTimeMillis;
    }

    public void setUsageTimeMillis(long usageTimeMillis) {
        this.usageTimeMillis = usageTimeMillis;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getIconBase64() {
        return iconBase64;
    }

    public void setIconBase64(String iconBase64) {
        this.iconBase64 = iconBase64;
    }

    public boolean isInstalled() {
        return installed;
    }

    public void setInstalled(boolean installed) {
        this.installed = installed;
        if (!installed && (status == null || status.isEmpty() || "installed".equals(status))) {
            this.status = "uninstalled";
        } else if (installed && (status == null || status.isEmpty() || "uninstalled".equals(status))) {
            this.status = "installed";
        }
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status != null ? status : (installed ? "installed" : "uninstalled");
        this.installed = !"uninstalled".equalsIgnoreCase(this.status);
    }

    public long getUninstalledAt() {
        return uninstalledAt;
    }

    public void setUninstalledAt(long uninstalledAt) {
        this.uninstalledAt = uninstalledAt;
    }

    public long getReinstalledAt() {
        return reinstalledAt;
    }

    public void setReinstalledAt(long reinstalledAt) {
        this.reinstalledAt = reinstalledAt;
    }

    @Exclude
    public boolean isUninstalled() {
        return !installed || "uninstalled".equalsIgnoreCase(status);
    }

    /**
     * Returns formatted usage time string (e.g., "1h 30m" or "45 minutes")
     */
    @Exclude
    public String getFormattedUsageTime() {
        long totalMinutes = usageTimeMillis / (1000 * 60);

        if (totalMinutes < 1) {
            return "< 1 minute";
        }

        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            if (minutes > 0) {
                return hours + "h " + minutes + "m";
            }
            return hours + " hour" + (hours > 1 ? "s" : "");
        }

        return minutes + " minute" + (minutes > 1 ? "s" : "");
    }

    /**
     * Returns short formatted time (e.g., "1h 30m" or "45m")
     */
    @Exclude
    public String getShortFormattedTime() {
        long totalMinutes = usageTimeMillis / (1000 * 60);

        if (totalMinutes < 1) {
            return "0m";
        }

        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            if (minutes > 0) {
                return hours + "h " + minutes + "m";
            }
            return hours + "h";
        }

        return minutes + "m";
    }

    /**
     * Returns usage time in minutes
     */
    @Exclude
    public long getUsageTimeMinutes() {
        return usageTimeMillis / (1000 * 60);
    }

    /**
     * Returns usage time in hours (as float)
     */
    @Exclude
    public float getUsageTimeHours() {
        return usageTimeMillis / (1000f * 60f * 60f);
    }
}
