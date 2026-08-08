package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class ConnectedDevicesManager {
    private static final String TAG = "ConnectedDevicesManager";
    private static final String PREF_NAME = "connected_devices";
    private static final String KEY_DEVICES = "devices";
    private static final String KEY_CURRENT_DEVICE = "current_device";

    private Context context;
    private SharedPreferences prefs;
    private Gson gson;
    private List<ChildDevice> connectedDevices;
    private String currentDeviceId;

    public interface OnDevicesLoadedListener {
        void onDevicesLoaded(List<ChildDevice> devices);

        void onCurrentDeviceLoaded(String deviceId);
    }

    public ConnectedDevicesManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        this.connectedDevices = new ArrayList<>();

        // 🔥 NUCLEAR FRESH START - Check for app reinstall and clear everything
        if (isFreshInstall()) {
            performNuclearStorageCleanup();
            Log.d(TAG, "🔥 FRESH INSTALL DETECTED - Complete storage obliteration completed");
        } else {
            loadConnectedDevices();
            loadBlacklistedDevices();
        }
    }

    // Add or update a connected device
    public void addOrUpdateDevice(ChildDevice device) {
        if (device == null || device.deviceId == null)
            return;

        // Remove existing device with same ID
        connectedDevices.removeIf(d -> d.deviceId.equals(device.deviceId));

        // Add the new/updated device
        connectedDevices.add(device);

        // Save to persistent storage
        saveConnectedDevices();

        Log.d(TAG, "Added/updated device: " + device.deviceName + " (ID: " + device.deviceId + ")");
    }

    // Remove a device - ENHANCED FOR BULLETPROOF QR RECONNECTION
    public void removeDevice(String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "⚠️ Cannot remove null deviceId");
            return;
        }

        Log.d(TAG, "🗑️ COMPLETE DEVICE REMOVAL - Starting obliteration of device: " + deviceId);
        Log.d(TAG, "📊 Before removal - Device count: " + connectedDevices.size());

        // Find the device name for logging
        String deviceName = "Unknown";
        for (ChildDevice device : connectedDevices) {
            if (deviceId.equals(device.deviceId)) {
                deviceName = device.deviceName;
                break;
            }
        }

        boolean removed = connectedDevices.removeIf(d -> d.deviceId.equals(deviceId));

        if (removed) {
            Log.d(TAG, "☢️ OBLITERATED device from memory: " + deviceName + " (ID: " + deviceId + ")");

            // If this was the current device, clear current device
            if (deviceId.equals(currentDeviceId)) {
                currentDeviceId = null;
                saveCurrentDevice();
                Log.d(TAG, "🔥 Cleared current device reference");
            }

            // 🔥 BULLETPROOF QR RECONNECTION: Don't add to blacklist - allow QR
            // reconnection
            Log.d(TAG, "🔄 BULLETPROOF MODE: Device can reconnect via QR code");
            // DISABLED: addDeviceToBlacklist(deviceId);

            saveConnectedDevices();
            Log.d(TAG, "💾 PERSISTENCE UPDATED - Saved removal to storage");
            Log.d(TAG, "📊 After removal - Device count: " + connectedDevices.size());

            // Log remaining devices
            if (connectedDevices.isEmpty()) {
                Log.d(TAG, "🎯 COMPLETE OBLITERATION - No devices remain in storage");
            } else {
                Log.d(TAG, "📋 Remaining devices:");
                for (ChildDevice device : connectedDevices) {
                    Log.d(TAG, "  - " + device.deviceName + " (ID: " + device.deviceId + ")");
                }
            }
        } else {
            Log.w(TAG, "⚠️ Device not found for removal: " + deviceId);
        }
    }

    // Get all connected devices (filtering out permanently removed devices)
    public List<ChildDevice> getConnectedDevices() {
        List<ChildDevice> filteredDevices = new ArrayList<>();

        for (ChildDevice device : connectedDevices) {
            if (!isPermanentlyRemoved(device.deviceId)) {
                filteredDevices.add(device);
            } else {
                Log.d(TAG, "🚫 Filtering out permanently removed device from storage list: " + device.deviceName);
            }
        }

        return filteredDevices;
    }

    // Get device by ID
    public ChildDevice getDevice(String deviceId) {
        if (deviceId == null)
            return null;

        for (ChildDevice device : connectedDevices) {
            if (deviceId.equals(device.deviceId)) {
                return device;
            }
        }
        return null;
    }

    // Set current device with validation (legacy call - treat as explicit)
    public void setCurrentDevice(String deviceId) {
        setCurrentDevice(deviceId, true);
    }

    /**
     * Set current device with explicit switch control.
     * If explicit==false, the method will only set the current device when there is
     * no current selection.
     */
    public void setCurrentDevice(String deviceId, boolean explicit) {
        // ENHANCED: Validate device exists before setting
        if (deviceId != null && getDevice(deviceId) == null) {
            Log.w(TAG, "⚠️ Cannot set current device to non-existent device: " + deviceId);
            return;
        }

        // If this is not an explicit user-initiated switch and we already have a
        // selected device, skip changing it
        if (!explicit && this.currentDeviceId != null && deviceId != null && !this.currentDeviceId.equals(deviceId)) {
            Log.d(TAG, "Ignoring non-explicit current device change to '" + deviceId
                    + "' because a device is already selected: '" + this.currentDeviceId + "'");
            return;
        }

        String previousDevice = this.currentDeviceId;
        this.currentDeviceId = deviceId;
        saveCurrentDevice();

        Log.d(TAG, "Current device switched from '" + previousDevice + "' to '" + deviceId + "' (explicit=" + explicit
                + ")");

        // Update last selected timestamp for the new device
        if (deviceId != null) {
            updateDeviceLastSeen(deviceId, System.currentTimeMillis());
        }
    }

    // Get current device ID
    public String getCurrentDeviceId() {
        return currentDeviceId;
    }

    // Get current device
    public ChildDevice getCurrentDevice() {
        return getDevice(currentDeviceId);
    }

    // Check if device is connected
    public boolean isDeviceConnected(String deviceId) {
        return getDevice(deviceId) != null;
    }

    // Get device count
    public int getDeviceCount() {
        return connectedDevices.size();
    }

    // Update device last seen timestamp
    public void updateDeviceLastSeen(String deviceId, long timestamp) {
        ChildDevice device = getDevice(deviceId);
        if (device != null) {
            device.lastConnected = timestamp;
            saveConnectedDevices();
            Log.d(TAG, "Updated last seen for device: " + deviceId);
        }
    }

    // Load connected devices from persistent storage
    private void loadConnectedDevices() {
        try {
            String devicesJson = prefs.getString(KEY_DEVICES, "[]");
            Type listType = new TypeToken<List<ChildDevice>>() {
            }.getType();
            List<ChildDevice> rawDevices = gson.fromJson(devicesJson, listType);

            if (rawDevices == null) {
                rawDevices = new ArrayList<>();
            }

            // Filter out permanently removed devices during loading
            connectedDevices = new ArrayList<>();
            for (ChildDevice device : rawDevices) {
                if (!isPermanentlyRemoved(device.deviceId)) {
                    connectedDevices.add(device);
                    Log.d(TAG, "  ✅ Loaded device: " + device.deviceName + " (ID: " + device.deviceId + ")");
                } else {
                    Log.d(TAG, "  🚫 Skipped permanently removed device: " + device.deviceName + " (ID: "
                            + device.deviceId + ")");
                }
            }

            // Load current device (check if it's permanently removed)
            String savedCurrentDeviceId = prefs.getString(KEY_CURRENT_DEVICE, null);
            if (savedCurrentDeviceId != null && !isPermanentlyRemoved(savedCurrentDeviceId)) {
                currentDeviceId = savedCurrentDeviceId;
            } else if (savedCurrentDeviceId != null) {
                Log.d(TAG, "🚫 Current device was permanently removed, clearing: " + savedCurrentDeviceId);
                currentDeviceId = null;
                saveCurrentDevice(); // Clear the invalid current device
            } else {
                currentDeviceId = null;
            }

            Log.d(TAG, "Loaded " + connectedDevices.size() + " filtered connected devices from storage");

        } catch (Exception e) {
            Log.e(TAG, "Error loading connected devices: " + e.getMessage());
            connectedDevices = new ArrayList<>();
        }
    }

    // Save connected devices to persistent storage
    private void saveConnectedDevices() {
        try {
            String devicesJson = gson.toJson(connectedDevices);
            prefs.edit().putString(KEY_DEVICES, devicesJson).apply();
            Log.d(TAG, "Saved " + connectedDevices.size() + " connected devices to storage");
        } catch (Exception e) {
            Log.e(TAG, "Error saving connected devices: " + e.getMessage());
        }
    }

    // Save current device to persistent storage
    private void saveCurrentDevice() {
        try {
            if (currentDeviceId != null) {
                prefs.edit().putString(KEY_CURRENT_DEVICE, currentDeviceId).apply();
            } else {
                prefs.edit().remove(KEY_CURRENT_DEVICE).apply();
            }
            Log.d(TAG, "Saved current device: " + currentDeviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error saving current device: " + e.getMessage());
        }
    }

    // Load devices with callback
    public void loadDevicesAsync(OnDevicesLoadedListener listener) {
        if (listener != null) {
            listener.onDevicesLoaded(getConnectedDevices());
            listener.onCurrentDeviceLoaded(getCurrentDeviceId());
        }
    }

    // Clear all devices (for logout)
    public void clearAllDevices() {
        connectedDevices.clear();
        currentDeviceId = null;
        prefs.edit().clear().apply();
        Log.d(TAG, "Cleared all connected devices");
    }

    // Get device names for display (filtering out permanently removed devices)
    public List<String> getDeviceNames() {
        Log.d(TAG, "🔍 DEVICE NAMES REQUESTED - Current storage count: " + connectedDevices.size());
        List<String> names = new ArrayList<>();
        for (ChildDevice device : connectedDevices) {
            if (!isPermanentlyRemoved(device.deviceId)) {
                String displayName = device.deviceName + " (" + device.appCount + " apps)";
                names.add(displayName);
                Log.d(TAG, "📱 Device name in storage: " + displayName);
            } else {
                Log.d(TAG, "🚫 Filtering out permanently removed device name: " + device.deviceName);
            }
        }

        if (names.isEmpty()) {
            Log.d(TAG, "✅ DEVICE NAMES EMPTY - No device names to display");
        } else {
            Log.w(TAG, "⚠️ DEVICE NAMES STILL EXIST - " + names.size() + " names found!");
        }

        return names;
    }

    // NUCLEAR VERIFICATION - Check if device truly obliterated
    public boolean isDeviceTrulyGone(String deviceId) {
        // Check in-memory list
        for (ChildDevice device : connectedDevices) {
            if (deviceId.equals(device.deviceId)) {
                Log.w(TAG, "⚠️ DEVICE STILL IN MEMORY: " + device.deviceName);
                return false;
            }
        }

        // Check SharedPreferences directly
        String devicesJson = prefs.getString(KEY_DEVICES, "[]");
        Log.d(TAG, "🔍 Raw storage content: " + devicesJson);

        if (devicesJson.contains(deviceId)) {
            Log.e(TAG, "🚨 DEVICE STILL IN STORAGE: " + deviceId);
            return false;
        }

        Log.d(TAG, "✅ DEVICE TRULY OBLITERATED: " + deviceId);
        return true;
    }

    // Auto-select a device if none is selected (filtering out permanently removed
    // devices)
    public String autoSelectDevice() {
        if (currentDeviceId == null && !connectedDevices.isEmpty()) {
            // Select the most recently connected device that isn't permanently removed
            ChildDevice mostRecent = null;
            for (ChildDevice device : connectedDevices) {
                if (!isPermanentlyRemoved(device.deviceId)) {
                    if (mostRecent == null || device.lastConnected > mostRecent.lastConnected) {
                        mostRecent = device;
                    }
                }
            }

            if (mostRecent != null) {
                setCurrentDevice(mostRecent.deviceId);
                return mostRecent.deviceId;
            } else {
                Log.d(TAG, "🚫 No valid devices available for auto-selection (all permanently removed)");
            }
        }
        return currentDeviceId;
    }

    // Blacklist management to prevent removed devices from auto-reconnecting
    private static final String KEY_BLACKLISTED_DEVICES = "blacklisted_devices";
    private List<String> blacklistedDevices = new ArrayList<>();

    private void loadBlacklistedDevices() {
        try {
            String blacklistJson = prefs.getString(KEY_BLACKLISTED_DEVICES, "[]");
            Type listType = new TypeToken<List<String>>() {
            }.getType();
            blacklistedDevices = gson.fromJson(blacklistJson, listType);

            if (blacklistedDevices == null) {
                blacklistedDevices = new ArrayList<>();
            }

            Log.d(TAG, "Loaded " + blacklistedDevices.size() + " blacklisted devices");
        } catch (Exception e) {
            Log.e(TAG, "Error loading blacklisted devices: " + e.getMessage());
            blacklistedDevices = new ArrayList<>();
        }
    }

    private void saveBlacklistedDevices() {
        try {
            String blacklistJson = gson.toJson(blacklistedDevices);
            prefs.edit().putString(KEY_BLACKLISTED_DEVICES, blacklistJson).apply();
            Log.d(TAG, "Saved " + blacklistedDevices.size() + " blacklisted devices");
        } catch (Exception e) {
            Log.e(TAG, "Error saving blacklisted devices: " + e.getMessage());
        }
    }

    private void addDeviceToBlacklist(String deviceId) {
        if (!blacklistedDevices.contains(deviceId)) {
            blacklistedDevices.add(deviceId);
            saveBlacklistedDevices();
            Log.d(TAG, "🚫 Device added to blacklist: " + deviceId);
        }
    }

    public boolean isDeviceBlacklisted(String deviceId) {
        return blacklistedDevices.contains(deviceId);
    }

    public void removeDeviceFromBlacklist(String deviceId) {
        if (blacklistedDevices.remove(deviceId)) {
            saveBlacklistedDevices();
            Log.d(TAG, "✅ Device removed from blacklist: " + deviceId);
        }
    }

    public void clearBlacklist() {
        blacklistedDevices.clear();
        saveBlacklistedDevices();
        Log.d(TAG, "🗑️ Blacklist cleared");
    }

    // Enhanced addOrUpdateDevice to check blacklist
    public void addOrUpdateDeviceWithBlacklistCheck(ChildDevice device) {
        if (device == null || device.deviceId == null)
            return;

        // Check if device is blacklisted
        if (isDeviceBlacklisted(device.deviceId)) {
            Log.w(TAG, "🚫 Blocked blacklisted device from reconnecting: " + device.deviceId);
            return;
        }

        addOrUpdateDevice(device);
    }

    // Get device info for debugging
    public Map<String, Object> getDeviceInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("totalDevices", connectedDevices.size());
        info.put("currentDeviceId", currentDeviceId);
        info.put("deviceNames", getDeviceNames());
        info.put("blacklistedDevices", blacklistedDevices.size());
        return info;
    }

    /**
     * ENHANCED: Check if system can handle multiple devices properly
     */
    public boolean canHandleMultipleDevices() {
        return connectedDevices.size() > 1;
    }

    /**
     * ENHANCED: Get devices sorted by last connection time (most recent first)
     */
    public List<ChildDevice> getDevicesSortedByConnection() {
        List<ChildDevice> filteredDevices = getConnectedDevices();
        filteredDevices.sort((d1, d2) -> Long.compare(d2.lastConnected, d1.lastConnected));
        return filteredDevices;
    }

    /**
     * ENHANCED: Prevent auto-switching unless explicitly requested
     */
    public String getCurrentDeviceIdWithoutAutoSelect() {
        return currentDeviceId; // Just return current, don't auto-select
    }

    /**
     * 🔍 Check if device is permanently removed (cannot auto-reconnect)
     * This mirrors the implementation in ParentDashboardActivity
     */
    private boolean isPermanentlyRemoved(String deviceId) {
        String parentUserId = getCurrentParentUserId();

        if (parentUserId == null) {
            Log.w(TAG, "⚠️ Cannot check permanent removal - no parent user ID, allowing device");
            return false; // Allow connection if we can't get parent ID
        }

        // Check email-specific removal list first
        String removalKey = parentUserId + "_permanently_removed_devices";
        SharedPreferences removedDevicesPrefs = context.getSharedPreferences(removalKey, Context.MODE_PRIVATE);
        boolean isEmailSpecificRemoved = removedDevicesPrefs.getBoolean(deviceId, false);

        // Removal state is scoped to the signed-in parent account.
        boolean isRemoved = isEmailSpecificRemoved;

        if (isRemoved) {
            Log.d(TAG, "🚫 Device is permanently removed: " + deviceId);
        }

        return isRemoved;
    }

    /**
     * Get current parent user ID for permanent removal checks
     * Uses multiple fallback methods to ensure ID is found
     */
    private String getCurrentParentUserId() {
        try {
            // 1. Try Firebase Auth (Source of Truth)
            com.google.firebase.auth.FirebaseUser firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance()
                    .getCurrentUser();
            if (firebaseUser != null) {
                return firebaseUser.getUid();
            }

            // 2. Try SessionManager
            SessionManager sessionManager = new SessionManager(context);
            if (sessionManager.isLoggedIn()) {
                String userId = sessionManager.getParentUserId();
                if (userId != null && !userId.isEmpty()) {
                    return userId;
                }
                userId = sessionManager.getUserId();
                if (userId != null && !userId.isEmpty()) {
                    return userId;
                }
            }

            // 3. Try raw SharedPreferences (Legacy Fallback)
            SharedPreferences userPrefs = context.getSharedPreferences("user_session", Context.MODE_PRIVATE);
            String userEmail = userPrefs.getString("user_email", null);
            if (userEmail != null && !userEmail.isEmpty()) {
                return userEmail;
            }

            String userPhone = userPrefs.getString("user_phone", null);
            if (userPhone != null && !userPhone.isEmpty()) {
                return userPhone;
            }

            Log.w(TAG, "⚠️ No parent user ID available in ConnectedDevicesManager (checked Firebase, Session, Prefs)");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting parent user ID: " + e.getMessage());
            return null;
        }
    }

    // ============================================================================
    // 🔥 NUCLEAR FRESH START METHODS - Complete storage obliteration on reinstall
    // ============================================================================

    /**
     * Detect if this is a fresh app install (not an update)
     * IMPROVED: Only consider fresh install if BOTH the flag isn't set AND no
     * device data exists
     * This prevents wiping data on app updates/rebuilds
     * 
     * @return true if fresh install, false if app update or existing install
     */
    private boolean isFreshInstall() {
        try {
            SharedPreferences appInstallPrefs = context.getSharedPreferences("app_install_detection",
                    Context.MODE_PRIVATE);
            boolean hasRunBefore = appInstallPrefs.getBoolean("has_run_before", false);

            // IMPROVED: Check if there's existing device data in storage
            // If device data exists, this is NOT a fresh install (it's an update)
            String existingDevicesJson = prefs.getString(KEY_DEVICES, "[]");
            boolean hasExistingDevices = existingDevicesJson != null &&
                    !existingDevicesJson.equals("[]") &&
                    !existingDevicesJson.isEmpty();

            if (hasExistingDevices) {
                // There's existing device data - this is an app UPDATE, not fresh install
                if (!hasRunBefore) {
                    // Mark that the app has run, but DON'T wipe data
                    appInstallPrefs.edit().putBoolean("has_run_before", true).apply();
                    Log.d(TAG, "📱 APP UPDATE DETECTED - Preserving existing device connections");
                }
                return false; // NOT a fresh install - preserve data!
            }

            if (!hasRunBefore) {
                // No existing data AND first run - truly a fresh install
                appInstallPrefs.edit().putBoolean("has_run_before", true).apply();
                Log.d(TAG, "🔥 FRESH INSTALL DETECTED - No existing device data found");
                return true;
            }

            Log.d(TAG, "📱 EXISTING INSTALL - App has run before");
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error detecting fresh install: " + e.getMessage());
            return false; // Default to existing install on error - PRESERVE DATA
        }
    }

    /**
     * Perform nuclear cleanup of ALL storage when fresh install is detected
     * This ensures absolutely no previous data remains from deleted/reinstalled app
     */
    public void performNuclearStorageCleanup() {
        try {
            Log.d(TAG, "🔥 STARTING NUCLEAR STORAGE CLEANUP - Complete obliteration mode");

            // 1. Clear ALL SharedPreferences files used by the app
            clearAllSharedPreferences();

            // 2. Clear connected devices storage (redundant but thorough)
            clearAllDevices();

            // 3. Clear permanent removal lists (fresh start = no blocked devices)
            clearAllRemovalLists();

            // 4. Initialize empty state
            connectedDevices.clear();
            currentDeviceId = null;

            Log.d(TAG, "✅ NUCLEAR STORAGE CLEANUP COMPLETED - App is now in pristine fresh state");
            Log.d(TAG, "📱 All previous connections obliterated - QR scanning required for all new connections");

        } catch (Exception e) {
            Log.e(TAG, "Error during nuclear storage cleanup: " + e.getMessage());
        }
    }

    /**
     * Clear ALL SharedPreferences files used by the application
     * This is the most thorough way to ensure no previous data remains
     */
    private void clearAllSharedPreferences() {
        try {
            Log.d(TAG, "🗑️ Clearing ALL SharedPreferences files...");

            // List of all known SharedPreferences files used by the app
            String[] prefFiles = {
                    "connected_devices", // ConnectedDevicesManager storage
                    "permanently_removed_devices", // Global device removal list
                    "device_blocking", // Email-specific device blocking
                    "user_session", // User session data
                    "device_connection", // Device connection state
                    "logout_state", // Logout tracking
                    "app_prefs", // General app preferences
                    "parent_utils", // Parent utilities
                    "child_session", // Child session data
                    "qr_scanner", // QR scanner preferences
                    "timer_settings", // Timer configuration
                    "focus_mode_settings", // Focus mode preferences
                    "usage_limits", // Usage limit settings
                    "app_install_detection" // Fresh install detection (keep this one)
            };

            // Clear each SharedPreferences file
            for (String prefFile : prefFiles) {
                if (!prefFile.equals("app_install_detection")) { // Don't clear the install detection
                    SharedPreferences prefs = context.getSharedPreferences(prefFile, Context.MODE_PRIVATE);
                    prefs.edit().clear().apply();
                    Log.d(TAG, "  ✅ Cleared: " + prefFile);
                }
            }

            Log.d(TAG, "🔥 ALL SharedPreferences cleared - Complete fresh start achieved");

        } catch (Exception e) {
            Log.e(TAG, "Error clearing SharedPreferences: " + e.getMessage());
        }
    }

    /**
     * Clear all removal lists (permanent removal tracking)
     * Fresh install = no devices should be blocked from reconnecting
     */
    private void clearAllRemovalLists() {
        try {
            Log.d(TAG, "🧹 Clearing all permanent removal lists...");

            // Clear global removal list
            SharedPreferences globalRemovedPrefs = context.getSharedPreferences("permanently_removed_devices",
                    Context.MODE_PRIVATE);
            globalRemovedPrefs.edit().clear().apply();

            // Clear email-specific removal lists
            SharedPreferences deviceBlockingPrefs = context.getSharedPreferences("device_blocking",
                    Context.MODE_PRIVATE);
            deviceBlockingPrefs.edit().clear().apply();

            Log.d(TAG, "✅ All removal lists cleared - No devices blocked from reconnection");

        } catch (Exception e) {
            Log.e(TAG, "Error clearing removal lists: " + e.getMessage());
        }
    }
}
