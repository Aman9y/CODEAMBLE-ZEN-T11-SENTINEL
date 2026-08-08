package online.monarchlabs.sentinel.models;

import online.monarchlabs.sentinel.utils.AppCategorizer;

/**
 * Extended app info with usage data and category
 */
public class AppWithUsage {
    private String packageName;
    private String appName;
    private String iconBase64;
    private boolean isSystemApp;
    private long usageTimeMs; // Usage time for selected date
    private AppCategorizer.AppCategory category;
    private boolean isTopUsed;
    private boolean hasTimer;
    private long timerLimitMs;

    public AppWithUsage(android.content.Context context, String packageName, String appName, String iconBase64,
            boolean isSystemApp) {
        this.packageName = packageName;
        this.appName = appName;
        this.iconBase64 = iconBase64;
        this.isSystemApp = isSystemApp;
        this.usageTimeMs = 0;
        // Use smart categorization with Context
        this.category = AppCategorizer.getCategory(context, packageName);
        this.isTopUsed = false;
        this.hasTimer = false;
        this.timerLimitMs = 0;
    }

    // Getters and setters
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

    public String getIconBase64() {
        return iconBase64;
    }

    public void setIconBase64(String iconBase64) {
        this.iconBase64 = iconBase64;
    }

    public boolean isSystemApp() {
        return isSystemApp;
    }

    public void setSystemApp(boolean systemApp) {
        isSystemApp = systemApp;
    }

    public long getUsageTimeMs() {
        return usageTimeMs;
    }

    public void setUsageTimeMs(long usageTimeMs) {
        this.usageTimeMs = usageTimeMs;
    }

    public AppCategorizer.AppCategory getCategory() {
        return category;
    }

    public void setCategory(AppCategorizer.AppCategory category) {
        this.category = category;
    }

    public boolean isTopUsed() {
        return isTopUsed;
    }

    public void setTopUsed(boolean topUsed) {
        isTopUsed = topUsed;
    }

    public boolean hasTimer() {
        return hasTimer;
    }

    public void setHasTimer(boolean hasTimer) {
        this.hasTimer = hasTimer;
    }

    public long getTimerLimitMs() {
        return timerLimitMs;
    }

    public void setTimerLimitMs(long timerLimitMs) {
        this.timerLimitMs = timerLimitMs;
    }

    /**
     * Format usage time as human-readable string
     */
    public String getUsageTimeFormatted() {
        if (usageTimeMs == 0)
            return "No usage";

        long hours = usageTimeMs / (1000 * 60 * 60);
        long minutes = (usageTimeMs / (1000 * 60)) % 60;

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }
}
