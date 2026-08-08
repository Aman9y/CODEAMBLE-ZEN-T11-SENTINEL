package online.monarchlabs.sentinel;

import java.util.List;

public class ChildDevice {
    public String deviceId;
    public String deviceName;
    public String userName; // Child's name (entered during setup)
    public int appCount;
    public long lastConnected;
    public String connectionId;
    public long linkedAt;
    public List<AppInfo> apps;
    public boolean focusModeActive;

    public ChildDevice() {
        // Default constructor for Firebase
    }

    public ChildDevice(String deviceId, String deviceName, List<AppInfo> apps) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.apps = apps;
        this.appCount = apps != null ? apps.size() : 0;
        this.lastConnected = System.currentTimeMillis();
        this.focusModeActive = false;
    }
}