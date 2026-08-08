package online.monarchlabs.sentinel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultiDeviceQRShare {
    public String hostDeviceId;
    public String hostDeviceName;
    public String parentId;
    public String parentName;
    public boolean active;
    public long timestamp;
    public Map<String, DeviceAppList> deviceAppLists;
    public int totalDevices;

    public MultiDeviceQRShare() {
        deviceAppLists = new HashMap<>();
        active = true;
    }

    public MultiDeviceQRShare(String hostDeviceId, String hostDeviceName) {
        this.hostDeviceId = hostDeviceId;
        this.hostDeviceName = hostDeviceName;
        this.timestamp = System.currentTimeMillis();
        this.deviceAppLists = new HashMap<>();
        this.totalDevices = 0;
        this.active = true;
    }

    public MultiDeviceQRShare(String hostDeviceId, String hostDeviceName, String parentId, String parentName) {
        this.hostDeviceId = hostDeviceId;
        this.hostDeviceName = hostDeviceName;
        this.parentId = parentId;
        this.parentName = parentName;
        this.timestamp = System.currentTimeMillis();
        this.deviceAppLists = new HashMap<>();
        this.totalDevices = 0;
        this.active = true;
    }

    public void addDeviceAppList(DeviceAppList deviceAppList) {
        if (deviceAppLists == null) {
            deviceAppLists = new HashMap<>();
        }
        deviceAppLists.put(deviceAppList.deviceId, deviceAppList);
        totalDevices = deviceAppLists.size();
    }

    public List<DeviceAppList> getAllDeviceAppLists() {
        if (deviceAppLists == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(deviceAppLists.values());
    }

    public int getTotalApps() {
        int total = 0;
        if (deviceAppLists != null) {
            for (DeviceAppList deviceAppList : deviceAppLists.values()) {
                if (deviceAppList.apps != null) {
                    total += deviceAppList.apps.size();
                }
            }
        }
        return total;
    }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}