package online.monarchlabs.sentinel;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Monitors child-device heartbeats to detect offline or likely-uninstalled states.
 */
public class UninstallDetectionManager {
    private static final String TAG = "UninstallDetection";

    public static final long OFFLINE_THRESHOLD = 10 * 60 * 1000;
    public static final long SUSPECTED_UNINSTALL_THRESHOLD = 30 * 60 * 1000;
    public static final long CONFIRMED_UNINSTALL_THRESHOLD = 60 * 60 * 1000;

    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_OFFLINE = "offline";
    public static final String STATUS_SUSPECTED_UNINSTALL = "suspected_uninstall";
    public static final String STATUS_LIKELY_UNINSTALLED = "likely_uninstalled";

    private final Context context;
    private final DatabaseReference databaseRef;
    private final Map<String, ValueEventListener> deviceListeners = new HashMap<>();

    public interface DeviceStatusCallback {
        void onStatusChanged(String deviceId, String status, long lastHeartbeat);
    }

    public UninstallDetectionManager(Context context) {
        this.context = context;
        this.databaseRef = FirebaseDatabase.getInstance().getReference();
    }

    public void sendHeartbeat(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG, "Cannot send heartbeat - no device ID");
            return;
        }

        try {
            DatabaseReference heartbeatRef = databaseRef
                    .child("v2")
                    .child("device_status")
                    .child(deviceId);

            Map<String, Object> heartbeatData = new HashMap<>();
            long now = System.currentTimeMillis();
            heartbeatData.put("lastHeartbeatAt", now);
            heartbeatData.put("lastSeen", now);
            heartbeatData.put("deviceAlive", true);
            heartbeatData.put("status", STATUS_ONLINE);
            heartbeatData.put("isOnline", true);
            heartbeatData.put("source", "uninstall_detection_fallback");
            heartbeatData.put("schemaVersion", 2);
            heartbeatData.put("appVersion", getAppVersion());

            heartbeatRef.updateChildren(heartbeatData)
                    .addOnSuccessListener(aVoid ->
                            Log.d(TAG, "Heartbeat sent successfully for device: " + deviceId))
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Failed to send heartbeat: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "Error sending heartbeat: " + e.getMessage());
        }
    }

    public void startMonitoringDevice(String deviceId, DeviceStatusCallback callback) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG, "Cannot monitor - no device ID");
            return;
        }

        stopMonitoringDevice(deviceId);

        DatabaseReference heartbeatRef = databaseRef
                .child("v2")
                .child("device_status")
                .child(deviceId);

        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Long lastHeartbeat = snapshot.child("lastHeartbeatAt").getValue(Long.class);
                if (lastHeartbeat == null) {
                    lastHeartbeat = snapshot.child("lastSeen").getValue(Long.class);
                }
                if (lastHeartbeat == null) {
                    callback.onStatusChanged(deviceId, STATUS_OFFLINE, 0);
                    return;
                }

                long timeSinceHeartbeat = System.currentTimeMillis() - lastHeartbeat;
                String status = calculateStatus(timeSinceHeartbeat);
                callback.onStatusChanged(deviceId, status, lastHeartbeat);

                Log.d(TAG, "Device " + deviceId + " status: " + status
                        + " (last heartbeat " + (timeSinceHeartbeat / 1000) + "s ago)");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error monitoring device " + deviceId + ": " + error.getMessage());
            }
        };

        heartbeatRef.addValueEventListener(listener);
        deviceListeners.put(deviceId, listener);

        Log.d(TAG, "Started monitoring device: " + deviceId);
    }

    public void stopMonitoringDevice(String deviceId) {
        if (deviceId == null) {
            return;
        }

        ValueEventListener listener = deviceListeners.remove(deviceId);
        if (listener != null) {
            databaseRef.child("v2")
                    .child("device_status")
                    .child(deviceId)
                    .removeEventListener(listener);
            Log.d(TAG, "Stopped monitoring device: " + deviceId);
        }
    }

    public void stopAllMonitoring() {
        for (Map.Entry<String, ValueEventListener> entry : deviceListeners.entrySet()) {
            databaseRef.child("v2")
                    .child("device_status")
                    .child(entry.getKey())
                    .removeEventListener(entry.getValue());
        }
        deviceListeners.clear();
        Log.d(TAG, "Stopped all device monitoring");
    }

    public static String calculateStatus(long timeSinceHeartbeat) {
        if (timeSinceHeartbeat < OFFLINE_THRESHOLD) {
            return STATUS_ONLINE;
        } else if (timeSinceHeartbeat < SUSPECTED_UNINSTALL_THRESHOLD) {
            return STATUS_OFFLINE;
        } else if (timeSinceHeartbeat < CONFIRMED_UNINSTALL_THRESHOLD) {
            return STATUS_SUSPECTED_UNINSTALL;
        } else {
            return STATUS_LIKELY_UNINSTALLED;
        }
    }

    public static boolean isUninstalled(String status) {
        return STATUS_SUSPECTED_UNINSTALL.equals(status)
                || STATUS_LIKELY_UNINSTALLED.equals(status);
    }

    public static String getLastSeenText(long lastHeartbeat) {
        if (lastHeartbeat == 0) {
            return "Never connected";
        }

        long timeSince = System.currentTimeMillis() - lastHeartbeat;

        if (timeSince < 60 * 1000) {
            return "Just now";
        } else if (timeSince < 60 * 60 * 1000) {
            long minutes = timeSince / (60 * 1000);
            return minutes + " minute" + (minutes > 1 ? "s" : "") + " ago";
        } else if (timeSince < 24 * 60 * 60 * 1000) {
            long hours = timeSince / (60 * 60 * 1000);
            return hours + " hour" + (hours > 1 ? "s" : "") + " ago";
        } else {
            long days = timeSince / (24 * 60 * 60 * 1000);
            return days + " day" + (days > 1 ? "s" : "") + " ago";
        }
    }

    private String getAppVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
