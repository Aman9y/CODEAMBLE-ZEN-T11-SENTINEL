package online.monarchlabs.sentinel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.widget.Toast;
import android.util.Log;
import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import java.util.List;
import java.util.Map;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Locale;
import java.text.SimpleDateFormat;
import android.app.usage.UsageEvents;
import online.monarchlabs.sentinel.utils.CrashlyticsLogger;
import online.monarchlabs.sentinel.utils.PipUsageTracker;

public class BlockService extends AccessibilityService {
    private static final String TAG = "BlockService";
    private SharedPreferences prefs;
    private String lastBroadcastPkg = "";
    private String currentForegroundApp = "";
    private String lastEnforcedBlockedPackage = "";
    private long lastBlockEnforcementTime = 0;
    private Handler accuracyHandler;
    private Runnable accuracyRunnable;
    private long lastUsageCheckTime = 0;
    private UsageStatsManager usageStatsManager;
    private BroadcastReceiver blockedAppsReceiver;
    private Handler pipUsageHandler;
    private Runnable pipUsageRunnable;
    private PipUsageTracker pipUsageTracker;
    private boolean hasVisiblePipApps = false;

    // CRITICAL: Device type detection
    private boolean isParentDevice = false;
    private Boolean lastDetectedIsParent = null;
    private long lastDeviceTypeCheckTime = 0;
    private SessionManager sessionManager;
    private String lastChildDeviceId = ""; // Track the child device ID transition

    // For extremely accurate monitoring with interaction detection
    private static final int ACCURACY_CHECK_INTERVAL = 500;
    private boolean operationalMonitoringStarted = false;
    private Handler onboardingHandler;
    private Runnable onboardingRunnable;

    // 🆕 ENHANCED BLOCKING: Track visible windows for split-screen/floating
    // detection
    private Set<String> visibleBlockedApps = new HashSet<>();
    private boolean isMultiWindowCheckRunning = false;

    // BLOCK_TIMER_MIGRATION: Parallel timer variables

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "🔗 BlockService connected");

        // CRITICAL: Detect device type FIRST
        detectDeviceType();

        Log.d(TAG, "📱 Initial device type is parent: " + isParentDevice);

        prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
        usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        pipUsageTracker = PipUsageTracker.getInstance(this);
        windowManager = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);

        // Register broadcast receiver for blocked apps updates
        setupBlockedAppsReceiver();

        startOperationalMonitoringWhenReady();
    }

    private void startOperationalMonitoringWhenReady() {
        if (operationalMonitoringStarted) {
            return;
        }

        if (isPermissionSetupActive()) {
            scheduleOperationalMonitoringRetry();
            return;
        }

        String childDeviceId = sessionManager != null ? sessionManager.getChildDeviceId() : null;
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.d(TAG, "Pairing is not complete; deferring intensive accessibility monitoring");
            scheduleOperationalMonitoringRetry();
            return;
        }

        operationalMonitoringStarted = true;
        if (onboardingHandler != null && onboardingRunnable != null) {
            onboardingHandler.removeCallbacks(onboardingRunnable);
        }

        // Initialize accuracy monitoring
        accuracyHandler = new Handler(Looper.getMainLooper());
        startAccuracyMonitoring();

        // 🚀 SYNC: Start frequent usage data upload (every 2 minutes)
        // This runs within the robust Accessibility Service for maximum reliability
        startFrequentUsageSync();

        // ⚙️ Setup Firebase listener for self-healing configurations (Rollback support)
        setupSelfHealingConfigListener();

        // BLOCK_TIMER_MIGRATION: Initialize parallel timer monitoring

        // Run diagnostics to help troubleshoot blocking issues
        runDiagnostics();

        // 🛡️ WATCHDOG SUPERVISOR: Immediate check on startup
        verifyCoreServicesRunning(true);

        Log.d(TAG, "✅ BlockService fully initialized and ready to block apps");
    }

    private void scheduleOperationalMonitoringRetry() {
        Log.d(TAG, "Permission setup is active; deferring accessibility enforcement");
        if (onboardingHandler == null) {
            onboardingHandler = new Handler(Looper.getMainLooper());
        }
        if (onboardingRunnable == null) {
            onboardingRunnable = this::startOperationalMonitoringWhenReady;
        }
        onboardingHandler.removeCallbacks(onboardingRunnable);
        onboardingHandler.postDelayed(onboardingRunnable, 1000L);
    }

    private boolean isPermissionSetupActive() {
        return getSharedPreferences("child_onboarding_state", MODE_PRIVATE)
                .getBoolean("permission_setup_active", false);
    }

    // 🔄 SYNC: Frequent usage upload
    private Handler syncHandler;
    private Runnable syncRunnable;
    private static final long SYNC_INTERVAL_MS = 30 * 1000; // Near-real-time without per-second Firebase writes

    // 🛡️ ACCESSIBILITY OVERLAY
    private android.view.WindowManager windowManager;
    private android.view.View blockingOverlay;


    private void startFrequentUsageSync() {
        Log.d(TAG, "🔄 Starting frequent usage sync (30 sec interval)");
        syncHandler = new Handler(Looper.getMainLooper());
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                // Dynamically re-detect device type on sync
                detectDeviceType();

                // Usage upload is centralized in UsageUploadWorker/SUsageDataManager.

                if (selfHealingConfigListener == null && !isParentDevice) {
                    Log.d(TAG, "🔄 Retrying self-healing config listener setup during sync...");
                    setupSelfHealingConfigListener();
                }

                // SELF-HEALING: Retry timer setup if it failed previously
                // if (migrationTimersListener == null && !isParentDevice) {
                //     Log.d(TAG, "BLOCK_TIMER_MIGRATION: 🔄 Retrying timer listener setup during sync...");
                //     setupBlockTimerMigration();
                // }

                // Schedule next run
                syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
            }
        };
        // Run immediately first time
        syncHandler.post(syncRunnable);
    }

    private void performUsageUpload() {
        if (isParentDevice)
            return;

        try {
            Log.d(TAG, "📤 Performing frequent usage uploads...");
            String deviceId = sessionManager.getChildDeviceId();
            if (deviceId != null && !deviceId.isEmpty()) {
                // No-op: usage upload is centralized in UsageUploadWorker/SUsageDataManager.
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Sync failed: " + e.getMessage());
        }
    }

    private void detectDeviceType() {
        boolean previousValue = isParentDevice;
        boolean detectedValue = false;
        try {
            sessionManager = new SessionManager(this);

            // Check for child device ID transitions immediately
            String currentDeviceId = sessionManager.getChildDeviceId();
            if (currentDeviceId != null && !currentDeviceId.isEmpty()) {
                if (lastChildDeviceId == null || lastChildDeviceId.isEmpty() || !currentDeviceId.equals(lastChildDeviceId)) {
                    Log.d(TAG, "📱 Child device ID transition detected (from empty/changed to: " + currentDeviceId + "). Setting up listeners immediately.");
                    // Remove old self-healing listener first to be safe
                    if (selfHealingConfigListener != null) {
                        try {
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                    .getReference("v2")
                                    .child("client_capabilities")
                                    .child(lastChildDeviceId.isEmpty() ? currentDeviceId : lastChildDeviceId)
                                    .child("self_healing_config")
                                    .removeEventListener(selfHealingConfigListener);
                        } catch (Exception ignored) {}
                        selfHealingConfigListener = null;
                    }

                    lastChildDeviceId = currentDeviceId;
                    // Re-register immediately
                    setupSelfHealingConfigListener();
                }
            } else {
                lastChildDeviceId = "";
            }

            // Method 1 (strict): trust explicit role only when role data is complete.
            if (sessionManager.isLoggedIn()) {
                String userType = sessionManager.getUserType();

                if ("parent".equals(userType)) {
                    if (sessionManager.isParentSessionComplete()) {
                        detectedValue = true;
                    } else {
                        detectedValue = false;
                    }
                } else if ("child".equals(userType)) {
                    if (sessionManager.isChildSessionComplete()) {
                        detectedValue = false;
                    } else {
                        detectedValue = false;
                    }
                }
            } else {
                // Method 2 (fallback): prefer child if clear child markers exist.
                String qrShareKey = sessionManager.getQRShareKey();
                String parentName = sessionManager.getParentName();
                String childDeviceId = sessionManager.getChildDeviceId();
                String userId = sessionManager.getUserId();
                String phoneNumber = sessionManager.getPhoneNumber();
                String deviceName = sessionManager.getDeviceName();

                if (parentName != null && !parentName.isEmpty() &&
                        childDeviceId != null && !childDeviceId.isEmpty() &&
                        qrShareKey != null && !qrShareKey.isEmpty()) {
                    detectedValue = false;
                } else if (userId != null && !userId.isEmpty() &&
                        phoneNumber != null && !phoneNumber.isEmpty() &&
                        deviceName != null && !deviceName.isEmpty()) {
                    detectedValue = true;
                } else {
                    // Default safety: keep blocking enabled unless parent is strongly proven.
                    detectedValue = false;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error detecting device type: " + e.getMessage());
            // Safe fallback: keep blocking enabled.
            detectedValue = false;
        }

        isParentDevice = detectedValue;

        // Only log and notify when the device type changes or is evaluated for the first time
        if (lastDetectedIsParent == null || previousValue != isParentDevice) {
            lastDetectedIsParent = isParentDevice;
            Log.d(TAG, "📱 Device type transition detected: " + (isParentDevice ? "PARENT" : "CHILD") + " (previous: " + (previousValue ? "PARENT" : "CHILD") + ")");
            if (isParentDevice) {
                Log.d(TAG, "🚫 PARENT DEVICE - BLOCKING ENFORCEMENT PAUSED");
                Toast.makeText(this, "📱 Parent Device - Blocking Disabled", Toast.LENGTH_LONG).show();

                // Clear any existing blocked apps to prevent accidental blocking on parent device
                prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
                if (prefs != null) {
                    prefs.edit().clear().apply();
                }
            } else {
                Log.d(TAG, "✅ CHILD DEVICE - BLOCKING ENFORCEMENT ACTIVE");
                Toast.makeText(this, "📱 Child Device - Blocking Enabled", Toast.LENGTH_LONG).show();
                // Immediately initialize child-specific listeners/timers on role switch
                setupSelfHealingConfigListener();
            }
        }
    }

    private void setupBlockedAppsReceiver() {
        Log.d(TAG, "📡 Setting up blocked apps broadcast receiver");

        blockedAppsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Dynamically re-detect device type
                detectDeviceType();
                if (isParentDevice) {
                    Log.d(TAG, "📡 Received blocked apps update broadcast, but ignored on parent device");
                    return;
                }

                if ("online.monarchlabs.sentinel.BLOCKED_APPS_UPDATED".equals(intent.getAction())) {
                    int blockedCount = intent.getIntExtra("blocked_count", 0);
                    Log.d(TAG, "📡 Received blocked apps update broadcast - count: " + blockedCount);

                    // Reload blocked apps immediately
                    reloadBlockedApps();

                    String changedPackage = intent.getStringExtra("changed_package");
                    if (changedPackage != null
                            && isManuallyBlockedOrExpired(changedPackage)
                            && isPackageVisible(changedPackage)) {
                        Log.w(TAG, "⚡ IMMEDIATE TARGET BLOCK: Blocked app is visible: " + changedPackage);
                        blockAppEnhanced(changedPackage);
                        return;
                    }

                    // Check and enforce immediately if the active app is blocked
                    String currentApp = getCurrentForegroundPackage();
                    if (currentApp != null && !shouldSkipForBlocking(currentApp)) {
                        if (isManuallyBlockedOrExpired(currentApp)) {
                            Log.w(TAG, "⚡ IMMEDIATE BROADCAST BLOCK: Blocked app active: " + currentApp);
                            blockAppEnhanced(currentApp);
                        }
                    }

                    // Show feedback to user
                    String message = blockedCount > 0 ? "🚫 " + blockedCount + " apps now blocked"
                            : "✅ All apps unblocked";
                    Toast.makeText(BlockService.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        };

        IntentFilter filter = new IntentFilter("online.monarchlabs.sentinel.BLOCKED_APPS_UPDATED");

        // Fix for Android 8.0+ (API 26+) - specify receiver export flag for security
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(blockedAppsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "✅ Broadcast receiver registered with RECEIVER_NOT_EXPORTED flag");
        } else {
            registerReceiver(blockedAppsReceiver, filter);
            Log.d(TAG, "✅ Broadcast receiver registered (legacy mode)");
        }
        Log.d(TAG, "✅ Broadcast receiver registered");
    }

    private void reloadBlockedApps() {
        // Dynamically re-detect device type
        detectDeviceType();
        // Only reload on child devices
        if (isParentDevice) {
            Log.d(TAG, "🚫 Skipping blocked apps reload on parent device");
            return;
        }

        Log.d(TAG, "🔄 Reloading blocked apps from SharedPreferences");

        // Force reload SharedPreferences (this is crucial for synchronization)
        prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);

        Map<String, ?> allEntries = prefs.getAll();
        Log.d(TAG, "📋 Reloaded " + allEntries.size() + " blocked apps:");
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            Log.d(TAG, "🔒 " + entry.getKey() + " = " + entry.getValue());
        }
    }

    private void startAccuracyMonitoring() {
        accuracyRunnable = new Runnable() {
            @Override
            public void run() {
                checkCurrentForegroundApp();
                accuracyHandler.postDelayed(this, ACCURACY_CHECK_INTERVAL);
            }
        };
        accuracyHandler.post(accuracyRunnable);
    }

    private void checkCurrentForegroundApp() {
        String currentApp = getCurrentForegroundPackage();
        if (currentApp == null || currentApp.isEmpty()) {
            return;
        }

        boolean appChanged = !currentApp.equals(currentForegroundApp);
        currentForegroundApp = currentApp;

        // Re-detect device type if app changed, or periodically (every 5 seconds)
        long now = System.currentTimeMillis();
        if (appChanged || (now - lastDeviceTypeCheckTime > 5000)) {
            lastDeviceTypeCheckTime = now;
            detectDeviceType();
        }

        if (isParentDevice) {
            return;
        }

        // Check on every pass. A remote block can arrive while the foreground app
        // remains unchanged and produces no new accessibility event.
        if (!shouldSkipForBlocking(currentApp)) {
            if (isManuallyBlockedOrExpired(currentApp)) {
                Log.w(TAG, "⚡ AGGRESSIVE: Blocked/expired-timer foreground app detected: " + currentApp);
                blockAppEnhanced(currentApp);
                return;
            }
        }

        if (appChanged) {
            broadcastForegroundApp(currentApp);
        }
    }

    private String getCurrentForegroundPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                CharSequence packageName = root.getPackageName();
                if (packageName != null && packageName.length() > 0) {
                    return packageName.toString();
                }
            } finally {
                root.recycle();
            }
        }

        return getCurrentForegroundAppFromUsageStats();
    }

    private boolean isPackageVisible(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }

        String foregroundPackage = getCurrentForegroundPackage();
        if (packageName.equals(foregroundPackage) || packageName.equals(currentForegroundApp)) {
            return true;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for (AccessibilityWindowInfo window : getWindows()) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) {
                        continue;
                    }
                    try {
                        CharSequence visiblePackage = root.getPackageName();
                        if (visiblePackage != null && packageName.contentEquals(visiblePackage)) {
                            return true;
                        }
                    } finally {
                        root.recycle();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Unable to inspect visible windows: " + e.getMessage());
            }
        }

        return false;
    }

    private String getCurrentForegroundAppFromUsageStats() {
        if (usageStatsManager == null)
            return null;

        long currentTime = System.currentTimeMillis();
        long startTime = currentTime - 3000; // Last 3 seconds for better accuracy

        try {
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, currentTime);
            UsageEvents.Event event = new UsageEvents.Event();
            String lastForegroundApp = null;
            long latestTimestamp = 0;

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);

                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    // Track the most recent app that came to foreground
                    if (event.getTimeStamp() > latestTimestamp) {
                        lastForegroundApp = event.getPackageName();
                        latestTimestamp = event.getTimeStamp();
                        Log.v(TAG, "🚀 Recent app resumed: " + event.getPackageName() + " at "
                                + new Date(event.getTimeStamp()));
                    }
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                    Log.v(TAG, "⏸️ App paused: " + event.getPackageName() + " at " + new Date(event.getTimeStamp()));
                }
            }

            return lastForegroundApp;
        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app from usage stats: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isPermissionSetupActive()) {
            return;
        }

        // Only re-detect on window state change to keep accessibility processing fast
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            detectDeviceType();
        }

        // CRITICAL: Do not block anything on parent devices
        if (isParentDevice) {
            // Still broadcast foreground app for analytics but don't block anything
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (event.getPackageName() != null) {
                    String packageName = event.getPackageName().toString();
                    broadcastForegroundApp(packageName);
                }
            }
            return;
        }

        // 🛡️ WATCHDOG SUPERVISOR: Throttled check for child devices
        verifyCoreServicesRunning(false);

        // ENHANCED USER INTERACTION DETECTION (CHILD DEVICES ONLY)
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // 🆕 ENHANCED: Handle multi-window detection (split-screen, floating windows,
        // PIP)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            Log.d(TAG, "🪟 WINDOWS_CHANGED event detected - checking multi-window mode");
            handlePictureInPictureWindows();
            checkAndBlockMultiWindowApps();
        }

        // ENHANCED AND BULLETPROOF BLOCKING LOGIC (CHILD DEVICES ONLY)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) {
                // Use the packageName already declared above
                packageName = event.getPackageName().toString();

                // Update current foreground app immediately
                currentForegroundApp = packageName;

                // *** CRITICAL: NEVER BLOCK OUR OWN APP ***
                String ourPackageName = getPackageName();
                if (packageName.equals(ourPackageName) ||
                        packageName.contains("online.monarchlabs.sentinel") ||
                        packageName.equals("online.monarchlabs.sentinel")) {
                    Log.d(TAG, "🛡️ PROTECTING OUR OWN APP: " + packageName);
                    broadcastForegroundApp(packageName);
                    return;
                }

                // Skip system apps and launchers for blocking
                if (shouldSkipForBlocking(packageName)) {
                    // Broadcast for timer logic but don't block
                    broadcastForegroundApp(packageName);
                    return;
                }

                // *** CRITICAL BLOCKING LOGIC ***
                // 🔧 Read both manual blocks AND expired timers for INSTANT updates
                boolean shouldBlock = isManuallyBlockedOrExpired(packageName);

                // Log every single app launch for debugging
                Log.d(TAG,
                        "🔍 APP LAUNCHED: " + packageName + " | SHOULD_BLOCK: " + shouldBlock + " | OUR APP: " + ourPackageName);

                // DOUBLE CHECK: Make sure we're not blocking ourselves
                if (packageName.equals(ourPackageName)) {
                    Log.d(TAG, "🛡️ DOUBLE PROTECTION: Not blocking our own app");
                    broadcastForegroundApp(packageName);
                    return;
                }

                // If app is blocked or its timer expired, block it immediately
                if (shouldBlock) {
                    Log.d(TAG, "🚫 BLOCKING APP NOW: " + packageName);
                    blockAppEnhanced(packageName);
                    return; // Don't broadcast blocked app
                }

                // If not blocked, broadcast normally for timer logic
                broadcastForegroundApp(packageName);
            }
        }

        // 🆕 ENHANCED: Handle interactive changes for floating/split-screen windows
        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            if (event.getPackageName() != null) {
                packageName = event.getPackageName().toString();
                if (!shouldSkipForBlocking(packageName)) {
                    if (isManuallyBlockedOrExpired(packageName)) {
                        Log.d(TAG, "🔍 BLOCKED/EXPIRED APP INTERACTED (floating window?): " + packageName);
                        blockAppEnhanced(packageName);
                    }
                }
            }
        }
    }
    /**
     * ENHANCED: Check all visible windows for blocked apps
     * This handles split-screen, floating windows, and PIP mode
     */
    private void handlePictureInPictureWindows() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        Set<String> pipPackages = new HashSet<>();
        SharedPreferences freshPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        || !window.isInPictureInPictureMode()) {
                    continue;
                }

                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }

                try {
                    CharSequence packageName = root.getPackageName();
                    String packageValue = packageName == null ? "" : packageName.toString();
                    if (!shouldTrackPipPackage(packageValue)) {
                        continue;
                    }

                    pipPackages.add(packageValue);
                    if (!AppBlockingPolicy.isUnblockable(packageValue)
                            && freshPrefs.getBoolean(packageValue, false)) {
                        boolean dismissed = root.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
                        Log.d(TAG, "Blocked PiP dismiss requested for " + packageValue + ": " + dismissed);
                        blockAppEnhanced(packageValue);
                    }
                } finally {
                    root.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to inspect PiP windows: " + e.getMessage());
        }

        updatePipUsageTracking(pipPackages);
    }

    private boolean shouldTrackPipPackage(String packageName) {
        return packageName != null
                && !packageName.isEmpty()
                && !packageName.equals(getPackageName())
                && !packageName.equals("android")
                && !packageName.equals("com.android.systemui")
                && !packageName.contains("launcher")
                && !packageName.startsWith("com.android.inputmethod");
    }

    private void updatePipUsageTracking(Set<String> pipPackages) {
        if (pipUsageTracker == null) {
            pipUsageTracker = PipUsageTracker.getInstance(this);
        }
        pipUsageTracker.updateVisiblePackages(pipPackages);
        hasVisiblePipApps = !pipPackages.isEmpty();

        if (hasVisiblePipApps && pipUsageHandler == null) {
            pipUsageHandler = new Handler(Looper.getMainLooper());
            pipUsageRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!hasVisiblePipApps) {
                        return;
                    }
                    pipUsageTracker.checkpoint();
                    pipUsageHandler.postDelayed(this, 5000);
                }
            };
            pipUsageHandler.postDelayed(pipUsageRunnable, 5000);
        } else if (!hasVisiblePipApps && pipUsageHandler != null && pipUsageRunnable != null) {
            pipUsageHandler.removeCallbacks(pipUsageRunnable);
            pipUsageHandler = null;
            pipUsageRunnable = null;
        }
    }

    private void checkAndBlockMultiWindowApps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            return;

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            Set<String> blockedPackages = new HashSet<>();
            boolean inSplitScreen = false;

            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                    inSplitScreen = true;
                }

                // Get the root node to find the package name
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    CharSequence pkgName = root.getPackageName();
                    if (pkgName != null) {
                        String pkg = pkgName.toString();

                        // Check if this package is manually blocked or has an expired timer
                        if (!AppBlockingPolicy.isUnblockable(pkg)
                                && isManuallyBlockedOrExpired(pkg)) {
                            blockedPackages.add(pkg);
                            Log.d(TAG, "🪟 Found blocked/expired app in multi-window: " + pkg +
                                    " | Window type: " + window.getType());

                            // Try to dismiss freeform/PIP windows directly via Accessibility
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                root.performAction(AccessibilityNodeInfo.ACTION_DISMISS);
                            }
                        }
                    }
                    root.recycle();
                }
            }

            // If we are in split screen and a blocked app is active, explicitly collapse split screen!
            if (inSplitScreen && !blockedPackages.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
                Log.d(TAG, "🪟 Collapsed split-screen mode due to blocked app!");
            }

            // Block all found blocked apps
            for (String blockedPkg : blockedPackages) {
                Log.d(TAG, "🚫 BLOCKING MULTI-WINDOW APP: " + blockedPkg);
                blockAppEnhanced(blockedPkg);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error checking multi-window apps: " + e.getMessage());
        }
    }

    /**
     * Check if package should be skipped for blocking
     * 🔧 FIXED: Only skip ACTUAL system components, not user-facing apps like
     * Chrome/Play Store
     */
    private boolean shouldSkipForBlocking(String packageName) {
        if (packageName == null)
            return true;

        // NEVER skip our own app
        if (packageName.contains("online.monarchlabs.sentinel"))
            return false;

        // Only skip CRITICAL system components
        return packageName.equals("android") ||
                packageName.contains("launcher") ||
                packageName.equals("com.android.systemui") ||
                AppBlockingPolicy.isUnblockable(packageName) ||
                packageName.startsWith("com.android.inputmethod"); // System keyboard
    }

    private void broadcastForegroundApp(String packageName) {
        if (packageName == null)
            packageName = "";

        // Avoid spam by checking if package changed
        if (packageName.equals(lastBroadcastPkg))
            return;
        lastBroadcastPkg = packageName;

        // Determine if system app
        boolean isSystem = isSystemApp(packageName);

        Intent intent = new Intent("online.monarchlabs.sentinel.APP_FOREGROUND");
        intent.putExtra("package_name", packageName);
        intent.putExtra("package", packageName);
        intent.putExtra("isSystem", isSystem);
        intent.putExtra("interaction_type", "foreground_change");
        sendBroadcast(intent);

        Log.d(TAG, "📱 Broadcasted foreground app: " + packageName);

        if (sessionManager == null) {
            sessionManager = new SessionManager(this);
        }
        String deviceId = sessionManager.getChildDeviceId();
        if (deviceId != null && !deviceId.isEmpty()) {
            try {
                com.google.firebase.database.FirebaseDatabase.getInstance().getReference("v2")
                        .child("device_status")
                        .child(deviceId)
                        .child("foreground_app")
                        .setValue(packageName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to update foreground_app in Firebase: " + e.getMessage());
            }
        }
    }

    // REMOVED - using simple app switching only

    private void blockAppEnhanced(String packageName) {
        if (AppBlockingPolicy.isUnblockable(packageName)) {
            getSharedPreferences("blocked_apps", MODE_PRIVATE)
                    .edit()
                    .remove(packageName)
                    .apply();
            Log.w(TAG, "Ignoring block request for unblockable app: " + packageName);
            return;
        }

        long now = System.currentTimeMillis();
        if (packageName.equals(lastEnforcedBlockedPackage) && now - lastBlockEnforcementTime < 150) {
            return;
        }
        lastEnforcedBlockedPackage = packageName;
        lastBlockEnforcementTime = now;

        Log.d(TAG, "🚫 ENHANCED MULTI-LAYER BLOCKING: " + packageName);
        String appName = getAppName(packageName);

        // LAYER 1: AGGRESSIVE BACK PRESS (Must be first to kill focused floating windows!)
        performGlobalAction(GLOBAL_ACTION_BACK);
        performGlobalAction(GLOBAL_ACTION_BACK);

        // LAYER 1.5: HOME ACTION
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_HOME);
            performGlobalAction(GLOBAL_ACTION_RECENTS); // Clear recents
            performGlobalAction(GLOBAL_ACTION_HOME); // Return home
            Log.d(TAG, "🏠 Layer 1.5: Home actions performed");
        }, 50);

        // LAYER 1.75: ACCESSIBILITY OVERLAY (Failsafe for floating windows)
        showBlockingOverlay();

        // LAYER 2: TASK REMOVAL (Android 5.0+)
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<ActivityManager.AppTask> tasks = am.getAppTasks();
                for (ActivityManager.AppTask task : tasks) {
                    if (task.getTaskInfo() != null &&
                            task.getTaskInfo().baseIntent != null &&
                            task.getTaskInfo().baseIntent.getComponent() != null) {
                        String taskPkg = task.getTaskInfo().baseIntent.getComponent().getPackageName();
                        if (taskPkg.equals(packageName)) {
                            task.finishAndRemoveTask();
                            Log.d(TAG, "� Layer 2: Removed task " + taskPkg);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Layer 2 error: " + e.getMessage());
        }

        // LAYER 3: KILL BACKGROUND PROCESSES
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses(packageName);
                    Log.d(TAG, "💀 Layer 3: Killed background processes");
                }
            } catch (Exception e) {
                Log.e(TAG, "Layer 3 error: " + e.getMessage());
            }

            // LAYER 4: FORCE STOP (for specific devices)
            if (isMIUI() || Build.MANUFACTURER.equalsIgnoreCase("samsung") ||
                    Build.MANUFACTURER.equalsIgnoreCase("oppo") ||
                    Build.MANUFACTURER.equalsIgnoreCase("vivo")) {
                try {
                    Runtime.getRuntime().exec("am force-stop " + packageName);
                    Log.d(TAG, "🛑 Layer 4: Force-stop command sent");
                } catch (Exception e) {
                    Log.e(TAG, "Layer 4 error: " + e.getMessage());
                }
            }

            // LAYER 5: REPEATED HOME ACTIONS (ensure we stay on home)
            performGlobalAction(GLOBAL_ACTION_HOME);
            Log.d(TAG, "🏠 Layer 5: Repeated home action");

        }, 200); // Faster blocking

        // LAYER 6: CONTINUOUS RE-BLOCKING (in case app reopens)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String currentApp = getCurrentForegroundAppFromUsageStats();
            if (currentApp != null && currentApp.equals(packageName)) {
                Log.w(TAG, "⚠️ Layer 6: App STILL RUNNING! Re-blocking...");
                performGlobalAction(GLOBAL_ACTION_HOME);
                performGlobalAction(GLOBAL_ACTION_HOME); // Double home

                // Try force stop again
                try {
                    ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    if (am != null) {
                        am.killBackgroundProcesses(packageName);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Re-block error: " + e.getMessage());
                }
            }
        }, 500); // Check again after 500ms

        // User feedback
        Toast.makeText(this, "🚫 BLOCKED: " + appName, Toast.LENGTH_SHORT).show();

        // Broadcast blocking event
        Intent blockingIntent = new Intent("online.monarchlabs.sentinel.APP_BLOCKED");
        blockingIntent.putExtra("package_name", packageName);
        blockingIntent.putExtra("app_name", appName);
        blockingIntent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(blockingIntent);

        Log.d(TAG, "✅ Multi-layer blocking complete for: " + appName);
    }

    private boolean shouldIgnoreForTimer(String packageName) {
        if (packageName == null)
            return true;

        // More comprehensive filtering for timer accuracy
        return packageName.equals("android") ||
                packageName.contains("launcher") ||
                packageName.contains("systemui") ||
                packageName.contains("system") ||
                packageName.startsWith("com.android.") ||
                packageName.contains("settings") ||
                packageName.contains("com.miui.") ||
                packageName.contains("com.google.android.gms") ||
                packageName.contains("com.google.android.permissioncontroller") ||
                packageName.contains("wallpaper") ||
                packageName.contains("keyboard") ||
                packageName.contains("inputmethod") ||
                packageName.equals("online.monarchlabs.sentinel") || // Don't track our own app
                isSystemApp(packageName); // Filter out all system apps for timer purposes
    }

    private boolean isSystemApp(String packageName) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(packageName, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isMIUI() {
        return "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER) ||
                Build.MODEL.toLowerCase().contains("redmi") ||
                Build.MODEL.toLowerCase().contains("poco") ||
                System.getProperty("ro.miui.ui.version.code") != null;
    }

    private String getAppName(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "BlockService interrupted");
    }

    @Override
    public void onDestroy() {
        if (pipUsageTracker != null) {
            pipUsageTracker.updateVisiblePackages(new HashSet<>());
        }
        if (pipUsageHandler != null && pipUsageRunnable != null) {
            pipUsageHandler.removeCallbacks(pipUsageRunnable);
        }
        super.onDestroy();
        Log.d(TAG, "💀 BlockService destroyed");

        // Stop accuracy monitoring
        if (accuracyHandler != null && accuracyRunnable != null) {
            accuracyHandler.removeCallbacks(accuracyRunnable);
        }
        if (onboardingHandler != null && onboardingRunnable != null) {
            onboardingHandler.removeCallbacks(onboardingRunnable);
        }

        // Stop sync monitoring
        if (syncHandler != null && syncRunnable != null) {
            syncHandler.removeCallbacks(syncRunnable);
        }

        // Unregister broadcast receiver
        if (blockedAppsReceiver != null) {
            try {
                unregisterReceiver(blockedAppsReceiver);
                Log.d(TAG, "📡 Broadcast receiver unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering broadcast receiver: " + e.getMessage());
            }
        }

        try {
            if (selfHealingConfigListener != null && sessionManager != null) {
                String deviceId = sessionManager.getChildDeviceId();
                if (deviceId != null && !deviceId.isEmpty()) {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                            .getReference("v2")
                            .child("client_capabilities")
                            .child(deviceId)
                            .child("self_healing_config")
                            .removeEventListener(selfHealingConfigListener);
                }
            }
        } catch (Exception ignored) {}
    }

    private void runDiagnostics() {
        Log.d(TAG, "=== BLOCKSERVICE DIAGNOSTICS ===");

        // Check blocked apps
        Map<String, ?> allPrefs = prefs.getAll();
        int blockedCount = 0;

        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            if (entry.getValue() instanceof Boolean && (Boolean) entry.getValue()) {
                String appName = getAppName(entry.getKey());
                Log.d(TAG, "BLOCKED APP: " + appName + " (" + entry.getKey() + ")");
                blockedCount++;
            }
        }

        Log.d(TAG, "Total blocked apps: " + blockedCount);

        if (blockedCount == 0) {
            Log.w(TAG, "WARNING: No apps are currently blocked!");
        }

        // Check service permissions
        try {
            Log.d(TAG, "Service running: " + (getApplicationContext() != null ? "YES" : "NO"));
            Log.d(TAG, "Accessibility connected: YES");
        } catch (Exception e) {
            Log.e(TAG, "Service check failed: " + e.getMessage());
        }

        Log.d(TAG, "=== END DIAGNOSTICS ===");
    }

    private com.google.firebase.database.ValueEventListener selfHealingConfigListener;

    private void setupSelfHealingConfigListener() {
        if (selfHealingConfigListener != null) {
            Log.d(TAG, "⚙️ Self-healing config listener already registered");
            return;
        }

        if (sessionManager == null) return;
        String deviceId = sessionManager.getChildDeviceId();
        if (deviceId == null || deviceId.isEmpty()) return;

        Log.d(TAG, "🛡️ Setting up Self-Healing Config Listener for device: " + deviceId);

        com.google.firebase.database.DatabaseReference configRef = com.google.firebase.database.FirebaseDatabase
                .getInstance()
                .getReference("v2")
                .child("client_capabilities")
                .child(deviceId)
                .child("self_healing_config");

        selfHealingConfigListener = new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        Boolean useSupervisor = snapshot.child("use_accessibility_supervisor").getValue(Boolean.class);
                        Boolean useLegacyWatchdog = snapshot.child("use_legacy_alarm_watchdog").getValue(Boolean.class);

                        SharedPreferences configPrefs = getSharedPreferences("self_healing_config", MODE_PRIVATE);
                        SharedPreferences.Editor editor = configPrefs.edit();

                        if (useSupervisor != null) {
                            editor.putBoolean("use_accessibility_supervisor", useSupervisor);
                            Log.d(TAG, "⚙️ Remote config: use_accessibility_supervisor = " + useSupervisor);
                        }
                        if (useLegacyWatchdog != null) {
                            editor.putBoolean("use_legacy_alarm_watchdog", useLegacyWatchdog);
                            Log.d(TAG, "⚙️ Remote config: use_legacy_alarm_watchdog = " + useLegacyWatchdog);

                            if (useLegacyWatchdog) {
                                Log.d(TAG, "⚙️ Re-scheduling legacy Watchdog Alarm loop");
                                ServiceWatchdog.schedulePeriodicChecks(BlockService.this);
                            } else {
                                Log.d(TAG, "⚙️ Cancelling legacy Watchdog Alarm loop");
                                ServiceWatchdog.cancelPeriodicChecks(BlockService.this);
                            }
                        }
                        editor.apply();
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing self_healing_config: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onCancelled(com.google.firebase.database.DatabaseError error) {
                Log.e(TAG, "self_healing_config listener cancelled: " + error.getMessage());
            }
        };

        configRef.addValueEventListener(selfHealingConfigListener);
    }



    private void verifyCoreServicesRunning(boolean immediate) {
        ChildServiceCoordinator.ensureCriticalService(this,
                immediate ? "accessibility_startup" : "accessibility_health");
        if (ChildServiceCoordinator.isManagingStartup(this)) {
            return;
        }

        try {
            // First check if the supervisor toggle is disabled (rollback configuration)
            SharedPreferences configPrefs = getSharedPreferences("self_healing_config", MODE_PRIVATE);
            boolean useSupervisor = configPrefs.getBoolean("use_accessibility_supervisor", true);
            if (!useSupervisor) {
                Log.d(TAG, "🛡️ Watchdog Supervisor disabled via self_healing_config toggle.");
                return;
            }

            // Check throttling
            long currentTime = System.currentTimeMillis();
            SharedPreferences healthPrefs = getSharedPreferences("health_state", MODE_PRIVATE);

            // If not immediate check, enforce the 5-minute throttle
            if (!immediate) {
                boolean needsRecovery = healthPrefs.getBoolean("needs_recovery", false);
                if (needsRecovery) {
                    healthPrefs.edit().putBoolean("needs_recovery", false).apply();
                } else {
                    long lastCheck = healthPrefs.getLong("last_health_check_time", 0);
                    if ((currentTime - lastCheck) < 5 * 60 * 1000L) {
                        return; // Within 5-minute throttle, skip check
                    }
                }
            }

            // Record the check time
            healthPrefs.edit().putLong("last_health_check_time", currentTime).apply();

            Log.d(TAG, "🔍 Throttled Watchdog Supervisor checking services...");

            // Check and recover RemoteBlockService
            if (!isServiceRunning(RemoteBlockService.class)) {
                if (ServiceRecoveryLimiter.canRestart(this, "RemoteBlockService")) {
                    Log.w(TAG, "⚠️ RemoteBlockService is DEAD - restarting!");
                    startServiceSafely(RemoteBlockService.class, "RemoteBlockService");
                }
            }

            // Check and recover AppTimerService
            if (!isServiceRunning(online.monarchlabs.sentinel.services.AppTimerService.class)) {
                if (ServiceRecoveryLimiter.canRestart(this, "AppTimerService")) {
                    Log.w(TAG, "⚠️ AppTimerService is DEAD - restarting!");
                    String childDeviceId = sessionManager.getChildDeviceId();
                    if (childDeviceId != null && !childDeviceId.isEmpty()) {
                        online.monarchlabs.sentinel.services.AppTimerService.start(this, childDeviceId);
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in verifyCoreServicesRunning: " + e.getMessage());
        }
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking service running: " + e.getMessage());
        }
        return false;
    }

    private void startServiceSafely(Class<?> serviceClass, String serviceName) {
        try {
            Intent intent = new Intent(this, serviceClass);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(intent);
                    Log.d(TAG, "✅ Started " + serviceName + " via startForegroundService");
                } catch (Exception e) {
                    Log.w(TAG, "⚠️ startForegroundService failed: " + e.getMessage());
                }
            } else {
                startService(intent);
                Log.d(TAG, "✅ Started " + serviceName + " via startService");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start service " + serviceName + ": " + e.getMessage());
        }
    }

    // BLOCK_TIMER_MIGRATION: Parallel AppTimer data class definition
    public static class AppTimer {
        public String packageName;
        public String key;
        public long remainingTimeMillis;
        public long dailyLimitMillis;      // Budget the parent set (e.g. 2h30m)
        public long usageAtSetMillis = -1; // Actual usage AT the moment timer was created;
                                           // -1 = not yet measured by this service instance
        public boolean active;
        public boolean expiryNotified = false; // true after first expiry notification sent
        public long lastSyncTime = 0;
        public long lastReconcileTime = 0;

        // For 5-minute re-notification cadence tracking (while child actively uses expired app)
        public long accumulatedActiveMs = 0;
        public long lastNotifIntervalCount = 0;
    }

    private void showBlockingOverlay() {
        if (windowManager == null) return;

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (blockingOverlay == null) {
                    blockingOverlay = new android.widget.FrameLayout(this);
                    blockingOverlay.setBackgroundColor(android.graphics.Color.parseColor("#E6000000")); // Semi-transparent black

                    android.widget.TextView tv = new android.widget.TextView(this);
                    tv.setText("App Blocked");
                    tv.setTextColor(android.graphics.Color.WHITE);
                    tv.setTextSize(24);
                    tv.setGravity(android.view.Gravity.CENTER);
                    ((android.widget.FrameLayout)blockingOverlay).addView(tv);

                    android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            android.view.WindowManager.LayoutParams.MATCH_PARENT,
                            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            android.graphics.PixelFormat.TRANSLUCENT);

                    windowManager.addView(blockingOverlay, params);
                }

                // Hide after a few seconds so it doesn't permanently brick the phone if they go home
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::hideBlockingOverlay, 2000);
            } catch (Exception e) {
                Log.e(TAG, "Error showing overlay: " + e.getMessage());
            }
        });
    }

    private void hideBlockingOverlay() {
        if (blockingOverlay != null && windowManager != null) {
            try {
                windowManager.removeView(blockingOverlay);
            } catch (Exception e) {}
            blockingOverlay = null;
        }
    }

    /**
     * Returns true if {@code packageName} should be blocked right now — either
     * because the parent manually blocked it (via the "blocked_apps" SharedPreferences)
     * OR because its daily timer has expired (state read from AppTimerLocalStore).
     *
     * This is the single gate used by every foreground / window / event check so
     * that adding a new blocking mechanism only requires touching this one method.
     */
    private boolean isManuallyBlockedOrExpired(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        // 1. Manual block via SharedPreferences
        SharedPreferences freshPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
        if (freshPrefs.getBoolean(packageName, false)) {
            return true;
        }
        // 2. Timer-expired block via AppTimerLocalStore (populated by AppTimerService)
        return isTimerExpired(packageName);
    }

    /**
     * Reads the cached timer execution state written by {@link online.monarchlabs.sentinel.services.AppTimerService}
     * and returns true when the package's daily timer has expired.
     *
     * Because both services share the same app process and the same
     * {@link AppTimerLocalStore} SharedPreferences, no IPC or network call is
     * needed — the check is instant.
     */
    private boolean isTimerExpired(String packageName) {
        if (isParentDevice) return false;
        String childId = sessionManager != null ? sessionManager.getChildDeviceId() : null;
        if (childId == null || childId.isEmpty()) return false;
        try {
            java.util.List<AppTimerLocalStore.TimerRecord> records =
                    AppTimerLocalStore.load(this, childId);
            for (AppTimerLocalStore.TimerRecord record : records) {
                if (packageName.equals(record.packageName)) {
                    // Expired when:
                    //   a) the dedicated boolean flag is set, OR
                    //   b) state is "EXPIRED", OR
                    //   c) timer is active but budget is exhausted
                    return record.expired
                            || "EXPIRED".equalsIgnoreCase(record.state)
                            || (record.active && record.remainingTimeMillis <= 0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isTimerExpired: error reading local store: " + e.getMessage());
        }
        return false;
    }
}
