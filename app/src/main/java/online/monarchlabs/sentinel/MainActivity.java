package online.monarchlabs.sentinel;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import online.monarchlabs.sentinel.databinding.ActivityMainBinding;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

public class MainActivity extends BaseActivity {
    private static final String TAG = "MainActivity";
    private ActivityMainBinding binding;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);

        // 🔥 BULLETPROOF REINSTALL DETECTION - Multiple layers of protection
        boolean isTrueFreshInstall = isFreshInstall();
        boolean hasUnexpectedData = detectUnexpectedDataRestore();

        if (isTrueFreshInstall || hasUnexpectedData) {
            if (isTrueFreshInstall) {
                Log.d(TAG, "🔥 TRUE FRESH INSTALL DETECTED - Complete data cleanup");
            }
            if (hasUnexpectedData) {
                Log.d(TAG, "🔥 UNEXPECTED DATA RESTORE DETECTED - Clearing backup data");
            }
            performCompleteReinstallCleanup();
        } else {
            Log.d(TAG, "✅ NORMAL APP LAUNCH - Preserving existing connections and data");
        }

        // CRITICAL: Check for valid sessions FIRST before any other logic
        sessionManager = new SessionManager(this);

        // 🔧 ROLE STABILITY FIX: Add additional validation for user type consistency
        if (sessionManager != null && sessionManager.isLoggedIn()) {
            String userType = sessionManager.getUserType();

            // Validate that the user type has complete and consistent data
            boolean isValidSession = validateSessionConsistency(userType);

            if (!isValidSession) {
                Log.w(TAG, "⚠️ INCONSISTENT SESSION DETECTED - User type: " + userType + " has incomplete data");
                Log.w(TAG, "   Clearing session to prevent role confusion");
                sessionManager.logoutUser();
                performCompleteReinstallCleanup();
            } else if ("parent".equals(userType)) {
                Log.d(TAG, "🚀 IMMEDIATE PARENT REDIRECT - Logged-in parent detected, skipping intro screen");
                sessionManager.updateLastActivity();

                try {
                    Intent intent = new Intent(this, LoadingActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                    return; // Exit onCreate immediately
                } catch (Exception e) {
                    Log.e(TAG, "Error redirecting to LoadingActivity: " + e.getMessage());
                    // Fall through to normal flow if redirect fails
                }
            }
        }

        // Check for logout message from background service
        String logoutMessage = getIntent().getStringExtra("logout_message");
        if (logoutMessage != null) {
            Log.d(TAG, "📱 Received logout message: " + logoutMessage);
            // Show logout message to user
            showLogoutMessage(logoutMessage);
        }

        String childDeviceId = sessionManager.getChildDeviceId();
        String parentName = sessionManager.getParentName();
        String shareKey = sessionManager.getQRShareKey(); // Fixed method name

        Log.d(TAG, "🔍 IMMEDIATE child session check - childDeviceId: " + childDeviceId +
                ", parentName: " + parentName + ", shareKey: " + shareKey);

        // Check for removal/disconnection flags first.
        if (isBulletproofDisconnectionActive()) {
            Log.d(TAG, "Persistent child removal state found; completing local cleanup");
            ChildDisconnectionCoordinator.disconnectCurrentSession(
                    this, "main_activity_removal_recovery");
            return;
        }

        // Check for explicit logout flags.
        boolean requiresQRReconnection = getIntent().getBooleanExtra("require_qr_reconnection", false);
        boolean forceLoginScreen = getIntent().getBooleanExtra("force_login_screen", false);
        boolean autoLogoutCompleted = getIntent().getBooleanExtra("auto_logout_completed", false);
        boolean bulletproofCleanupCompleted = getIntent().getBooleanExtra("bulletproof_cleanup_completed", false);
        boolean deviceWasRemoved = getIntent().getBooleanExtra("device_was_removed", false);

        Log.d(TAG, "🔍 Logout flags - requiresQRReconnection: " + requiresQRReconnection +
                ", forceLoginScreen: " + forceLoginScreen + ", autoLogoutCompleted: " + autoLogoutCompleted +
                ", bulletproofCleanupCompleted: " + bulletproofCleanupCompleted + ", deviceWasRemoved: "
                + deviceWasRemoved);

        // 🔧 BULLETPROOF FIX: Force logout if any disconnection flag is set
        if (forceLoginScreen || autoLogoutCompleted || bulletproofCleanupCompleted || deviceWasRemoved) {
            Log.d(TAG, "🚪 BULLETPROOF LOGOUT DETECTED - Clearing session and staying on login screen");
            sessionManager.logoutUser();
            clearAnyRemainingSessionData();

            // 🔧 LOGOUT FIX: Clear intentional logout flag after successful logout
            SharedPreferences logoutPrefs = getSharedPreferences("logout_state", MODE_PRIVATE);
            logoutPrefs.edit().clear().apply();
            Log.d(TAG, "✅ Intentional logout flag cleared - logout completed");

            // Clear the intent flags to prevent repeated logout
            clearLogoutIntentFlags();

            // Force clean login screen
            displayLoginScreen();
            return;
        }

        if (childDeviceId != null && !childDeviceId.isEmpty() &&
                parentName != null && !parentName.isEmpty() &&
                !requiresQRReconnection && !forceLoginScreen && !autoLogoutCompleted &&
                !bulletproofCleanupCompleted && !deviceWasRemoved) {

            // 🛡️ BULLETPROOF VALIDATION: Strict connection validation
            if (!isValidActiveConnection(childDeviceId, parentName)) {
                Log.d(TAG, "🛡️ BULLETPROOF PROTECTION: Connection validation failed - blocking dashboard access");
                clearAnyRemainingSessionData();
                displayLoginScreen();
                return;
            }

            Log.d(TAG, "🚀 IMMEDIATE CHILD REDIRECT - Valid session found, going to loading screen");

            // 🎨 LOADING SCREEN: Show loading screen before dashboard (like parent app)
            Intent intent = new Intent(this, LoadingActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish(); // Close MainActivity
            return; // Exit onCreate immediately

        } else {
            Log.d(TAG, "⚠️ Child session exists but connection validation failed - BULLETPROOF MODE: Blocking access");
            clearAnyRemainingSessionData();
        }

        // Check for explicit QR reconnection requirement
        if (requiresQRReconnection) {
            Log.d(TAG, "⚠️ Child session requires QR reconnection - showing message to user");
            Toast.makeText(this, "Device was disconnected. Please scan QR code to reconnect.", Toast.LENGTH_LONG)
                    .show();
        }

        Log.d(TAG, "📱 No valid child session found, proceeding with normal MainActivity flow");

        // Start DailyTimerResetService to ensure automatic midnight resets
        startDailyTimerResetService();

        // Check if redirected due to disconnect
        handleDisconnectMessage();

        // Check if user is already logged in (for parent or invalid sessions)
        checkExistingSession();

        // Setup MainActivity UI if we reach this point
        // Result of all checks: No session found, show Welcome Screen
        if (binding == null) {
            binding = ActivityMainBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());
        }

        // Setup click listeners
        binding.btnGetStarted.setOnClickListener(v -> {
            startActivity(new Intent(MainActivity.this, RoleSelectionActivity.class));
        });
    }

    /**
     * Start the DailyTimerResetService to ensure automatic midnight resets
     * for both timers and daily usage limits work even when app is closed
     */
    private void startDailyTimerResetService() {
        try {
            Log.d(TAG, "🕛 Starting DailyTimerResetService for automatic midnight resets");
            Intent serviceIntent = new Intent(this, DailyTimerResetService.class);
            startService(serviceIntent);
            Log.d(TAG, "✅ DailyTimerResetService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start DailyTimerResetService: " + e.getMessage());
        }
    }

    private void handleDisconnectMessage() {
        try {
            // Check if this activity was started due to disconnect
            boolean disconnectedByParent = getIntent().getBooleanExtra("disconnected_by_parent", false);
            String disconnectMessage = getIntent().getStringExtra("disconnect_message");

            if (disconnectedByParent) {
                Log.d(TAG, "User redirected to login due to disconnect by parent");

                // Show disconnect notification
                if (disconnectMessage != null && !disconnectMessage.isEmpty()) {
                    showDisconnectDialog(disconnectMessage);
                } else {
                    showDisconnectDialog("Device was disconnected by parent. Please scan QR code to reconnect.");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling disconnect message: " + e.getMessage());
        }
    }

    /**
     * 🔧 PRODUCTION FIX: Check existing session with proper validation
     * Implements all 5 fixes for the auto-logout bug
     */
    private void checkExistingSession() {
        Log.d(TAG, "🔍 Checking existing session...");
        Log.d(TAG, sessionManager.getSessionInfo());

        if (sessionManager.isLoggedIn()) {
            String userType = sessionManager.getUserType();
            Log.d(TAG, "✅ User already logged in as: " + userType);

            // 🔧 FIX 2: Check Firebase Auth BEFORE validating local session
            // If Firebase user exists, don't logout even if local data is incomplete
            com.google.firebase.auth.FirebaseUser firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance()
                    .getCurrentUser();
            boolean hasFirebaseAuth = firebaseUser != null;
            Log.d(TAG, "🔐 Firebase Auth status: " + (hasFirebaseAuth ? "AUTHENTICATED" : "NOT AUTHENTICATED"));

            // 🔧 FIX 1 & FIX 5: Validate session with improved logic and grace period
            ValidationResult validationResult = validateSessionWithGrace(userType, hasFirebaseAuth);

            if (!validationResult.isValid) {
                if (validationResult.shouldLogout) {
                    Log.w(TAG, "⚠️ INVALID SESSION - " + validationResult.reason);
                    sessionManager.logoutUser();
                    performCompleteReinstallCleanup();
                    Toast.makeText(this, validationResult.userMessage, Toast.LENGTH_LONG).show();
                    return;
                } else {
                    // Session is incomplete but recoverable (e.g., parent with Firebase auth)
                    Log.w(TAG, "⚠️ SESSION INCOMPLETE but recoverable - " + validationResult.reason);
                    if ("parent".equals(userType) && hasFirebaseAuth) {
                        // Allow parent to proceed with partial session
                        // They can still access the app, just show a reconnect child UI later
                        Log.d(TAG, "✅ Allowing parent to proceed with Firebase auth");
                    }
                }
            }

            // Update last activity time
            sessionManager.updateLastActivity();

            // Auto-login based on user type
            if ("parent".equals(userType)) {
                Log.d(TAG, "📱 Parent session - proceeding to dashboard");
                autoLoginParent();
            } else if ("child".equals(userType)) {
                // 🔧 FIX 3: For child, try to rehydrate from server if local data is incomplete
                if (!validationResult.isValid && !validationResult.shouldLogout) {
                    attemptSessionRehydrationFromServer(userType);
                }
                Log.d(TAG, "📱 Child session - proceeding to dashboard");
                autoLoginChild();
            } else {
                Log.w(TAG, "❌ Unknown user type: " + userType);
                sessionManager.logoutUser();
                performCompleteReinstallCleanup();
                Toast.makeText(this, "Session type invalid - please login again", Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.d(TAG, "ℹ️ No existing session found");
        }
    }

    /**
     * 🔧 FIX 5: Validate session with 5-second grace period for remote sync
     * Attempts to rehydrate from server before declaring session invalid
     */
    private ValidationResult validateSessionWithGrace(String userType, boolean hasFirebaseAuth) {
        // First attempt: immediate validation
        ValidationResult immediately = validateSessionImmediate(userType, hasFirebaseAuth);

        if (immediately.isValid) {
            return immediately; // Session is valid, no need to wait
        }

        // If not valid immediately, wait for potential remote sync
        Log.d(TAG, "⏳ Session validation failed, waiting 5s for remote sync...");

        try {
            Thread.sleep(5000); // 5-second grace period
        } catch (InterruptedException e) {
            Log.e(TAG, "Grace period interrupted: " + e.getMessage());
        }

        // Second attempt: re-validate after grace period
        ValidationResult afterGrace = validateSessionImmediate(userType, hasFirebaseAuth);

        if (afterGrace.isValid) {
            Log.d(TAG, "✅ Session recovered after grace period!");
        } else {
            Log.d(TAG, "❌ Session still invalid after grace period");
        }

        return afterGrace;
    }

    /**
     * 🔧 FIX 1: Immediate session validation with separate parent/child logic
     */
    private ValidationResult validateSessionImmediate(String userType, boolean hasFirebaseAuth) {
        ValidationResult result = new ValidationResult();

        if (userType == null || userType.isEmpty()) {
            result.isValid = false;
            result.shouldLogout = true;
            result.reason = "userType is null or empty";
            result.userMessage = "Session was corrupted - please login again";
            return result;
        }

        if ("parent".equals(userType)) {
            // 🔧 FIX 1 & FIX 2: Validate ONLY parent-specific fields
            boolean localSessionComplete = sessionManager.isParentSessionComplete();

            if (!localSessionComplete && hasFirebaseAuth) {
                // Parent has incomplete local session but IS authenticated with Firebase
                // Don't logout! Just log a warning
                Log.w(TAG, "⚠️ Parent has incomplete local data but Firebase auth exists");
                result.isValid = false;
                result.shouldLogout = false; // DON'T logout!
                result.reason = "Local session incomplete but Firebase auth present";
                result.userMessage = "";
                return result;
            } else if (!localSessionComplete && !hasFirebaseAuth) {
                // Parent has no local session AND no Firebase auth - true corruption
                result.isValid = false;
                result.shouldLogout = true;
                result.reason = "Parent session incomplete with no Firebase auth";
                result.userMessage = "Session was corrupted - please login again";
                logMissingParentFields();
                return result;
            }

            // Parent session is complete
            result.isValid = true;
            result.shouldLogout = false;
            return result;

        } else if ("child".equals(userType)) {
            // 🔧 FIX 1: Validate ONLY child-specific fields
            boolean localSessionComplete = sessionManager.isChildSessionComplete();

            if (!localSessionComplete) {
                result.isValid = false;
                result.shouldLogout = true; // Children must have complete local data
                result.reason = "Child session incomplete";
                result.userMessage = "Connection lost - please scan QR code again";
                logMissingChildFields();
                return result;
            }

            // Child session is complete
            result.isValid = true;
            result.shouldLogout = false;
            return result;

        } else {
            result.isValid = false;
            result.shouldLogout = true;
            result.reason = "Unknown user type: " + userType;
            result.userMessage = "Session type invalid - please login again";
            return result;
        }
    }

    /**
     * 🔧 FIX 3: Attempt to rehydrate session from Firebase server
     * Only called for incomplete sessions before logout decision
     */
    private void attemptSessionRehydrationFromServer(String userType) {
        Log.d(TAG, "🔄 Attempting session rehydration from server for: " + userType);

        if ("child".equals(userType)) {
            // Try to get childDeviceId from system if it's missing
            String childDeviceId = sessionManager.getChildDeviceId();

            if (childDeviceId == null || childDeviceId.isEmpty()) {
                // Try to reconstruct from device info
                childDeviceId = android.provider.Settings.Secure.getString(
                        getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID);

                if (childDeviceId != null && !childDeviceId.isEmpty()) {
                    Log.d(TAG, "✅ Rehydrated childDeviceId from system: " + childDeviceId);
                    // Save it back to session
                    SharedPreferences prefs = getSharedPreferences("MasterAppSession", MODE_PRIVATE);
                    prefs.edit().putString("childDeviceId", childDeviceId).apply();
                }
            }
        }
        // Parent rehydration would query Firebase here if needed
    }

    /**
     * Helper: Log which parent fields are missing
     */
    private void logMissingParentFields() {
        String userId = sessionManager.getUserId();
        String phoneNumber = sessionManager.getPhoneNumber();
        String deviceName = sessionManager.getDeviceName();

        Log.w(TAG, "❌ Missing parent fields:");
        if (userId == null || userId.isEmpty())
            Log.w(TAG, "  - userId");
        if (phoneNumber == null || phoneNumber.isEmpty())
            Log.w(TAG, "  - phoneNumber");
        if (deviceName == null || deviceName.isEmpty())
            Log.w(TAG, "  - deviceName");
    }

    /**
     * Helper: Log which child fields are missing
     */
    private void logMissingChildFields() {
        String parentName = sessionManager.getParentName();
        String childDeviceId = sessionManager.getChildDeviceId();
        String shareKey = sessionManager.getQRShareKey();

        Log.w(TAG, "❌ Missing child fields:");
        if (parentName == null || parentName.isEmpty())
            Log.w(TAG, "  - parentName");
        if (childDeviceId == null || childDeviceId.isEmpty())
            Log.w(TAG, "  - childDeviceId");
        if (shareKey == null || shareKey.isEmpty())
            Log.w(TAG, "  - shareKey");
    }

    /**
     * Inner class for validation results
     */
    private static class ValidationResult {
        boolean isValid = false;
        boolean shouldLogout = false;
        String reason = "";
        String userMessage = "";
    }

    private void autoLoginParent() {
        Log.d(TAG, "Auto-logging in parent user - redirecting to loading screen");

        // Immediately redirect to loading screen, no delay
        Intent intent = new Intent(MainActivity.this, LoadingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void autoLoginChild() {
        Log.d(TAG, "Auto-logging in child user - proceeding directly to dashboard");

        // Get session data
        String parentName = sessionManager.getParentName();
        String childDeviceId = sessionManager.getChildDeviceId();
        String parentUserId = sessionManager.getParentUserId();
        String shareKey = sessionManager.getQRShareKey();

        // Validate essential data
        if (parentName == null || childDeviceId == null) {
            Log.w(TAG, "⚠️ Critical session data missing - BULLETPROOF MODE: attempting recovery instead of logout");
            Log.w(TAG, "   Parent Name: " + parentName);
            Log.w(TAG, "   Child Device ID: " + childDeviceId);
            // 🛡️ BULLETPROOF: Don't logout, try to navigate to ChildDashboard for recovery
            // sessionManager.logoutUser(); // DISABLED to prevent logout loops

            Toast.makeText(this, "Attempting session recovery - please wait...", Toast.LENGTH_SHORT).show();

            // 🎨 LOADING SCREEN: Try to navigate to LoadingActivity for emergency recovery
            try {
                Intent intent = new Intent(this, LoadingActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            } catch (Exception e) {
                Log.e(TAG, "Failed to navigate to ChildDashboard for recovery: " + e.getMessage());
                Toast.makeText(this, "Recovery failed - please scan QR code", Toast.LENGTH_LONG).show();
                return;
            }
        }

        Log.d(TAG, "✅ Child session data valid - proceeding to dashboard");
        Log.d(TAG, "   Parent: " + parentName);
        Log.d(TAG, "   Device: " + childDeviceId);

        // Skip Firebase validation and proceed directly to dashboard
        // The connection persistence is handled by PersistentConnectionService
        proceedWithChildAutoLogin();
    }

    private void proceedWithChildAutoLogin() {
        Log.d(TAG, "🚀 Launching child dashboard with session data");

        // Get all session data
        String parentName = sessionManager.getParentName();
        String shareKey = sessionManager.getQRShareKey();
        String childDeviceId = sessionManager.getChildDeviceId();

        // 🎨 LOADING SCREEN: Show loading screen for smooth transition
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent intent = new Intent(MainActivity.this, LoadingActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                Log.d(TAG, "✅ Starting LoadingActivity for child user with session:");
                Log.d(TAG, "   parentName: " + parentName);
                Log.d(TAG, "   deviceId: " + childDeviceId);
                Log.d(TAG, "   shareKey: "
                        + (shareKey != null ? shareKey.substring(0, Math.min(6, shareKey.length())) + "..." : "null"));

                startActivity(intent);
                finish();

            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to launch child dashboard: " + e.getMessage());
                Toast.makeText(this, "Error launching dashboard - please try again", Toast.LENGTH_SHORT).show();
            }
        }, 800); // Reduced delay for faster startup
    }

    /**
     * Show logout message to user when child device is disconnected by parent
     */
    /**
     * Show logout message to user when child device is disconnected by parent
     */
    private void showLogoutMessage(String message) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
            builder.setTitle("🚨 Device Disconnected");

            // Use custom TextView to ensure text is visible (Force BLACK color)
            android.widget.TextView messageView = new android.widget.TextView(this);
            messageView.setText(message + "\n\nYou will need to scan a new QR code to reconnect.");
            messageView.setTextColor(android.graphics.Color.BLACK);
            messageView.setTextSize(16);
            messageView.setPadding(60, 40, 60, 20); // Add padding for better look

            builder.setView(messageView);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setCancelable(false);
            builder.setPositiveButton("OK", (dialog, which) -> {
                dialog.dismiss();
            });

            AlertDialog dialog = builder.create();
            dialog.show();

            Log.d(TAG, "📱 Logout message dialog shown to user");
        } catch (Exception e) {
            Log.e(TAG, "Error showing logout message: " + e.getMessage());
            // Fallback to toast
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void showDisconnectDialog(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("⚠️ Device Disconnected");

        // Use custom TextView to ensure text is visible (Force BLACK color)
        android.widget.TextView messageView = new android.widget.TextView(this);
        messageView.setText(message);
        messageView.setTextColor(android.graphics.Color.BLACK);
        messageView.setTextSize(16);
        messageView.setPadding(60, 40, 60, 20);

        builder.setView(messageView);

        builder.setPositiveButton("OK", (dialog, which) -> {
            // User acknowledged the disconnect
            Toast.makeText(this, "Tap 'Get Started' to reconnect", Toast.LENGTH_LONG).show();
        })
                .setCancelable(false)
                .show();
    }

    /**
     * BULLETPROOF FRESH INSTALL DETECTION
     * Detects actual app uninstall/reinstall vs normal app usage
     * 🔧 CRITICAL FIX: NEVER clear sessions on Android Studio rebuilds!
     * Only clears data on FIRST EVER RUN (never installed before)
     */
    private boolean isFreshInstall() {
        try {
            SharedPreferences appInstallPrefs = getSharedPreferences("app_install_detection", MODE_PRIVATE);

            // Get app installation info
            long appInstallTime = getAppInstallTime();
            long lastKnownInstallTime = appInstallPrefs.getLong("install_time", 0);
            boolean hasRunBefore = appInstallPrefs.getBoolean("has_run_before", false);

            Log.d(TAG, "🔍 INSTALL DETECTION:");
            Log.d(TAG, "   App install time: " + appInstallTime);
            Log.d(TAG, "   Last known install time: " + lastKnownInstallTime);
            Log.d(TAG, "   Has run before: " + hasRunBefore);

            // SCENARIO 1: True first run (never ran before) - ONLY time we clear
            if (!hasRunBefore && lastKnownInstallTime == 0) {
                Log.d(TAG, "🔥 SCENARIO 1: TRUE FIRST INSTALL - First ever run");
                appInstallPrefs.edit()
                        .putBoolean("has_run_before", true)
                        .putLong("install_time", appInstallTime)
                        .putLong("first_run_time", System.currentTimeMillis())
                        .apply();
                return true;
            }

            // 🔧 CRITICAL FIX: SCENARIO 2 REMOVED - Android Studio rebuilds should NOT
            // clear data!
            // Previously this would detect install time changes and clear sessions
            // This was causing logout on every rebuild from Android Studio
            // NOW: We ONLY clear on true first install (Scenario 1)

            // Update install time silently without clearing data
            if (hasRunBefore && Math.abs(appInstallTime - lastKnownInstallTime) > 60000) {
                Log.d(TAG,
                        "ℹ️ Install time changed (Android Studio rebuild?) - Updating timestamp but PRESERVING sessions");
                Log.d(TAG, "   Time difference: " + Math.abs(appInstallTime - lastKnownInstallTime) + "ms");
                appInstallPrefs.edit()
                        .putLong("install_time", appInstallTime)
                        .putLong("last_update_time", System.currentTimeMillis())
                        .apply();
            }

            // SCENARIO 2: Normal app usage - preserve all data
            Log.d(TAG, "✅ SCENARIO 2: NORMAL APP USAGE - Preserving sessions and connections");

            // Update last activity time
            appInstallPrefs.edit()
                    .putLong("last_activity_time", System.currentTimeMillis())
                    .apply();

            return false; // NEVER clear data except on true first install

        } catch (Exception e) {
            Log.e(TAG, "Error in bulletproof install detection: " + e.getMessage());
            // On error, don't clear data to be safe
            return false;
        }
    }

    /**
     * Get the actual app installation time from PackageManager
     * This changes when app is uninstalled and reinstalled
     */
    private long getAppInstallTime() {
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.firstInstallTime;
        } catch (Exception e) {
            Log.e(TAG, "Error getting app install time: " + e.getMessage());
            // Fallback to current time
            return System.currentTimeMillis();
        }
    }

    /**
     * BACKUP RESTORE DETECTION
     * Detects if Android restored data from backup after reinstall
     * This happens even with backup disabled sometimes
     */
    private boolean detectUnexpectedDataRestore() {
        try {
            SharedPreferences appInstallPrefs = getSharedPreferences("app_install_detection", MODE_PRIVATE);
            boolean hasRunBefore = appInstallPrefs.getBoolean("has_run_before", false);

            // If we have install detection data but also have session data,
            // it might be from backup restore
            if (hasRunBefore && sessionManager != null) {
                boolean hasSessionData = sessionManager.isLoggedIn();
                long lastKnownInstallTime = appInstallPrefs.getLong("install_time", 0);
                long currentInstallTime = getAppInstallTime();

                // Check for signs of backup restore:
                // 1. Has session data but install time is very recent (within 5 minutes)
                // 2. Install time changed significantly but we still have session data
                long timeSinceInstall = System.currentTimeMillis() - currentInstallTime;
                boolean recentInstall = timeSinceInstall < (5 * 60 * 1000); // 5 minutes
                boolean installTimeChanged = Math.abs(currentInstallTime - lastKnownInstallTime) > 60000;

                if (hasSessionData && (recentInstall || installTimeChanged)) {
                    Log.w(TAG, "🚨 BACKUP RESTORE DETECTED:");
                    Log.w(TAG, "   Has session data: " + hasSessionData);
                    Log.w(TAG, "   Recent install: " + recentInstall + " (" + timeSinceInstall + "ms ago)");
                    Log.w(TAG, "   Install time changed: " + installTimeChanged);
                    Log.w(TAG, "   Current install time: " + currentInstallTime);
                    Log.w(TAG, "   Last known install time: " + lastKnownInstallTime);
                    return true;
                }
            }

            // Also check if we have session data but no install detection data at all
            // This could happen if backup restored session but not install detection
            if (!hasRunBefore && sessionManager != null && sessionManager.isLoggedIn()) {
                Log.w(TAG, "🚨 ORPHANED SESSION DATA DETECTED - Session exists but no install record");
                return true;
            }

            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error detecting backup restore: " + e.getMessage());
            // If error, assume clean state
            return false;
        }
    }

    /**
     * COMPLETE REINSTALL CLEANUP
     * Only runs on actual app reinstall (uninstall -> reinstall)
     * Preserves connections during normal app usage
     */
    private void performCompleteReinstallCleanup() {
        try {
            Log.d(TAG, "🚨 REINSTALL CLEANUP - App was uninstalled and reinstalled, clearing all data");

            // Clear session data
            if (sessionManager != null) {
                sessionManager.logoutUser();
                Log.d(TAG, "  ✅ Session manager cleared (reinstall)");
            }

            // Clear all app data for true fresh start
            clearAllAppData();

            Log.d(TAG, "✅ REINSTALL CLEANUP COMPLETED - App is now in pristine state");

        } catch (Exception e) {
            Log.e(TAG, "Error during reinstall cleanup: " + e.getMessage());
        }
    }

    /**
     * COMPLETE APP DATA CLEARING
     * Only used on actual app reinstall - clears everything for fresh start
     * Preserves install detection data to prevent repeated clearing
     */
    private void clearAllAppData() {
        try {
            Log.d(TAG, "🧹 COMPLETE APP DATA CLEARING - Removing all traces of previous installation");

            // List of ALL preferences that need clearing on reinstall
            String[] allPreferences = {
                    "user_session", // User login state
                    "device_connection", // Device connection state
                    "child_session", // Child device session
                    "logout_state", // Logout tracking
                    "connected_devices", // Device storage
                    "permanently_removed_devices", // Removal tracking
                    "MasterAppSession", // SessionManager data
                    "timer_state", // Timer state
                    "app_usage_limits", // Usage limits
                    "blocked_apps", // Blocked apps list
                    "device_pairing", // Pairing information
                    "firebase_cache", // Firebase cached data
                    "qr_scanning_state", // QR scanning state
                    "parent_dashboard_cache", // Parent dashboard cache
                    "child_dashboard_cache", // Child dashboard cache
                    "connection_history", // Connection history
                    "usage_statistics", // Usage statistics
                    "notification_settings", // Notification preferences
                    "theme_settings", // Theme preferences
                    "tutorial_completed" // Tutorial state
                    // NOTE: NOT clearing "app_install_detection" - we need this for future
                    // detection
            };

            int clearedCount = 0;
            for (String prefName : allPreferences) {
                SharedPreferences prefs = getSharedPreferences(prefName, MODE_PRIVATE);
                int itemCount = prefs.getAll().size();
                if (itemCount > 0) {
                    prefs.edit().clear().apply();
                    clearedCount++;
                    Log.d(TAG, "  ✅ Cleared: " + prefName + " (" + itemCount + " items)");
                }
            }

            // Clear any additional app-related preferences
            clearAdditionalAppPreferences();

            Log.d(TAG, "✅ COMPLETE DATA CLEARING FINISHED - Cleared " + clearedCount + " preference files");

        } catch (Exception e) {
            Log.e(TAG, "Error during complete app data clearing: " + e.getMessage());
        }
    }

    /**
     * Clear any additional app-related SharedPreferences that might exist
     */
    private void clearAdditionalAppPreferences() {
        try {
            java.io.File prefsDir = new java.io.File(getApplicationInfo().dataDir, "shared_prefs");
            if (prefsDir.exists() && prefsDir.isDirectory()) {
                java.io.File[] prefsFiles = prefsDir.listFiles();
                if (prefsFiles != null) {
                    for (java.io.File file : prefsFiles) {
                        String fileName = file.getName();
                        // Clear any file that looks app-related, but preserve install detection
                        if ((fileName.contains("master") || fileName.contains("session") ||
                                fileName.contains("device") || fileName.contains("child") ||
                                fileName.contains("parent") || fileName.contains("timer") ||
                                fileName.contains("usage") || fileName.contains("block")) &&
                                !fileName.contains("app_install_detection")) {

                            String prefName = fileName.replace(".xml", "");
                            SharedPreferences extraPrefs = getSharedPreferences(prefName, MODE_PRIVATE);
                            if (extraPrefs.getAll().size() > 0) {
                                extraPrefs.edit().clear().apply();
                                Log.d(TAG, "  ✅ Cleared additional: " + prefName);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing additional app preferences: " + e.getMessage());
        }
    }

    /**
     * 🗑️ DEPRECATED: Old validation method - replaced by
     * validateSessionWithGrace()
     * Kept for backward compatibility but should not be called
     */
    @Deprecated
    private boolean validateSessionConsistency(String userType) {
        Log.w(TAG, "⚠️ Using deprecated validateSessionConsistency - should use new validation");

        // Redirect to new validation
        com.google.firebase.auth.FirebaseUser firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance()
                .getCurrentUser();
        ValidationResult result = validateSessionImmediate(userType, firebaseUser != null);
        return result.isValid;
    }

    /**
     * 🛡️ Check if bulletproof disconnection protection is active
     */
    private boolean isBulletproofDisconnectionActive() {
        try {
            SharedPreferences state = getSharedPreferences(
                    "disconnection_state", MODE_PRIVATE);
            boolean removed = state.getBoolean("device_was_removed", false);
            boolean logoutCompleted = state.getBoolean(
                    "bulletproof_logout_completed", false);
            boolean requiresQr = state.getBoolean(
                    "require_qr_reconnection", false);
            return (removed || logoutCompleted) && requiresQr;
        } catch (Exception error) {
            Log.e(TAG, "Failed to read child disconnection state", error);
            return true;
        }
    }

    /**
     * 🔍 Validate if connection is genuinely active and valid
     */
    private boolean isValidActiveConnection(String childDeviceId, String parentName) {
        try {
            // Check 1: Basic session data completeness
            if (childDeviceId == null || childDeviceId.isEmpty() ||
                    parentName == null || parentName.isEmpty()) {
                Log.d(TAG, "  ❌ Validation failed: Missing basic session data");
                return false;
            }

            // Check 2: Check for bulletproof disconnection protection
            if (isBulletproofDisconnectionActive()) {
                Log.d(TAG, "  ❌ Validation failed: Bulletproof disconnection protection active");
                return false;
            }

            // Check 3: Connection state flag (be lenient for fresh connections)
            SharedPreferences connectionPrefs = getSharedPreferences("device_connection", MODE_PRIVATE);
            boolean isConnected = connectionPrefs.getBoolean("is_connected", true); // Default to true for fresh
                                                                                    // connections
            boolean connectionValid = connectionPrefs.getBoolean("connection_valid", true);

            // Check 4: No recent explicit disconnection (bulletproof disconnection marker)
            long lastDisconnectionTime = connectionPrefs.getLong("disconnection_time", 0);
            long currentTime = System.currentTimeMillis();
            boolean noRecentDisconnection = lastDisconnectionTime == 0
                    || (currentTime - lastDisconnectionTime) > (5 * 60 * 1000); // 5 minutes

            // Check 5: SessionManager consistency
            boolean sessionValid = sessionManager.isSessionDataComplete();

            boolean validConnection = isConnected && connectionValid && noRecentDisconnection && sessionValid;

            Log.d(TAG, "🔍 CONNECTION VALIDATION:");
            Log.d(TAG, "  Is connected: " + isConnected);
            Log.d(TAG, "  Connection valid: " + connectionValid);
            Log.d(TAG, "  No recent disconnection: " + noRecentDisconnection);
            Log.d(TAG, "  Session valid: " + sessionValid);
            Log.d(TAG, "  Overall valid: " + validConnection);

            return validConnection;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error validating connection: " + e.getMessage());
            return false; // Default to invalid for safety
        }
    }

    /**
     * 🧹 Clear any remaining session data
     */
    private void clearAnyRemainingSessionData() {
        try {
            // Clear SessionManager
            sessionManager.logoutUser();

            // Clear connection preferences
            String[] prefsTolear = { "device_connection", "child_session", "child_prefs",
                    "connection_prefs", "device_info", "emergency_session_backup" };

            for (String prefName : prefsTolear) {
                SharedPreferences prefs = getSharedPreferences(prefName, MODE_PRIVATE);
                prefs.edit().clear().apply();
                Log.d(TAG, "  ✅ Cleared: " + prefName);
            }

            Log.d(TAG, "🧹 Remaining session data cleared");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error clearing remaining session data: " + e.getMessage());
        }
    }

    /**
     * 🚪 Display clean login screen
     * Shows the Welcome Back layout in the current activity
     * 🔧 FIX: No longer restarts MainActivity (which caused infinite loop crash)
     */
    private void displayLoginScreen() {
        try {
            Log.d(TAG, "📱 Setting up login screen in current activity");

            // Setup MainActivity UI here instead of restarting the activity
            // This prevents the infinite loop that was causing crashes
            if (binding == null) {
                binding = ActivityMainBinding.inflate(getLayoutInflater());
                setContentView(binding.getRoot());
            }

            // Setup click listeners for the login screen
            if (binding.btnGetStarted != null) {
                binding.btnGetStarted.setOnClickListener(v -> {
                    startActivity(new Intent(MainActivity.this, RoleSelectionActivity.class));
                });
            }

            Log.d(TAG, "✅ Login screen displayed successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error displaying login screen: " + e.getMessage());
            // Last resort fallback - just setup binding
            try {
                binding = ActivityMainBinding.inflate(getLayoutInflater());
                setContentView(binding.getRoot());
                binding.btnGetStarted.setOnClickListener(v -> {
                    startActivity(new Intent(MainActivity.this, RoleSelectionActivity.class));
                });
            } catch (Exception fallbackError) {
                Log.e(TAG, "❌ Fallback also failed: " + fallbackError.getMessage());
            }
        }
    }

    /**
     * 🧹 Clear logout intent flags
     */
    private void clearLogoutIntentFlags() {
        try {
            getIntent().removeExtra("force_login_screen");
            getIntent().removeExtra("auto_logout_completed");
            getIntent().removeExtra("bulletproof_cleanup_completed");
            getIntent().removeExtra("device_was_removed");
            getIntent().removeExtra("require_qr_reconnection");
            Log.d(TAG, "✅ Logout intent flags cleared");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error clearing intent flags: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
