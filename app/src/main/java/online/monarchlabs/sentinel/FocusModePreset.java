package online.monarchlabs.sentinel;

import java.util.List;

public class FocusModePreset {
    public String deviceId;
    public String deviceName;
    public List<String> blockedAppPackages;
    public long createdTimestamp;
    public long lastActivatedTimestamp;
    public boolean isActive;

    public FocusModePreset() {
        // Default constructor for Firebase
    }

    public FocusModePreset(String deviceId, String deviceName, List<String> blockedAppPackages) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.blockedAppPackages = blockedAppPackages;
        this.createdTimestamp = System.currentTimeMillis();
        this.isActive = false;
    }
}