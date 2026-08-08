package online.monarchlabs.sentinel;

/**
 * Model class representing an app installation or uninstallation event
 */
public class AppStatusEvent {
    public String appName;
    public String packageName;
    public String action; // "INSTALLED" or "UNINSTALLED"
    public long timestamp;
    public String iconBase64;
    public String eventId; // Firebase push key

    // Default constructor for Firebase
    public AppStatusEvent() {
    }

    public AppStatusEvent(String appName, String packageName, String action, long timestamp) {
        this.appName = appName;
        this.packageName = packageName;
        this.action = action;
        this.timestamp = timestamp;
    }

    public AppStatusEvent(String appName, String packageName, String action,
            long timestamp, String iconBase64) {
        this(appName, packageName, action, timestamp);
        this.iconBase64 = iconBase64;
    }

    public String getAppName() {
        return appName;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getAction() {
        return action;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getIconBase64() {
        return iconBase64;
    }

    public boolean isInstalled() {
        return "INSTALLED".equals(action);
    }

    public boolean isUninstalled() {
        return "UNINSTALLED".equals(action);
    }
}
