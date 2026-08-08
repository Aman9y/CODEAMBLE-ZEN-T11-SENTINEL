package online.monarchlabs.sentinel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class DeviceStatusManager {
    private static final String TAG = "DeviceStatusManager";
    private static final long STATUS_HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes
    private static final int STATUS_SCHEMA_VERSION = 2;
    private Context context;
    private DatabaseReference deviceStatusRef;
    private DatabaseReference connectedRef;
    private String myDeviceId;
    private ValueEventListener connectionListener;
    private boolean isOnline = false;
    private boolean isAppActive = false;
    private boolean isInternetConnected = true; 
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Thread internetCheckThread;
    private Thread heartbeatThread;
    private volatile boolean shouldKeepRunning = true;
    private boolean statusPublishingEnabled = false;
    private String myDeviceModel = "";
    private Map<String, Object> lastWrittenComparableStatus;
    private long lastSuccessfulStatusWriteAt = 0;
    private boolean pendingStatusWrite = false;
    
    // Debouncing mechanism to prevent flickering
    private boolean lastReportedOnlineStatus = false;
    private long lastStatusChangeTime = 0;
    private static final long STABILITY_DELAY = 3000; // 3 seconds stability before reporting change
    
    // Track listeners to prevent duplicates
    private final Map<String, ValueEventListener> activeListeners = new HashMap<>();

    public interface OnDeviceStatusChangeListener {
        void onDeviceStatusChanged(String deviceId, boolean isOnline, long lastSeen);
    }

    public interface OnInternetStatusChangeListener {
        void onInternetStatusChanged(boolean isConnected);
    }

    public DeviceStatusManager(Context context) {
        this.context = context;
        this.myDeviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        this.deviceStatusRef = database.getReference("v2").child("device_status");
        this.connectedRef = database.getReference(".info/connected");
        
        setupConnectionListener();
    }

    private void setupConnectionListener() {
        connectionListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                if (connected != null) {
                    isOnline = connected;
                    Log.d(TAG, "Firebase connection status changed: " + connected);
                    updateMyDeviceStatus();
                } else {
                    Log.w(TAG, "Firebase connection status is null, assuming offline");
                    isOnline = false;
                    updateMyDeviceStatus();
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Connection listener cancelled: " + error.getMessage());
            }
        };
        
        connectedRef.addValueEventListener(connectionListener);
    }

    private void setupNetworkCallback() {
        // Initial internet status check
        checkInternetConnectivity();
        
        // Start continuous internet monitoring
        startPeriodicInternetCheck();
        
        Log.d(TAG, "Network monitoring started with real connectivity checking");
    }

    private void startPeriodicInternetCheck() {
        // Stop existing thread if any
        if (internetCheckThread != null && internetCheckThread.isAlive()) {
            internetCheckThread.interrupt();
        }
        
        internetCheckThread = new Thread(() -> {
            while (shouldKeepRunning) {
                try {
                    // SAFETY CHECK: Exit if thread should stop
                    if (!shouldKeepRunning || Thread.currentThread().isInterrupted()) {
                        Log.d(TAG, "Internet check thread stopping");
                        break;
                    }
                    
                    boolean previousStatus = isInternetConnected;
                    checkInternetConnectivity();
                    
                    if (shouldKeepRunning && previousStatus != isInternetConnected) {
                        updateMyDeviceStatus();
                    }
                    
                    if (previousStatus != isInternetConnected) {
                        Log.d(TAG, "Internet connectivity changed: " + isInternetConnected);
                    }
                    
                    Thread.sleep(15000); // Check every 15 seconds to reduce Firebase load
                } catch (InterruptedException e) {
                    Log.d(TAG, "Internet check thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in internet check: " + e.getMessage());
                    // Don't break on generic exceptions, just continue
                    try {
                        Thread.sleep(10000); // Wait longer on error
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            Log.d(TAG, "Internet check thread finished");
        });
        
        internetCheckThread.setDaemon(true);
        internetCheckThread.start();
    }

    private void checkInternetConnectivity() {
        try {
            // Method 1: Check if we have an active network
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                isInternetConnected = false;
                Log.d(TAG, "No active network - Internet: DISCONNECTED");
                return;
            }
            
            // Method 2: Check network capabilities
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null || 
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                isInternetConnected = false;
                Log.d(TAG, "Network capabilities check failed - Internet: DISCONNECTED");
                return;
            }
            
            // Method 3: Try to reach a reliable server (async to avoid blocking)
            Thread connectivityTestThread = new Thread(() -> {
                try {
                    URL url = new URL("https://8.8.8.8/");
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(3000);
                    
                    int responseCode = connection.getResponseCode();
                    boolean canReachInternet = (responseCode == 200);
                    
                    if (isInternetConnected && !canReachInternet) {
                        isInternetConnected = false;
                        Log.d(TAG, "Internet reachability test failed - Internet: DISCONNECTED");
                        updateMyDeviceStatus();
                    } else if (!isInternetConnected && canReachInternet) {
                        isInternetConnected = true;
                        Log.d(TAG, "Internet reachability test passed - Internet: CONNECTED");
                        updateMyDeviceStatus();
                    }
                    
                    connection.disconnect();
                } catch (IOException e) {
                    if (isInternetConnected) {
                        isInternetConnected = false;
                        Log.d(TAG, "Internet reachability test exception - Internet: DISCONNECTED");
                        updateMyDeviceStatus();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in connectivity test: " + e.getMessage());
                }
            });
            
            connectivityTestThread.setDaemon(true);
            connectivityTestThread.start();
            
            // For now, assume connected if we have validated capabilities
            isInternetConnected = true;
            Log.d(TAG, "Network capabilities validated - Internet: CONNECTED");
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking internet connectivity: " + e.getMessage());
            isInternetConnected = false;
        }
    }

    public void startAsChildDevice(String parentDeviceId, String deviceName) {
        Log.d(TAG, "Starting as child device: " + deviceName);
        shouldKeepRunning = true;
        isAppActive = true;
        statusPublishingEnabled = true;
        myDeviceModel = deviceName != null ? deviceName : "";
        String sessionDeviceId = new SessionManager(context).getChildDeviceId();
        if (sessionDeviceId != null && !sessionDeviceId.isEmpty()) {
            myDeviceId = sessionDeviceId;
        }
        setupNetworkCallback();
        updateMyDeviceStatus();
        
        // CRITICAL FIX: Delay setting up onDisconnect to prevent immediate disconnection
        // Wait for Firebase connection to stabilize before setting disconnect handler
        new Thread(() -> {
            try {
                Thread.sleep(5000); // Wait 5 seconds for Firebase to stabilize
                
                // Only set up onDisconnect if device is still active
                if (shouldKeepRunning && isAppActive) {
                    DatabaseReference myStatusRef = deviceStatusRef.child(myDeviceId);
                    Map<String, Object> disconnectStatus = createDisconnectedStatus();
                    disconnectStatus.put("reason", "app_closed");
                    disconnectStatus.put("timestamp", System.currentTimeMillis());
                    myStatusRef.onDisconnect().setValue(disconnectStatus);
                    Log.d(TAG, "🔒 OnDisconnect handler set up after stabilization period");
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "OnDisconnect setup interrupted");
            }
        }).start();
        
        // Persistent service owns child status; heartbeat is intentionally sparse.
        startHeartbeat(deviceName, "child", parentDeviceId);
        
        Log.d(TAG, "✅ Child device status tracking started - persistent connection enabled");
    }

    public void startAsParentDevice(String deviceName) {
        Log.d(TAG, "Starting as parent device: " + deviceName);
        shouldKeepRunning = true;
        isAppActive = true;
        statusPublishingEnabled = false;
        setupNetworkCallback();
        Log.d(TAG, "Parent device status publishing disabled; child devices own v2/device_status writes");
    }

    public void setAppActive(boolean active) {
        isAppActive = active;
        Log.d(TAG, "App active status changed: " + active);
        
        // For child devices, don't immediately disconnect when app becomes inactive
        // Instead, maintain connection and just update the status
        updateMyDeviceStatus();
        
        if (active) {
            Log.d(TAG, "📱 Child device app is now active - resuming full tracking");
        } else {
            Log.d(TAG, "⏸️ Child device app paused - maintaining connection but marking as inactive");
        }
    }

    private void updateMyDeviceStatus() {
        try {
            if (!statusPublishingEnabled) {
                Log.d(TAG, "Skipping status update - publishing not enabled for this manager");
                return;
            }

            // SAFETY CHECK: Don't update if manager is stopped
            if (!shouldKeepRunning) {
                Log.d(TAG, "Skipping status update - manager is stopped");
                return;
            }
            
            Map<String, Object> status = new HashMap<>();
            SessionManager sessionManager = new SessionManager(context);
            String childName = sessionManager.getChildName();
            if (childName == null || childName.trim().isEmpty()) {
                childName = myDeviceModel;
            }
            
            // For child devices, always maintain some level of connectivity
            // App is considered "online" if it has internet connectivity (even if not active)
            boolean isDeviceOnline = isInternetConnected; // More lenient for child devices
            
            status.put("isOnline", isDeviceOnline);
            status.put("isAppActive", isAppActive);
            status.put("isInternetConnected", isInternetConnected);
            status.put("deviceId", myDeviceId);
            status.put("childName", childName);
            status.put("deviceName", childName);
            status.put("deviceModel", myDeviceModel);
            status.put("connectionPersistent", true); // Flag to indicate this is a persistent connection
            status.put("uninstallProtectionActive", isUninstallProtectionActive());
            status.put("source", "device_status_manager");
            status.put("schemaVersion", STATUS_SCHEMA_VERSION);

            writeStatusIfNeeded(status, "state check");
        } catch (Exception e) {
            Log.e(TAG, "Error updating device status: " + e.getMessage());
        }
    }

    private synchronized void writeStatusIfNeeded(Map<String, Object> baseStatus, String reason) {
        if (deviceStatusRef == null || myDeviceId == null || baseStatus == null) {
            Log.w(TAG, "Cannot update status - null reference, device ID, or status");
            return;
        }

        long now = System.currentTimeMillis();
        Map<String, Object> comparableStatus = new HashMap<>(baseStatus);
        boolean changed = lastWrittenComparableStatus == null
                || !lastWrittenComparableStatus.equals(comparableStatus);
        boolean heartbeatDue = lastSuccessfulStatusWriteAt == 0
                || now - lastSuccessfulStatusWriteAt >= STATUS_HEARTBEAT_INTERVAL_MS;

        if (!changed && !heartbeatDue && !pendingStatusWrite) {
            Log.d(TAG, "V2 device status skipped - unchanged and heartbeat not due");
            return;
        }

        Map<String, Object> status = new HashMap<>(baseStatus);
        status.put("lastSeen", now);
        status.put("lastHeartbeatAt", now);
        status.put("updatedAt", now);
        if (changed) {
            status.put("lastChangedAt", now);
        }

        String writeReason = pendingStatusWrite ? "retry after failed write"
                : changed ? "state changed"
                : heartbeatDue ? "heartbeat due"
                : reason;

        pendingStatusWrite = true;
        Map<String, Object> heartbeatUpdates = new HashMap<>();
        for (Map.Entry<String, Object> entry : status.entrySet()) {
            heartbeatUpdates.put("v2/device_status/" + myDeviceId + "/" + entry.getKey(), entry.getValue());
        }
        heartbeatUpdates.put("v2/device_owners/" + myDeviceId + "/lastSeenAt", now);
        FirebaseDatabase.getInstance().getReference().updateChildren(heartbeatUpdates)
                .addOnSuccessListener(unused -> {
                    synchronized (DeviceStatusManager.this) {
                        lastWrittenComparableStatus = comparableStatus;
                        lastSuccessfulStatusWriteAt = now;
                        pendingStatusWrite = false;
                    }
                    Log.d(TAG, "V2 device status written (" + writeReason + ") - Online: "
                            + baseStatus.get("isOnline") + ", App Active: "
                            + baseStatus.get("isAppActive") + ", Internet: "
                            + baseStatus.get("isInternetConnected"));
                })
                .addOnFailureListener(e -> {
                    synchronized (DeviceStatusManager.this) {
                        if (e.getMessage() != null
                                && e.getMessage().toLowerCase().contains("permission denied")) {
                            pendingStatusWrite = false;
                            statusPublishingEnabled = false;
                        } else {
                            pendingStatusWrite = true;
                        }
                    }
                    if (e.getMessage() != null
                            && e.getMessage().toLowerCase().contains("permission denied")) {
                        Log.w(TAG, "V2 device status ownership missing; status publishing paused");
                    } else {
                        Log.e(TAG, "V2 device status write failed, will retry later: " + e.getMessage());
                    }
                });
    }

    private void startHeartbeat(String deviceName, String deviceType, String parentDeviceId) {
        // Stop existing heartbeat thread if any
        if (heartbeatThread != null && heartbeatThread.isAlive()) {
            heartbeatThread.interrupt();
        }
        
        // Create a background thread for heartbeat
        heartbeatThread = new Thread(() -> {
            while (shouldKeepRunning) { // Continue heartbeat even when app is inactive
                try {
                    // SAFETY CHECK: Exit if thread should stop
                    if (!shouldKeepRunning || Thread.currentThread().isInterrupted()) {
                        Log.d(TAG, "Heartbeat thread stopping");
                        break;
                    }
                    
                    // SAFETY CHECK: Ensure we have valid references
                    if (deviceStatusRef == null || myDeviceId == null) {
                        Log.w(TAG, "Heartbeat stopping - null references");
                        break;
                    }
                    
                    Map<String, Object> status = new HashMap<>();
                    SessionManager sessionManager = new SessionManager(context);
                    String childName = sessionManager.getChildName();
                    if (childName == null || childName.trim().isEmpty()) {
                        childName = deviceName;
                    }
                    
                    // For child devices, maintain connection even when app is not active
                    boolean isDeviceOnline = isInternetConnected; // More lenient for persistent connections
                    
                    status.put("isOnline", isDeviceOnline);
                    status.put("isAppActive", isAppActive);
                    status.put("isInternetConnected", isInternetConnected);
                    status.put("deviceId", myDeviceId);
                    status.put("deviceName", childName);
                    status.put("childName", childName);
                    status.put("deviceModel", deviceName);
                    status.put("deviceType", deviceType);
                    status.put("connectionPersistent", true);
                    status.put("uninstallProtectionActive", isUninstallProtectionActive());
                    status.put("source", "persistent_service");
                    status.put("schemaVersion", STATUS_SCHEMA_VERSION);
                    
                    if (parentDeviceId != null) {
                        status.put("parentDeviceId", parentDeviceId);
                    }
                    
                    writeStatusIfNeeded(status, "periodic heartbeat");
                    
                    Thread.sleep(STATUS_HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Heartbeat thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in heartbeat: " + e.getMessage());
                    // Don't break on generic exceptions, just continue
                    try {
                        Thread.sleep(60000); // Wait longer on error
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            Log.d(TAG, "Heartbeat thread finished");
        });
        
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private Map<String, Object> createDisconnectedStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isOnline", false);
        status.put("isAppActive", false);
        status.put("isInternetConnected", isInternetConnected);
        status.put("lastSeen", System.currentTimeMillis());
        status.put("lastHeartbeatAt", System.currentTimeMillis());
        status.put("deviceId", myDeviceId);
        status.put("connectionPersistent", false);
        status.put("uninstallProtectionActive", isUninstallProtectionActive());
        status.put("disconnectedAt", System.currentTimeMillis());
        status.put("source", "firebase_on_disconnect");
        status.put("schemaVersion", STATUS_SCHEMA_VERSION);
        return status;
    }

    private boolean isUninstallProtectionActive() {
        try {
            return new DeviceAdminHelper(context).isAdminActive();
        } catch (Exception e) {
            Log.w(TAG, "Unable to read uninstall protection state: " + e.getMessage());
            return false;
        }
    }

    public void listenForChildDeviceStatus(String childDeviceId, OnDeviceStatusChangeListener listener) {
        Log.d(TAG, "Listening for child device status: " + childDeviceId);
        
        // Remove existing listener if any to prevent duplicates
        ValueEventListener existingListener = activeListeners.get(childDeviceId);
        if (existingListener != null) {
            deviceStatusRef.child(childDeviceId).removeEventListener(existingListener);
            Log.d(TAG, "Removed existing listener for device: " + childDeviceId);
        }
        
        ValueEventListener newListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        Boolean appActive = snapshot.child("isAppActive").getValue(Boolean.class);
                        Boolean internetConnected = snapshot.child("isInternetConnected").getValue(Boolean.class);
                        Long lastSeen = snapshot.child("lastSeen").getValue(Long.class);
                        
                        if (appActive != null && lastSeen != null) {
                            // Check if device is recent enough for the 5-minute heartbeat cadence.
                            long currentTime = System.currentTimeMillis();
                            long timeDiff = currentTime - lastSeen;
                            boolean isRecentlyActive = timeDiff < 10 * 60 * 1000; // 10 minutes
                            
                            // Device is considered online if app is active AND recently seen
                            // More strict - require both conditions for better offline detection
                            boolean actuallyOnline = appActive && isRecentlyActive;
                            
                            // Store internet connectivity status for parent to display
                            boolean hasInternet = internetConnected != null ? internetConnected : false;
                            
                            // Use debounced reporting to prevent flickering
                            reportStatusWithDebouncing(childDeviceId, actuallyOnline, lastSeen, listener);
                            
                            Log.d(TAG, "Child device " + childDeviceId + " status: " + 
                                  "Online=" + actuallyOnline + 
                                  ", App Active=" + appActive + 
                                  ", Recently Active=" + isRecentlyActive +
                                  ", Internet=" + hasInternet + 
                                  " (last seen: " + timeDiff/1000 + " seconds ago)");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing device status: " + e.getMessage());
                    }
                } else {
                    Log.d(TAG, "No status data for child device: " + childDeviceId);
                    reportStatusWithDebouncing(childDeviceId, false, 0, listener);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error listening for child device status: " + error.getMessage());
                // Remove from active listeners on cancellation
                activeListeners.remove(childDeviceId);
            }
        };
        
        // Add the new listener to Firebase and track it
        deviceStatusRef.child(childDeviceId).addValueEventListener(newListener);
        activeListeners.put(childDeviceId, newListener);
        Log.d(TAG, "Added new status listener for device: " + childDeviceId);
    }

    public void stopListeningForChildDeviceStatus(String childDeviceId) {
        if (childDeviceId == null || childDeviceId.isEmpty()) return;
        try {
            ValueEventListener listener = activeListeners.remove(childDeviceId);
            if (listener != null && deviceStatusRef != null) {
                deviceStatusRef.child(childDeviceId).removeEventListener(listener);
                Log.d(TAG, "Removed status listener for device: " + childDeviceId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing status listener for device " + childDeviceId + ": " + e.getMessage());
        }
    }
    
    /**
     * Reports device status with debouncing to prevent flickering
     */
    private void reportStatusWithDebouncing(String deviceId, boolean isOnline, long lastSeen, OnDeviceStatusChangeListener listener) {
        long currentTime = System.currentTimeMillis();
        
        // If status hasn't changed, just report it immediately
        if (isOnline == lastReportedOnlineStatus) {
            listener.onDeviceStatusChanged(deviceId, isOnline, lastSeen);
            return;
        }
        
        // Status has changed - check if we should wait for stability
        if (lastStatusChangeTime == 0 || (currentTime - lastStatusChangeTime) < STABILITY_DELAY) {
            // First change or not enough time has passed - update change time and wait
            if (lastStatusChangeTime == 0) {
                lastStatusChangeTime = currentTime;
                Log.d(TAG, "Status change detected for " + deviceId + ": " + lastReportedOnlineStatus + " -> " + isOnline + " (starting stability timer)");
            }
            
            // Report the old status for now to avoid flickering
            listener.onDeviceStatusChanged(deviceId, lastReportedOnlineStatus, lastSeen);
            
            // Schedule a delayed check to confirm the status change
            new Thread(() -> {
                try {
                    Thread.sleep(STABILITY_DELAY);
                    
                    // Re-check the status after delay
                    deviceStatusRef.child(deviceId).get().addOnSuccessListener(snapshot -> {
                        if (snapshot.exists()) {
                            Boolean appActive = snapshot.child("isAppActive").getValue(Boolean.class);
                            Long recentLastSeen = snapshot.child("lastSeen").getValue(Long.class);
                            
                            if (appActive != null && recentLastSeen != null) {
                                long recentTimeDiff = System.currentTimeMillis() - recentLastSeen;
                                boolean recentlyActive = recentTimeDiff < 10 * 60 * 1000;
                                boolean confirmedOnline = appActive && recentlyActive;
                                
                                // If status is still the same after stability delay, report the change
                                if (confirmedOnline == isOnline) {
                                    lastReportedOnlineStatus = isOnline;
                                    lastStatusChangeTime = 0; // Reset
                                    listener.onDeviceStatusChanged(deviceId, isOnline, recentLastSeen);
                                    Log.d(TAG, "Status change confirmed for " + deviceId + ": " + isOnline);
                                } else {
                                    Log.d(TAG, "Status change for " + deviceId + " was temporary, keeping previous status");
                                    lastStatusChangeTime = 0; // Reset
                                }
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    Log.d(TAG, "Status stability check interrupted");
                }
            }).start();
            
        } else {
            // Enough time has passed, confirm the status change
            lastReportedOnlineStatus = isOnline;
            lastStatusChangeTime = 0; // Reset
            listener.onDeviceStatusChanged(deviceId, isOnline, lastSeen);
            Log.d(TAG, "Status change confirmed for " + deviceId + " after stability delay: " + isOnline);
        }
    }

    public void listenForChildInternetStatus(String childDeviceId, OnInternetStatusChangeListener listener) {
        Log.d(TAG, "Listening for child internet status: " + childDeviceId);
        
        deviceStatusRef.child(childDeviceId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        Boolean internetConnected = snapshot.child("isInternetConnected").getValue(Boolean.class);
                        Long lastSeen = snapshot.child("lastSeen").getValue(Long.class);
                        
                        // Check if the status is recent for the 5-minute heartbeat cadence.
                        long currentTime = System.currentTimeMillis();
                        long timeDiff = lastSeen != null ? currentTime - lastSeen : Long.MAX_VALUE;
                        boolean isRecentStatus = timeDiff < 10 * 60 * 1000; // 10 minutes
                        
                        // Only consider internet connected if status is recent and explicitly true
                        boolean hasInternet = internetConnected != null && internetConnected && isRecentStatus;
                        
                        listener.onInternetStatusChanged(hasInternet);
                        
                        Log.d(TAG, "Child device " + childDeviceId + " internet status: " + hasInternet + 
                              " (status age: " + timeDiff/1000 + " seconds)");
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing internet status: " + e.getMessage());
                    }
                } else {
                    listener.onInternetStatusChanged(false);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error listening for child internet status: " + error.getMessage());
            }
        });
    }

    public boolean isInternetConnected() {
        return isInternetConnected;
    }

    public void forceRefreshStatus() {
        try {
            Log.d(TAG, "Force refreshing device status");
            
            // Force immediate internet connectivity check
            checkInternetConnectivity();
            
            // Restart internet monitoring if it's not running
            if (internetCheckThread == null || !internetCheckThread.isAlive()) {
                startPeriodicInternetCheck();
            }
            
            // Immediately update status
            updateMyDeviceStatus();
            
            Log.d(TAG, "Forced status refresh completed - Internet: " + isInternetConnected + 
                  ", App Active: " + isAppActive);
        } catch (Exception e) {
            Log.e(TAG, "Error in force refresh status: " + e.getMessage());
        }
    }

    public void stopStatusTracking() {
        try {
            Log.d(TAG, "🛑 Stopping status tracking...");
            
            // Stop all running threads first
            shouldKeepRunning = false;
            
            // Mark as offline
            isAppActive = false;
            
            // Send final offline status update
            try {
                if (deviceStatusRef != null && myDeviceId != null) {
                    Map<String, Object> finalStatus = createDisconnectedStatus();
                    finalStatus.put("reason", "app_stopped");
                    deviceStatusRef.child(myDeviceId).setValue(finalStatus);
                    Log.d(TAG, "📴 Final offline status sent");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to send final status: " + e.getMessage());
            }
            
            // Remove Firebase listeners
            try {
                if (connectionListener != null && connectedRef != null) {
                    connectedRef.removeEventListener(connectionListener);
                    connectionListener = null;
                    Log.d(TAG, "🔥 Firebase connection listener removed");
                }
                
                // Remove all active device status listeners
                for (Map.Entry<String, ValueEventListener> entry : activeListeners.entrySet()) {
                    deviceStatusRef.child(entry.getKey()).removeEventListener(entry.getValue());
                    Log.d(TAG, "🔥 Removed status listener for device: " + entry.getKey());
                }
                activeListeners.clear();
                Log.d(TAG, "🔥 All device status listeners removed");
                
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove listeners: " + e.getMessage());
            }
            
            // Stop and wait for threads to finish
            try {
                if (internetCheckThread != null && internetCheckThread.isAlive()) {
                    internetCheckThread.interrupt();
                    internetCheckThread.join(2000); // Wait up to 2 seconds
                    if (internetCheckThread.isAlive()) {
                        Log.w(TAG, "Internet check thread did not stop gracefully");
                    } else {
                        Log.d(TAG, "🧵 Internet check thread stopped");
                    }
                }
                
                if (heartbeatThread != null && heartbeatThread.isAlive()) {
                    heartbeatThread.interrupt();
                    heartbeatThread.join(2000); // Wait up to 2 seconds
                    if (heartbeatThread.isAlive()) {
                        Log.w(TAG, "Heartbeat thread did not stop gracefully");
                    } else {
                        Log.d(TAG, "🧵 Heartbeat thread stopped");
                    }
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Thread join interrupted: " + e.getMessage());
            }
            
            // Clear references
            internetCheckThread = null;
            heartbeatThread = null;
            
            Log.d(TAG, "✅ Status tracking stopped successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping status tracking: " + e.getMessage());
        }
    }
}
