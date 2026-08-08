package online.monarchlabs.sentinel.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import online.monarchlabs.sentinel.ChildAppUtils;
import online.monarchlabs.sentinel.ChildDisconnectionCoordinator;
import online.monarchlabs.sentinel.DeviceAdminHelper;
import online.monarchlabs.sentinel.SessionManager;
import online.monarchlabs.sentinel.DeviceStatusManager;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import androidx.annotation.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Background service to maintain persistent connection to parent device
 * This ensures the child device stays connected even when app is closed
 * Also monitors for logout commands from parent
 */
public class PersistentConnectionService extends Service {
    private static final String TAG = "PersistentConnection";
    private static final long CONNECTION_CHECK_INTERVAL = 5 * 60 * 1000; // 5 minutes

    private SessionManager sessionManager;
    private DeviceStatusManager deviceStatusManager;
    private Timer connectionTimer;
    private boolean serviceRunning = false;

    private DatabaseReference v2RemovalRef;
    private ValueEventListener v2RemovalListener;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PersistentConnectionService created");

        sessionManager = new SessionManager(this);
        ChildDisconnectionCoordinator.validateCurrentOwnership(
                this, "persistent_service_create");
        serviceRunning = true;

        // Start connection monitoring
        startConnectionMonitoring();

        // 🚨 CRITICAL: Start logout command monitoring
        startV2RemovalMonitoring();
        publishCurrentDeviceAdminState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "PersistentConnectionService started");

        // Ensure connection monitoring is active
        if (!serviceRunning) {
            serviceRunning = true;
            startConnectionMonitoring();
        }
        if (v2RemovalRef == null || v2RemovalListener == null) {
            startV2RemovalMonitoring();
        }
        ChildDisconnectionCoordinator.validateCurrentOwnership(
                this, "persistent_service_start");
        publishCurrentDeviceAdminState();

        // Return START_STICKY to restart service if killed by system
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // This is not a bound service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "PersistentConnectionService destroyed");

        serviceRunning = false;

        if (v2RemovalRef != null && v2RemovalListener != null) {
            v2RemovalRef.removeEventListener(v2RemovalListener);
            v2RemovalRef = null;
            v2RemovalListener = null;
            Log.d(TAG, "v2 removal listener cleaned up");
        }

        // Clean up timer
        if (connectionTimer != null) {
            connectionTimer.cancel();
            connectionTimer = null;
        }

        // Clean up device status manager
        if (deviceStatusManager != null) {
            // Don't stop tracking - keep connection alive
            deviceStatusManager = null;
        }
    }

    /**
     * Start monitoring connection to parent device
     */
    private void startConnectionMonitoring() {
        Log.d(TAG, "Starting connection monitoring");

        // Check if we have a valid child session
        if (!sessionManager.isLoggedIn() || !"child".equals(sessionManager.getUserType())) {
            Log.d(TAG, "No valid child session - stopping service");
            stopSelf();
            return;
        }

        // Set up periodic connection checks
        connectionTimer = new Timer();
        connectionTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkAndMaintainConnection();
            }
        }, 0, CONNECTION_CHECK_INTERVAL);
    }

    /**
     * Check connection status and restore if needed
     * Also sends heartbeat for uninstall detection
     */
    private void checkAndMaintainConnection() {
        try {
            if (!serviceRunning)
                return;

            String parentName = sessionManager.getParentName();
            String childDeviceId = sessionManager.getChildDeviceId();
            String parentUserId = sessionManager.getParentUserId();

            if (parentName == null || childDeviceId == null) {
                Log.w(TAG, "Missing connection data - cannot maintain connection");
                return;
            }

            // Initialize device status manager if needed
            if (deviceStatusManager == null) {
                Log.d(TAG, "Re-initializing device status manager in background");
                deviceStatusManager = new DeviceStatusManager(this);

                if (parentUserId != null) {
                    String deviceName = ChildAppUtils.getChildDeviceName();
                    deviceStatusManager.startAsChildDevice(parentUserId, deviceName);
                    Log.d(TAG, "✅ Background connection to parent maintained");
                } else {
                    Log.w(TAG, "⚠️ No parent user ID - cannot start child device tracking");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error maintaining connection: " + e.getMessage());
        }
    }

    private void publishCurrentDeviceAdminState() {
        if (sessionManager == null || !sessionManager.isLoggedIn()
                || !"child".equals(sessionManager.getUserType())) {
            return;
        }
        String childDeviceId = sessionManager.getChildDeviceId();
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            return;
        }
        syncDeviceAdminState(childDeviceId);
    }
    /**
     * Publish the actual Android Device Admin state for the parent dashboard.
     */
    private void syncDeviceAdminState(String deviceId) {
        try {
            boolean active = new DeviceAdminHelper(this).isAdminActive();
            Map<String, Object> status = new HashMap<>();
            long now = System.currentTimeMillis();
            status.put("uninstallProtectionActive", active);
            status.put("childName", sessionManager.getChildName());
            status.put("deviceAdminCheckedAt", now);
            status.put("updatedAt", now);
            status.put("schemaVersion", 2);
            status.put("source", "persistent_service");
            status.put("lastOnlineStatus", "Online");

            FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("device_status")
                    .child(deviceId)
                    .updateChildren(status);
        } catch (Exception e) {
            Log.e(TAG, "Failed to sync Device Admin state: " + e.getMessage());
        }
    }

    private void startV2RemovalMonitoring() {
        if (v2RemovalRef != null && v2RemovalListener != null) {
            Log.d(TAG, "v2 removal monitoring already active");
            return;
        }

        String childDeviceId = sessionManager.getChildDeviceId();
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.w(TAG, "No child device ID - cannot monitor v2 removals");
            return;
        }

        v2RemovalRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_removals")
                .child(childDeviceId);

        v2RemovalListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean trigger = snapshot.child("trigger").getValue(Boolean.class);
                Boolean removedByParent = snapshot.child("removed_by_parent").getValue(Boolean.class);
                if (!Boolean.TRUE.equals(trigger) && !Boolean.TRUE.equals(removedByParent)) {
                    return;
                }
                ChildDisconnectionCoordinator.processRemovalMarker(
                        PersistentConnectionService.this, snapshot, v2RemovalRef);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "v2 removal listener cancelled: " + error.getMessage());
            }
        };

        v2RemovalRef.addValueEventListener(v2RemovalListener);
        Log.d(TAG, "v2 removal monitoring started for device: " + childDeviceId);
    }

    /**
     * Static method to start the persistent connection service
     */
    public static void startService(android.content.Context context) {
        try {
            Intent serviceIntent = new Intent(context, PersistentConnectionService.class);
            context.startService(serviceIntent);
            Log.d(TAG, "PersistentConnectionService start requested");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start PersistentConnectionService: " + e.getMessage());
        }
    }

    /**
     * Static method to stop the persistent connection service
     */
    public static void stopService(android.content.Context context) {
        try {
            Intent serviceIntent = new Intent(context, PersistentConnectionService.class);
            context.stopService(serviceIntent);
            Log.d(TAG, "PersistentConnectionService stop requested");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop PersistentConnectionService: " + e.getMessage());
        }
    }
}
