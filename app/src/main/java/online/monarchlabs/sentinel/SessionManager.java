package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class SessionManager {
    private static final String TAG = "SessionManager";
    private static final String PREF_NAME = "MasterAppSession";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_USER_TYPE = "userType";
    private static final String KEY_PHONE_NUMBER = "phoneNumber";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_DEVICE_NAME = "deviceName";
    private static final String KEY_QR_SHARE_KEY = "qrShareKey";
    private static final String KEY_PARENT_NAME = "parentName";
    private static final String KEY_CHILD_DEVICE_ID = "childDeviceId";
    private static final String KEY_CONNECTION_ACTIVE = "connectionActive";
    private static final String KEY_LAST_LOGIN_TIME = "lastLoginTime";
    private static final String KEY_PARENT_USER_ID = "parentUserId";
    private static final String KEY_CHILD_NAME = "childName";
    private static final String KEY_DEVICE_MODEL = "deviceModel";
    private static final String KEY_CONNECTION_ID = "connectionId";
    private static final String KEY_CONNECTION_LINKED_AT = "connectionLinkedAt";

    private static final String KEY_PARENT_PROFILE_NAME = "parentProfileName";

    private SharedPreferences pref;
    private Context context;

    public SessionManager(Context context) {
        this.context = context;
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // Save login session for Parent
    public void saveParentSession(String phoneNumber, String userId, String deviceName, String parentName) {
        // 🔧 ROLE SWITCH FIX: Clear any existing child session data first
        clearChildSessionData();

        pref.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_USER_TYPE, "parent")
            .putString(KEY_PHONE_NUMBER, phoneNumber)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_PARENT_USER_ID, userId)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putString(KEY_PARENT_PROFILE_NAME, parentName)
            .putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            .apply();

        saveParentUserId(userId);

        Log.d(TAG, "Parent session saved: " + phoneNumber + " - " + deviceName + " (" + parentName + ")");
        Log.d(TAG, "  Previous child data cleared to prevent role confusion");
    }

    public void saveParentSession(String phoneNumber, String userId, String deviceName) {
        saveParentSession(phoneNumber, userId, deviceName, "Parent");
    }

    public void saveParentSessionWithoutPhone(String userId, String deviceName, String parentName) {
        saveParentSession("", userId, deviceName, parentName);
    }

    // Save login session for Child
    public void saveChildSession(String deviceId, String parentName, String shareKey) {
        saveChildSession(deviceId, parentName, shareKey, getConnectionId(), getConnectionLinkedAt());
    }

    public void saveChildSession(String deviceId, String parentName, String shareKey,
            String connectionId, long connectionLinkedAt) {
        // 🛡️ BULLETPROOF FRESH START: Clear any old connection traces first
        prepareFreshChildConnection();

        // 🔧 ROLE SWITCH FIX: Clear any existing parent session data first
        clearParentSessionData();

        SharedPreferences.Editor editor = pref.edit();
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USER_TYPE, "child");
        editor.putString(KEY_CHILD_DEVICE_ID, deviceId);
        editor.putString(KEY_PARENT_NAME, parentName);
        editor.putString(KEY_QR_SHARE_KEY, shareKey);
        if (connectionId != null && !connectionId.trim().isEmpty()) {
            editor.putString(KEY_CONNECTION_ID, connectionId);
        }
        if (connectionLinkedAt > 0) {
            editor.putLong(KEY_CONNECTION_LINKED_AT, connectionLinkedAt);
        }
        editor.putBoolean(KEY_CONNECTION_ACTIVE, true);
        editor.putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis());
        editor.apply();

        // Write active connection flags to device_connection SharedPreferences
        SharedPreferences connectionPrefs = context.getSharedPreferences("device_connection", Context.MODE_PRIVATE);
        connectionPrefs.edit()
                .putBoolean("is_connected", true)
                .putBoolean("is_real_connection", true)
                .putString("connection_status", "active")
                .putString("connection_method", "qr_scan")
                .putString(KEY_CONNECTION_ID, connectionId)
                .putLong(KEY_CONNECTION_LINKED_AT, connectionLinkedAt)
                .putLong("connection_time", System.currentTimeMillis())
                .apply();

        Log.d(TAG, "Child session saved: connected to " + parentName);
        Log.d(TAG, "  Previous parent data cleared to prevent role confusion");
        Log.d(TAG, "  Fresh connection prepared - old traces removed");

        // Mirror to Device Protected Storage SharedPreferences for Direct Boot Support
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                directBootPrefs.edit()
                        .putBoolean(KEY_IS_LOGGED_IN, true)
                        .putString(KEY_USER_TYPE, "child")
                        .putString(KEY_CHILD_DEVICE_ID, deviceId)
                        .putString(KEY_PARENT_NAME, parentName)
                        .putString(KEY_QR_SHARE_KEY, shareKey)
                        .putString(KEY_CONNECTION_ID, connectionId)
                        .putLong(KEY_CONNECTION_LINKED_AT, connectionLinkedAt)
                        .apply();
                Log.d(TAG, "Device Protected Storage session saved successfully for Direct Boot");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save Device Protected Storage session: " + e.getMessage());
        }
    }

    // Save QR share key for parent
    public void saveParentQRKey(String qrShareKey) {
        pref.edit().putString(KEY_QR_SHARE_KEY, qrShareKey).apply();
        Log.d(TAG, "Parent QR key saved: " + qrShareKey);
    }

    // NEW: Save parent user ID (for child devices)
    public void saveParentUserId(String parentUserId) {
        pref.edit().putString(KEY_PARENT_USER_ID, parentUserId).apply();
        Log.d(TAG, "Parent user ID saved: " + parentUserId);

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                directBootPrefs.edit().putString(KEY_PARENT_USER_ID, parentUserId).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save parentUserId in Device Protected Storage: " + e.getMessage());
        }
    }

    // NEW: Get parent user ID (for child devices)
    public String getParentUserId() {
        String parentId = pref.getString(KEY_PARENT_USER_ID, null);
        if (parentId == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                parentId = directBootPrefs.getString(KEY_PARENT_USER_ID, null);
            } catch (Exception ignored) {}
        }
        return parentId;
    }

    public String getConnectionId() {
        String connectionId = pref.getString(KEY_CONNECTION_ID, "");
        if (connectionId.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                connectionId = directBootPrefs.getString(KEY_CONNECTION_ID, "");
            } catch (Exception ignored) {}
        }
        return connectionId;
    }

    public long getConnectionLinkedAt() {
        long linkedAt = pref.getLong(KEY_CONNECTION_LINKED_AT, 0L);
        if (linkedAt <= 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                linkedAt = directBootPrefs.getLong(KEY_CONNECTION_LINKED_AT, 0L);
            } catch (Exception ignored) {}
        }
        return linkedAt;
    }

    // Save child name (entered by child before QR scan)
    public void saveChildName(String childName) {
        pref.edit().putString(KEY_CHILD_NAME, childName).apply();
        Log.d(TAG, "Child name saved: " + childName);

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                directBootPrefs.edit().putString(KEY_CHILD_NAME, childName).apply();
                Log.d(TAG, "Child name saved in Device Protected Storage: " + childName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save child name in Device Protected Storage: " + e.getMessage());
        }
    }

    // Get child name
    public String getChildName() {
        String childName = pref.getString(KEY_CHILD_NAME, "");
        if (childName.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                childName = directBootPrefs.getString(KEY_CHILD_NAME, "");
            } catch (Exception ignored) {}
        }
        return childName;
    }

    // Save device model
    public void saveDeviceModel(String deviceModel) {
        pref.edit().putString(KEY_DEVICE_MODEL, deviceModel).apply();
        Log.d(TAG, "Device model saved: " + deviceModel);
    }

    // Get device model
    public String getDeviceModel() {
        return pref.getString(KEY_DEVICE_MODEL, "");
    }

    private boolean isUserUnlocked() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.os.UserManager userManager = (android.os.UserManager) context.getSystemService(Context.USER_SERVICE);
            return userManager != null && userManager.isUserUnlocked();
        }
        return true;
    }

    private boolean isRemovedChildReconnectionRequired() {
        try {
            SharedPreferences disconnectionPrefs = context.getSharedPreferences("disconnection_state", Context.MODE_PRIVATE);
            boolean removed = disconnectionPrefs.getBoolean("device_was_removed", false);
            boolean requiresQr = disconnectionPrefs.getBoolean("require_qr_reconnection", false);
            boolean hasChildSession = "child".equals(pref.getString(KEY_USER_TYPE, ""))
                    || pref.contains(KEY_CHILD_DEVICE_ID);
            return removed && requiresQr && hasChildSession;
        } catch (Exception e) {
            Log.e(TAG, "Failed to check removed child reconnection state: " + e.getMessage());
            return false;
        }
    }
    // Check if user is logged in
    public boolean isLoggedIn() {
        if (isRemovedChildReconnectionRequired()) {
            Log.d(TAG, "Removed child reconnection is required; treating local child session as logged out");
            return false;
        }

        boolean loggedIn = pref.getBoolean(KEY_IS_LOGGED_IN, false);

        // If not found (e.g. pre-unlock direct boot), check Device Protected Storage
        if (!loggedIn && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                loggedIn = directBootPrefs.getBoolean(KEY_IS_LOGGED_IN, false);
            } catch (Exception ignored) {}
        }

        // Child relationships are governed by v2 ownership and its 90-day stale-owner policy.
        // Keep the existing local expiry only for parent sessions.
        if (isUserUnlocked() && !"child".equals(getUserType())) {
            // Check if session is still valid (not older than 30 days)
            long lastLoginTime = pref.getLong(KEY_LAST_LOGIN_TIME, 0);
            long currentTime = System.currentTimeMillis();
            long thirtyDaysInMillis = 30 * 24 * 60 * 60 * 1000L;

            if (loggedIn && (currentTime - lastLoginTime) > thirtyDaysInMillis) {
                Log.d(TAG, "Session expired, logging out");
                logoutUser();
                return false;
            }
        }

        return loggedIn;
    }

    // Get user type (parent or child)
    public String getUserType() {
        String userType = pref.getString(KEY_USER_TYPE, "");
        if (userType.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                userType = directBootPrefs.getString(KEY_USER_TYPE, "");
            } catch (Exception ignored) {}
        }
        return userType;
    }

    // Get phone number (for parent)
    public String getPhoneNumber() {
        return pref.getString(KEY_PHONE_NUMBER, "");
    }

    // Get user ID (for parent)
    public String getUserId() {
        return pref.getString(KEY_USER_ID, "");
    }

    // Get device name
    public String getDeviceName() {
        return pref.getString(KEY_DEVICE_NAME, "");
    }

    // Get parent profile name (Parent's actual name)
    public String getParentProfileName() {
        return pref.getString(KEY_PARENT_PROFILE_NAME, "");
    }

    // Get QR share key
    public String getQRShareKey() {
        String qrKey = pref.getString(KEY_QR_SHARE_KEY, "");
        if (qrKey.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                qrKey = directBootPrefs.getString(KEY_QR_SHARE_KEY, "");
            } catch (Exception ignored) {}
        }
        return qrKey;
    }

    // Get parent name (for child)
    public String getParentName() {
        String parentName = pref.getString(KEY_PARENT_NAME, "");
        if (parentName.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                parentName = directBootPrefs.getString(KEY_PARENT_NAME, "");
            } catch (Exception ignored) {}
        }
        return parentName;
    }

    // Get child device ID
    public String getChildDeviceId() {
        String deviceId = pref.getString(KEY_CHILD_DEVICE_ID, "");
        if (deviceId.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                deviceId = directBootPrefs.getString(KEY_CHILD_DEVICE_ID, "");
            } catch (Exception ignored) {}
        }
        return deviceId;
    }

    // Check if connection is active (for child)
    public boolean isConnectionActive() {
        return pref.getBoolean(KEY_CONNECTION_ACTIVE, false);
    }

    // Update connection status
    public void setConnectionActive(boolean active) {
        pref.edit().putBoolean(KEY_CONNECTION_ACTIVE, active).apply();
        Log.d(TAG, "Connection status updated: " + active);
    }

    // Update last activity time (to keep session alive)
    public void updateLastActivity() {
        pref.edit().putLong(KEY_LAST_LOGIN_TIME, System.currentTimeMillis()).apply();
    }

    // Logout user and clear all session data
    public void logoutUser() {
        String currentUserType = getUserType();
        String savedChildName = getChildName();
        Log.d(TAG, "Logging out user type: " + currentUserType + ", preserving child name: " + savedChildName);

        pref.edit().clear().commit();

        if (savedChildName != null && !savedChildName.isEmpty()) {
            pref.edit().putString(KEY_CHILD_NAME, savedChildName).commit();
            Log.d(TAG, "Restored preserved child name: " + savedChildName);
        }

        // Clear Device Protected Storage SharedPreferences as well
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Context deviceContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directBootPrefs = deviceContext.getSharedPreferences("direct_boot_prefs", Context.MODE_PRIVATE);
                directBootPrefs.edit().clear().commit();
                
                // Mirror the child name restoration in Device Protected Storage
                if (savedChildName != null && !savedChildName.isEmpty()) {
                    directBootPrefs.edit().putString(KEY_CHILD_NAME, savedChildName).commit();
                    Log.d(TAG, "Restored preserved child name to Device Protected Storage: " + savedChildName);
                }
                Log.d(TAG, "Device Protected Storage session cleared");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear Device Protected Storage session: " + e.getMessage());
        }

        // Also clear any related SharedPreferences that might persist state
        clearRelatedPreferences(currentUserType);

        Log.d(TAG, "User logged out, session cleared completely");
    }

    /**
     * SMART PREFERENCE CLEARING
     * Clear only session-related data, preserve app settings and established
     * connections
     */
    private void clearRelatedPreferences(String currentUserType) {
        try {
            Log.d(TAG, "  🤖 Smart clearing for user type: " + currentUserType);

            // Always clear these session-specific preferences
            String[] sessionPrefs = {
                    "qr_scanning_state", // QR scanning state
                    "logout_state" // Logout tracking
            };

            // Clear dashboard caches based on role
            String[] roleCachePrefs;
            if ("parent".equals(currentUserType)) {
                roleCachePrefs = new String[] { "parent_dashboard_cache" };
            } else if ("child".equals(currentUserType)) {
                roleCachePrefs = new String[] { "child_dashboard_cache", "device_connection", "child_session",
                        "emergency_session_backup", "connection_state", "parent_connection", "device_info",
                        "auth_cache", "app_state" };
            } else {
                roleCachePrefs = new String[] {}; // Unknown role, don't clear anything extra
            }

            // Clear session preferences
            for (String prefName : sessionPrefs) {
                clearPreference(prefName);
            }

            // Clear role-specific caches
            for (String prefName : roleCachePrefs) {
                clearPreference(prefName);
            }

            // NOTE: We preserve:
            // - timer_state (timers should persist)
            // - app_usage_limits (limits should persist)
            // - blocked_apps (blocked apps should persist)
            // - connection data for established connections

            Log.d(TAG, "  ✅ Smart preference clearing completed");

        } catch (Exception e) {
            Log.e(TAG, "Error during smart preference clearing: " + e.getMessage());
        }
    }

    /**
     * Clear a single preference with logging
     */
    private void clearPreference(String prefName) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
            int itemCount = prefs.getAll().size();
            if (itemCount > 0) {
                prefs.edit().clear().commit();
                Log.d(TAG, "    ✅ Cleared: " + prefName + " (" + itemCount + " items)");
            } else {
                Log.d(TAG, "    ⏭️ Already empty: " + prefName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing preference " + prefName + ": " + e.getMessage());
        }
    }

    // Get session info for debugging
    public String getSessionInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Session Info:\n");
        info.append("- Logged in: ").append(isLoggedIn()).append("\n");
        info.append("- User type: ").append(getUserType()).append("\n");
        info.append("- Phone: ").append(getPhoneNumber()).append("\n");
        info.append("- Device: ").append(getDeviceName()).append("\n");
        info.append("- QR Key: ").append(getQRShareKey()).append("\n");
        info.append("- Parent: ").append(getParentName()).append("\n");
        info.append("- Parent User ID: ").append(getParentUserId()).append("\n");
        info.append("- Connection ID: ").append(getConnectionId()).append("\n");
        info.append("- Connection: ").append(isConnectionActive()).append("\n");

        long lastLogin = pref.getLong(KEY_LAST_LOGIN_TIME, 0);
        if (lastLogin > 0) {
            info.append("- Last login: ").append(new java.util.Date(lastLogin)).append("\n");
        }

        return info.toString();
    }

    // NEW: Add this method for detailed debugging
    public String getDetailedSessionInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== DETAILED SESSION INFO ===\n");
        info.append("- Logged in: ").append(isLoggedIn()).append("\n");
        info.append("- User type: ").append(getUserType()).append("\n");
        info.append("- Phone: ").append(getPhoneNumber()).append("\n");
        info.append("- User ID: ").append(getUserId()).append("\n");
        info.append("- Device: ").append(getDeviceName()).append("\n");
        info.append("- QR Key: ").append(getQRShareKey()).append("\n");
        info.append("- Parent: ").append(getParentName()).append("\n");
        info.append("- Parent User ID: ").append(getParentUserId()).append("\n");
        info.append("- Child Device ID: ").append(getChildDeviceId()).append("\n");
        info.append("- Connection ID: ").append(getConnectionId()).append("\n");
        info.append("- Connection Linked At: ").append(getConnectionLinkedAt()).append("\n");
        info.append("- Connection: ").append(isConnectionActive()).append("\n");

        long lastLogin = pref.getLong(KEY_LAST_LOGIN_TIME, 0);
        if (lastLogin > 0) {
            info.append("- Last login: ").append(new java.util.Date(lastLogin)).append("\n");
        }

        info.append("=== END SESSION INFO ===");
        return info.toString();
    }

    /**
     * Clear only child-specific session data (used when switching to parent role)
     */
    private void clearChildSessionData() {
        try {
            pref.edit()
                .remove(KEY_CHILD_DEVICE_ID)
                .remove(KEY_PARENT_NAME)
                .remove(KEY_PARENT_USER_ID)
                .remove(KEY_CONNECTION_ID)
                .remove(KEY_CONNECTION_LINKED_AT)
                .remove(KEY_CONNECTION_ACTIVE)
                .apply();

            Log.d(TAG, "  🧹 Child-specific session data cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing child session data: " + e.getMessage());
        }
    }

    /**
     * Clear only parent-specific session data (used when switching to child role)
     */
    private void clearParentSessionData() {
        try {
            pref.edit()
                .remove(KEY_PHONE_NUMBER)
                .remove(KEY_USER_ID)
                .remove(KEY_DEVICE_NAME)
                .apply();

            Log.d(TAG, "  🧹 Parent-specific session data cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing parent session data: " + e.getMessage());
        }
    }

    /**
     * Get current session role for debugging
     */
    public String getCurrentRole() {
        String userType = getUserType();
        if ("parent".equals(userType)) {
            return "PARENT (Phone: " + getPhoneNumber() + ", Device: " + getDeviceName() + ")";
        } else if ("child".equals(userType)) {
            return "CHILD (Parent: " + getParentName() + ", DeviceID: " + getChildDeviceId() + ")";
        } else {
            return "UNKNOWN (" + userType + ")";
        }
    }

    /**
     * Validate that current session has all required data for its claimed role
     */
    public boolean isSessionDataComplete() {
        try {
            String userType = getUserType();

            if ("parent".equals(userType)) {
                return isParentSessionComplete();
            } else if ("child".equals(userType)) {
                return isChildSessionComplete();
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error validating session data completeness: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🔧 FIX 1: Validate ONLY parent-specific fields for parent sessions
     * Don't check child fields (childDeviceId, shareKey) for parents!
     */
    public boolean isParentSessionComplete() {
        try {
            String userId = getUserId();
            String phoneNumber = getPhoneNumber();
            String deviceName = getDeviceName();

            boolean isComplete = userId != null && !userId.isEmpty() &&
                    phoneNumber != null && !phoneNumber.isEmpty() &&
                    deviceName != null && !deviceName.isEmpty();

            if (!isComplete) {
                Log.d(TAG, "Parent session incomplete:");
                Log.d(TAG, "  userId: " + (userId != null && !userId.isEmpty() ? "✓" : "✗"));
                Log.d(TAG, "  phoneNumber: " + (phoneNumber != null && !phoneNumber.isEmpty() ? "✓" : "✗"));
                Log.d(TAG, "  deviceName: " + (deviceName != null && !deviceName.isEmpty() ? "✓" : "✗"));
            }

            return isComplete;
        } catch (Exception e) {
            Log.e(TAG, "Error validating parent session: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🔧 FIX 1: Validate ONLY child-specific fields for child sessions
     * Don't check parent fields (userId, phoneNumber) for children!
     */
    public boolean isChildSessionComplete() {
        try {
            String parentName = getParentName();
            String childDeviceId = getChildDeviceId();
            String shareKey = getQRShareKey();

            boolean isComplete = parentName != null && !parentName.isEmpty() &&
                    childDeviceId != null && !childDeviceId.isEmpty() &&
                    shareKey != null && !shareKey.isEmpty();

            if (!isComplete) {
                Log.d(TAG, "Child session incomplete:");
                Log.d(TAG, "  parentName: " + (parentName != null && !parentName.isEmpty() ? "✓" : "✗"));
                Log.d(TAG, "  childDeviceId: " + (childDeviceId != null && !childDeviceId.isEmpty() ? "✓" : "✗"));
                Log.d(TAG, "  shareKey: " + (shareKey != null && !shareKey.isEmpty() ? "✓" : "✗"));
            }

            return isComplete;
        } catch (Exception e) {
            Log.e(TAG, "Error validating child session: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🛡️ Prepare for fresh child connection by clearing old traces
     */
    private void prepareFreshChildConnection() {
        try {
            Log.d(TAG, "🛡️ PREPARING FRESH CHILD CONNECTION - Clearing old traces");

            // Clear disconnection state that might block connection
            SharedPreferences disconnectionPrefs = context.getSharedPreferences("disconnection_state",
                    Context.MODE_PRIVATE);
            disconnectionPrefs.edit().clear().apply();

            // Clear old device connection state
            SharedPreferences deviceConnectionPrefs = context.getSharedPreferences("device_connection",
                    Context.MODE_PRIVATE);
            deviceConnectionPrefs.edit().clear().apply();

            // Clear any child-specific preferences
            String[] childPrefs = { "child_prefs", "child_session", "logout_state", "auth_cache" };
            for (String prefName : childPrefs) {
                SharedPreferences prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                prefs.edit().clear().commit();
            }

            // Set fresh start marker
            SharedPreferences appStatePrefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE);
            appStatePrefs.edit()
                    .putBoolean("fresh_connection_prepared", true)
                    .putLong("fresh_preparation_time", System.currentTimeMillis())
                    .apply();

            Log.d(TAG, "✅ Fresh child connection prepared - old traces cleared");

        } catch (Exception e) {
            Log.e(TAG, "Error preparing fresh child connection: " + e.getMessage());
        }
    }
}
