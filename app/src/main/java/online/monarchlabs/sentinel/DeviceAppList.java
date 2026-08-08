package online.monarchlabs.sentinel;

import java.util.List;

public class DeviceAppList {
    public String deviceId;
    public String deviceName;
    public long timestamp;
    public List<AppInfo> apps;

    public DeviceAppList() {
        // Default constructor for Firebase
    }

    public DeviceAppList(String deviceId, String deviceName, List<AppInfo> apps) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.apps = apps;
        this.timestamp = System.currentTimeMillis();
    }
}
