package online.monarchlabs.sentinel.models;

import android.content.Context;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import online.monarchlabs.sentinel.utils.ChildDisplayName;

import online.monarchlabs.sentinel.AppInfo;
import online.monarchlabs.sentinel.ChildDevice;

/** Reads parent links and app inventory only from the canonical v2 schema. */
public final class ChildDeviceManager {
    private static final String TAG = "ChildDeviceManager";

    private final DatabaseReference v2;

    public interface OnDeviceConnectionListener {
        void onDeviceConnected(ChildDevice device);
        void onDeviceDisconnected(String deviceId);
        void onError(String error);
    }

    public interface OnDevicesLoadedListener {
        void onDevicesLoaded(List<ChildDevice> devices);
        void onError(String error);
    }

    public interface OnAppsLoadedListener {
        void onAppsLoaded(List<AppInfo> apps);
        void onError(String error);
    }

    public ChildDeviceManager(Context context) {
        v2 = FirebaseDatabase.getInstance().getReference("v2");
    }

    public void listenForConnections(String ignoredShareKey,
            OnDeviceConnectionListener listener) {
        DatabaseReference links = parentLinks(listener);
        if (links == null) {
            return;
        }
        links.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot link : snapshot.getChildren()) {
                    ChildDevice device = parseDevice(link);
                    if (device != null) {
                        listener.onDeviceConnected(device);
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                listener.onError(error.getMessage());
            }
        });
    }

    public void loadExistingConnections(String ignoredShareKey,
            OnDevicesLoadedListener listener) {
        String parentUid = currentParentUid();
        if (parentUid == null) {
            listener.onError("Parent authentication is required");
            return;
        }
        v2.child("parent_device_links").child(parentUid).get()
                .addOnSuccessListener(snapshot -> {
                    List<ChildDevice> devices = new ArrayList<>();
                    for (DataSnapshot link : snapshot.getChildren()) {
                        ChildDevice device = parseDevice(link);
                        if (device != null) {
                            devices.add(device);
                        }
                    }
                    listener.onDevicesLoaded(devices);
                })
                .addOnFailureListener(error ->
                        listener.onError(error.getMessage()));
    }

    public void getDeviceApps(String deviceId,
            OnAppsLoadedListener listener) {
        loadApps(deviceId, listener);
    }

    public void getDeviceAppsFromShareKey(String ignoredShareKey,
            String deviceId, OnAppsLoadedListener listener) {
        loadApps(deviceId, listener);
    }

    private void loadApps(String deviceId, OnAppsLoadedListener listener) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            listener.onError("Device is required");
            return;
        }
        v2.child("device_installs").child(deviceId).child("apps").get()
                .addOnSuccessListener(snapshot -> {
                    List<AppInfo> apps = new ArrayList<>();
                    for (DataSnapshot appSnapshot : snapshot.getChildren()) {
                        Object raw = appSnapshot.getValue();
                        if (!(raw instanceof Map)) {
                            continue;
                        }
                        @SuppressWarnings("unchecked")
                        AppInfo app = parseApp((Map<String, Object>) raw);
                        if (app != null) {
                            apps.add(app);
                        }
                    }
                    listener.onAppsLoaded(apps);
                })
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Could not load v2 app inventory", error);
                    listener.onError(error.getMessage());
                });
    }

    private DatabaseReference parentLinks(
            OnDeviceConnectionListener listener) {
        String parentUid = currentParentUid();
        if (parentUid == null) {
            listener.onError("Parent authentication is required");
            return null;
        }
        return v2.child("parent_device_links").child(parentUid);
    }

    private static String currentParentUid() {
        return FirebaseAuth.getInstance().getCurrentUser() == null
                ? null
                : FirebaseAuth.getInstance().getCurrentUser().getUid();
    }

    private static ChildDevice parseDevice(DataSnapshot link) {
        String deviceId = link.child("deviceId").getValue(String.class);
        if (deviceId == null || deviceId.trim().isEmpty()) {
            deviceId = link.getKey();
        }
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return null;
        }
        String name = link.child("deviceName").getValue(String.class);
        if (name == null || name.trim().isEmpty()) {
            name = link.child("childName").getValue(String.class);
        }
        ChildDevice device = new ChildDevice();
        device.deviceId = deviceId;
        device.deviceName = ChildDisplayName.resolve(deviceId, name);
        Long linkedAt = link.child("linkedAt").getValue(Long.class);
        device.lastConnected = linkedAt == null ? 0L : linkedAt;
        device.apps = new ArrayList<>();
        device.appCount = 0;
        return device;
    }

    private static AppInfo parseApp(Map<String, Object> data) {
        String packageName = stringValue(data.get("packageName"));
        String appName = stringValue(data.get("appName"));
        if (appName == null) {
            appName = stringValue(data.get("name"));
        }
        if (packageName == null || appName == null) {
            return null;
        }

        AppInfo app = new AppInfo();
        app.packageName = packageName;
        app.name = appName;
        app.category = stringValue(data.get("category"));
        app.versionName = stringValue(data.get("versionName"));
        Object versionCode = data.get("versionCode");
        if (versionCode instanceof Number) {
            app.versionCode = ((Number) versionCode).longValue();
        }
        app.isSystemApp = Boolean.TRUE.equals(data.get("isSystemApp"));
        app.canBeBlocked = true;
        return app;
    }

    private static String stringValue(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String text = ((String) value).trim();
        return text.isEmpty() ? null : text;
    }
}
