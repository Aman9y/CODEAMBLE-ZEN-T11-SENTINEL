package online.monarchlabs.sentinel.models;

/**
 * Represents a permission change event on a child device.
 * Used to track and display permission changes to parents.
 */
public class PermissionEvent {
    private String permissionName;
    private String action; // "ACTIVATED" or "DEACTIVATED"
    private String effect;
    private long timestamp;
    private String dateFormatted;
    private String timeFormatted;

    // Required empty constructor for Firebase
    public PermissionEvent() {
    }

    public PermissionEvent(String permissionName, String action, String effect,
            long timestamp, String dateFormatted, String timeFormatted) {
        this.permissionName = permissionName;
        this.action = action;
        this.effect = effect;
        this.timestamp = timestamp;
        this.dateFormatted = dateFormatted;
        this.timeFormatted = timeFormatted;
    }

    // Getters
    public String getPermissionName() {
        return permissionName;
    }

    public String getAction() {
        return action;
    }

    public String getEffect() {
        return effect;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getDateFormatted() {
        return dateFormatted;
    }

    public String getTimeFormatted() {
        return timeFormatted;
    }

    // Setters (for Firebase deserialization)
    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public void setDateFormatted(String dateFormatted) {
        this.dateFormatted = dateFormatted;
    }

    public void setTimeFormatted(String timeFormatted) {
        this.timeFormatted = timeFormatted;
    }

    /**
     * Helper to check if this is a deactivation/removal event (bad for parent).
     * Older records may store uninstall-protection states as raw action strings.
     */
    public boolean isDeactivation() {
        if (action == null) {
            return false;
        }
        String normalized = action.trim().toUpperCase(java.util.Locale.US);
        return "DEACTIVATED".equals(normalized)
                || normalized.contains("UNINSTALL")
                || normalized.contains("REMOVED")
                || normalized.contains("DISABLED");
    }
}
