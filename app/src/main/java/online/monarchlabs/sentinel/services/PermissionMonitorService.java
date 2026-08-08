package online.monarchlabs.sentinel.services;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import online.monarchlabs.sentinel.BatteryOptimizationManager;
import online.monarchlabs.sentinel.BlockService;
import online.monarchlabs.sentinel.R;
import online.monarchlabs.sentinel.SessionManager;
import online.monarchlabs.sentinel.models.PermissionEvent;
import online.monarchlabs.sentinel.utils.CrashlyticsLogger;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Foreground service that monitors permission changes on child device
 * and reports them to the parent via Firebase.
 */
public class PermissionMonitorService extends Service {
    private static final String TAG = "PermissionMonitor";
    public static final String ACTION_CHECK_NOW =
            "online.monarchlabs.sentinel.action.CHECK_PERMISSIONS_NOW";
    private static final String CHANNEL_ID = "permission_monitor_channel";
    private static final int NOTIFICATION_ID = 9001;
    private static final int CHECK_INTERVAL_MS = 30000; // 30 seconds
    private static final long HEALTH_CHECKPOINT_MS = 8 * 60 * 60 * 1000L;
    private static final long RETRY_BASE_MS = 30_000L;
    private static final long RETRY_MAX_MS = 30 * 60 * 1000L;

    private static final String PREF_NAME = "PermissionStatus";
    private static final String KEY_ACCESSIBILITY = "perm_accessibility";
    private static final String KEY_USAGE_STATS = "perm_usage_stats";
    private static final String KEY_NOTIFICATIONS = "perm_notifications";
    private static final String KEY_BATTERY_OPT = "perm_battery_opt";
    private static final String KEY_HAS_WRITTEN_SNAPSHOT = "firebase_has_written_snapshot";
    private static final String KEY_WRITTEN_ACCESSIBILITY = "firebase_accessibility";
    private static final String KEY_WRITTEN_USAGE_STATS = "firebase_usage_stats";
    private static final String KEY_WRITTEN_NOTIFICATIONS = "firebase_notifications";
    private static final String KEY_WRITTEN_BATTERY_OPT = "firebase_battery_opt";
    private static final String KEY_LAST_SUCCESSFUL_WRITE_AT = "firebase_last_successful_write_at";
    private static final String KEY_LAST_CHANGED_AT = "firebase_last_changed_at";
    private static final String KEY_PENDING_WRITE = "firebase_pending_write";
    private static final String KEY_PENDING_ACCESSIBILITY = "firebase_pending_accessibility";
    private static final String KEY_PENDING_USAGE_STATS = "firebase_pending_usage_stats";
    private static final String KEY_PENDING_NOTIFICATIONS = "firebase_pending_notifications";
    private static final String KEY_PENDING_BATTERY_OPT = "firebase_pending_battery_opt";
    private static final String KEY_PENDING_SIGNATURE = "firebase_pending_signature";
    private static final String KEY_RETRY_COUNT = "firebase_retry_count";
    private static final String KEY_NEXT_RETRY_AT = "firebase_next_retry_at";

    private Handler handler;
    private Runnable checkRunnable;
    private SharedPreferences prefs;
    private SessionManager sessionManager;
    private BatteryOptimizationManager batteryOptimizationManager;
    private DatabaseReference databaseRef;
    private DatabaseReference rootRef;
    private boolean monitoringStarted;
    private boolean writeInFlight;
    private boolean immediateCheckPending;

    // Permission effect descriptions
    private static final Map<String, String[]> PERMISSION_EFFECTS = new HashMap<>();
    static {
        // [0] = Deactivated effect, [1] = Activated effect
        PERMISSION_EFFECTS.put("Accessibility Service", new String[] {
                "App blocking disabled. Child can bypass restrictions and open any app.",
                "App blocking enabled. Focus mode and restrictions are active."
        });
        PERMISSION_EFFECTS.put("Usage Stats", new String[] {
                "Usage tracking stopped. Daily reports will be incomplete or unavailable.",
                "Usage tracking active. Full usage reports are available for monitoring."
        });
        PERMISSION_EFFECTS.put("Notifications", new String[] {
                "Timer alerts will not display. Child won't see time limit warnings.",
                "Timer alerts enabled. Child will see warnings before limits are reached."
        });
        PERMISSION_EFFECTS.put("Battery Optimization", new String[] {
                "Background services may stop. Timer resets and monitoring may fail.",
                "Services protected from battery optimization. Reliable operation ensured."
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🔔 PermissionMonitorService STARTING...");

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sessionManager = new SessionManager(this);
        batteryOptimizationManager = new BatteryOptimizationManager(this);
        handler = new Handler(Looper.getMainLooper());
        rootRef = FirebaseDatabase.getInstance().getReference();

        String childDeviceId = sessionManager.getChildDeviceId();
        createNotificationChannel();
        if (!tryStartForeground()) {
            stopSelf();
            return;
        }
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.e(TAG, "No child device ID; permission monitoring stopped");
            stopSelf();
            return;
        }
        setupDatabaseReference(childDeviceId);
    }

    private boolean tryStartForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Foreground start rejected; stopping service to avoid crash: " + e.getMessage());
            CrashlyticsLogger.recordForegroundServiceRejected(
                    TAG,
                    "specialUse:permission_monitoring",
                    e
            );
            return false;
        }
    }

    /**
     * Setup database reference and start monitoring
     */
    private void setupDatabaseReference(String childDeviceId) {
        databaseRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("permission_logs")
                .child(childDeviceId);
        Log.d(TAG, "Permission events: v2/permission_logs/" + childDeviceId);
        startPermissionMonitoring();
    }

    /**
     * Start monitoring after Firebase is ready
     */
    private void startPermissionMonitoring() {
        if (monitoringStarted) {
            Log.d(TAG, "Permission monitoring already started; ignoring duplicate setup");
            return;
        }
        monitoringStarted = true;
        Log.d(TAG, "🔄 Forcing fresh permission state...");
        initializePermissionStates();

        handler.postDelayed(() -> {
            Log.d(TAG, "🔔 IMMEDIATE permission check...");
            checkPermissionsAndReport();
            immediateCheckPending = false;
        }, 500);

        startMonitoring();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Permission Monitor",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Monitors permission status for parental control");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control Active")
                .setContentText("Monitoring is enabled")
                .setSmallIcon(R.drawable.ic_child)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void initializePermissionStates() {
        // Only initialize if not already set (first run)
        if (!prefs.contains(KEY_ACCESSIBILITY)) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(KEY_ACCESSIBILITY, isAccessibilityServiceEnabled());
            editor.putBoolean(KEY_USAGE_STATS, hasUsageStatsPermission());
            editor.putBoolean(KEY_NOTIFICATIONS, hasNotificationPermission());
            editor.putBoolean(KEY_BATTERY_OPT, batteryOptimizationManager.isBatteryOptimizationDisabled());
            editor.apply();
            Log.d(TAG, "Initialized permission states");
        }
    }

    /**
     * 🔧 FIX: Force capture current permission states (always overwrites)
     * This ensures we have accurate baseline when service starts
     */
    private void initializePermissionStatesForced() {
        boolean accessibility = isAccessibilityServiceEnabled();
        boolean usageStats = hasUsageStatsPermission();
        boolean notifications = hasNotificationPermission();
        boolean batteryOpt = batteryOptimizationManager.isBatteryOptimizationDisabled();

        Log.d(TAG, "📊 Current permission states:");
        Log.d(TAG, "   Accessibility: " + accessibility);
        Log.d(TAG, "   Usage Stats: " + usageStats);
        Log.d(TAG, "   Notifications: " + notifications);
        Log.d(TAG, "   Battery Opt: " + batteryOpt);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_ACCESSIBILITY, accessibility);
        editor.putBoolean(KEY_USAGE_STATS, usageStats);
        editor.putBoolean(KEY_NOTIFICATIONS, notifications);
        editor.putBoolean(KEY_BATTERY_OPT, batteryOpt);
        editor.apply();

        Log.d(TAG, "✅ Permission states saved to prefs");

        // 🔧 FIX: Also immediately update Firebase with current status
        if (databaseRef != null) {
            updateCurrentStatus(accessibility, usageStats, notifications, batteryOpt);
            Log.d(TAG, "📤 Initial status synced to Firebase");
        }
    }

    private void startMonitoring() {
        if (checkRunnable != null) {
            handler.removeCallbacks(checkRunnable);
        }
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                checkPermissionsAndReport();
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS);
        Log.d(TAG, "Started permission monitoring (interval: " + CHECK_INTERVAL_MS + "ms)");
    }

    private void checkPermissionsAndReport() {
        Log.d(TAG, "🔍 Checking permissions...");

        if (databaseRef == null) {
            Log.e(TAG, "❌ Firebase not initialized, cannot report changes!");
            return;
        }

        // Current states
        boolean currAccessibility = isAccessibilityServiceEnabled();
        boolean currUsageStats = hasUsageStatsPermission();
        boolean currNotifications = hasNotificationPermission();
        boolean currBatteryOpt = batteryOptimizationManager.isBatteryOptimizationDisabled();

        // Previous states
        boolean prevAccessibility = prefs.getBoolean(KEY_ACCESSIBILITY, false);
        boolean prevUsageStats = prefs.getBoolean(KEY_USAGE_STATS, false);
        boolean prevNotifications = prefs.getBoolean(KEY_NOTIFICATIONS, false);
        boolean prevBatteryOpt = prefs.getBoolean(KEY_BATTERY_OPT, false);

        // 🔧 FIX: Add verbose comparison logging
        Log.d(TAG, "📊 Permission comparison:");
        Log.d(TAG, "   Accessibility: CURRENT=" + currAccessibility + " vs SAVED=" + prevAccessibility +
                (currAccessibility != prevAccessibility ? " ⚠️ CHANGED!" : ""));
        Log.d(TAG, "   Usage Stats: CURRENT=" + currUsageStats + " vs SAVED=" + prevUsageStats +
                (currUsageStats != prevUsageStats ? " ⚠️ CHANGED!" : ""));
        Log.d(TAG, "   Notifications: CURRENT=" + currNotifications + " vs SAVED=" + prevNotifications +
                (currNotifications != prevNotifications ? " ⚠️ CHANGED!" : ""));
        Log.d(TAG, "   Battery Opt: CURRENT=" + currBatteryOpt + " vs SAVED=" + prevBatteryOpt +
                (currBatteryOpt != prevBatteryOpt ? " ⚠️ CHANGED!" : ""));

        // Detect and report changes
        if (currAccessibility != prevAccessibility) {
            reportPermissionChange("Accessibility Service", currAccessibility);
            prefs.edit().putBoolean(KEY_ACCESSIBILITY, currAccessibility).apply();
        }
        if (currUsageStats != prevUsageStats) {
            reportPermissionChange("Usage Stats", currUsageStats);
            prefs.edit().putBoolean(KEY_USAGE_STATS, currUsageStats).apply();
        }
        if (currNotifications != prevNotifications) {
            reportPermissionChange("Notifications", currNotifications);
            prefs.edit().putBoolean(KEY_NOTIFICATIONS, currNotifications).apply();
        }
        if (currBatteryOpt != prevBatteryOpt) {
            reportPermissionChange("Battery Optimization", currBatteryOpt);
            prefs.edit().putBoolean(KEY_BATTERY_OPT, currBatteryOpt).apply();
        }

        boolean permissionChanged = currAccessibility != prevAccessibility
                || currUsageStats != prevUsageStats
                || currNotifications != prevNotifications
                || currBatteryOpt != prevBatteryOpt;
        if (permissionChanged) {
            prefs.edit().putLong(KEY_LAST_CHANGED_AT, System.currentTimeMillis()).apply();
        }

        maybeWriteCurrentStatus(
                currAccessibility,
                currUsageStats,
                currNotifications,
                currBatteryOpt,
                permissionChanged);
    }

    private void reportPermissionChange(String permissionName, boolean isEnabled) {
        String action = isEnabled ? "ACTIVATED" : "DEACTIVATED";
        String[] effects = PERMISSION_EFFECTS.get(permissionName);
        String effect = (effects != null) ? (isEnabled ? effects[1] : effects[0]) : "Unknown effect";

        long timestamp = System.currentTimeMillis();
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        Date date = new Date(timestamp);

        PermissionEvent event = new PermissionEvent(
                permissionName,
                action,
                effect,
                timestamp,
                dateFormat.format(date),
                timeFormat.format(date));

        DatabaseReference eventRef = databaseRef.push();
        String childDeviceId = sessionManager.getChildDeviceId();
        if (childDeviceId != null && eventRef.getKey() != null) {
            Map<String, Object> eventUpdates = new HashMap<>();
            eventUpdates.put("v2/permission_logs/" + childDeviceId + "/"
                    + eventRef.getKey(), event);
            rootRef.updateChildren(eventUpdates)
                    .addOnSuccessListener(ignored ->
                            Log.d(TAG, "Reported permission event atomically: "
                                    + permissionName + " " + action))
                    .addOnFailureListener(error ->
                            Log.e(TAG, "Failed to report permission event: "
                                    + error.getMessage()));
        } else {
            eventRef.setValue(event)
                    .addOnFailureListener(error ->
                            Log.e(TAG, "Failed to report permission event: "
                                    + error.getMessage()));
        }

        Log.i(TAG, "🔔 Permission change detected: " + permissionName + " -> " + action);
    }

    private void updateCurrentStatus(boolean accessibility, boolean usageStats,
            boolean notifications, boolean batteryOpt) {
        maybeWriteCurrentStatus(
                accessibility, usageStats, notifications, batteryOpt, true);
    }

    private void maybeWriteCurrentStatus(boolean accessibility, boolean usageStats,
            boolean notifications, boolean batteryOpt, boolean permissionChanged) {
        String childDeviceId = sessionManager.getChildDeviceId();
        if (rootRef == null
                || childDeviceId == null || childDeviceId.isEmpty()) {
            Log.w(TAG, "Permission snapshot skipped: Firebase path is not ready");
            return;
        }

        long now = System.currentTimeMillis();
        boolean hasWrittenSnapshot = prefs.getBoolean(KEY_HAS_WRITTEN_SNAPSHOT, false);
        boolean differsFromSuccessful = !hasWrittenSnapshot
                || accessibility != prefs.getBoolean(KEY_WRITTEN_ACCESSIBILITY, false)
                || usageStats != prefs.getBoolean(KEY_WRITTEN_USAGE_STATS, false)
                || notifications != prefs.getBoolean(KEY_WRITTEN_NOTIFICATIONS, false)
                || batteryOpt != prefs.getBoolean(KEY_WRITTEN_BATTERY_OPT, false);
        boolean pendingRetry = prefs.getBoolean(KEY_PENDING_WRITE, false);
        long lastSuccessfulWriteAt = prefs.getLong(KEY_LAST_SUCCESSFUL_WRITE_AT, 0L);
        boolean checkpointDue = hasWrittenSnapshot
                && now - lastSuccessfulWriteAt >= HEALTH_CHECKPOINT_MS;

        if (!differsFromSuccessful && !pendingRetry && !checkpointDue) {
            Log.d(TAG, "Permission snapshot skipped because unchanged");
            return;
        }

        String signature = buildStatusSignature(
                accessibility, usageStats, notifications, batteryOpt);
        String pendingSignature = prefs.getString(KEY_PENDING_SIGNATURE, "");
        boolean retryingSameSnapshot = pendingRetry
                && signature.equals(pendingSignature);
        if (differsFromSuccessful || permissionChanged) {
            persistPendingSnapshot(
                    accessibility, usageStats, notifications, batteryOpt, signature);
        }

        if (writeInFlight) {
            persistPendingSnapshot(
                    accessibility, usageStats, notifications, batteryOpt, signature);
            Log.d(TAG, "Permission snapshot coalesced while write is in flight");
            return;
        }

        if (retryingSameSnapshot) {
            long nextRetryAt = prefs.getLong(KEY_NEXT_RETRY_AT, 0L);
            if (now < nextRetryAt) {
                Log.d(TAG, "Permission snapshot retry waiting for backoff");
                return;
            }
        }

        String reason;
        if (retryingSameSnapshot) {
            reason = "retry after failed write";
        } else if (permissionChanged || differsFromSuccessful) {
            reason = hasWrittenSnapshot ? "permission changed" : "initial snapshot";
        } else {
            reason = "health checkpoint due";
        }

        writePermissionSnapshot(
                childDeviceId,
                accessibility,
                usageStats,
                notifications,
                batteryOpt,
                signature,
                reason,
                now);
    }

    private void writePermissionSnapshot(String childDeviceId,
            boolean accessibility, boolean usageStats,
            boolean notifications, boolean batteryOpt,
            String signature, String reason, long checkedAt) {
        writeInFlight = true;

        Map<String, Object> status = new HashMap<>();
        status.put("accessibility", accessibility);
        status.put("usageStats", usageStats);
        status.put("notifications", notifications);
        status.put("batteryOptimization", batteryOpt);
        status.put("lastChangedAt", prefs.getLong(KEY_LAST_CHANGED_AT, checkedAt));
        status.put("lastCheckedAt", checkedAt);
        status.put("lastHeartbeatAt", checkedAt);
        status.put("lastUpdated", checkedAt);

        Map<String, Object> updates = new HashMap<>();
        updates.put("v2/permissions_current/" + childDeviceId, status);

        Log.d(TAG, "Writing permission snapshot: " + reason);
        rootRef.updateChildren(updates)
                .addOnSuccessListener(ignored -> {
                    writeInFlight = false;
                    SharedPreferences.Editor editor = prefs.edit()
                            .putBoolean(KEY_HAS_WRITTEN_SNAPSHOT, true)
                            .putBoolean(KEY_WRITTEN_ACCESSIBILITY, accessibility)
                            .putBoolean(KEY_WRITTEN_USAGE_STATS, usageStats)
                            .putBoolean(KEY_WRITTEN_NOTIFICATIONS, notifications)
                            .putBoolean(KEY_WRITTEN_BATTERY_OPT, batteryOpt)
                            .putLong(KEY_LAST_SUCCESSFUL_WRITE_AT, checkedAt)
                            .putInt(KEY_RETRY_COUNT, 0)
                            .putLong(KEY_NEXT_RETRY_AT, 0L);

                    String pendingSignature = prefs.getString(KEY_PENDING_SIGNATURE, "");
                    if (signature.equals(pendingSignature)) {
                        editor.putBoolean(KEY_PENDING_WRITE, false)
                                .remove(KEY_PENDING_SIGNATURE)
                                .remove(KEY_PENDING_ACCESSIBILITY)
                                .remove(KEY_PENDING_USAGE_STATS)
                                .remove(KEY_PENDING_NOTIFICATIONS)
                                .remove(KEY_PENDING_BATTERY_OPT);
                    }
                    editor.apply();
                    Log.d(TAG, "Permission snapshot write succeeded: " + reason);
                })
                .addOnFailureListener(error -> {
                    writeInFlight = false;
                    int retryCount = Math.min(
                            prefs.getInt(KEY_RETRY_COUNT, 0) + 1, 10);
                    long retryDelay = Math.min(
                            RETRY_MAX_MS,
                            RETRY_BASE_MS * (1L << Math.min(retryCount - 1, 6)));
                    persistPendingSnapshot(
                            accessibility, usageStats, notifications, batteryOpt, signature);
                    prefs.edit()
                            .putInt(KEY_RETRY_COUNT, retryCount)
                            .putLong(KEY_NEXT_RETRY_AT,
                                    System.currentTimeMillis() + retryDelay)
                            .apply();
                    Log.w(TAG, "Permission snapshot write failed; retry scheduled: "
                            + error.getMessage());
                });
    }

    private void persistPendingSnapshot(boolean accessibility, boolean usageStats,
            boolean notifications, boolean batteryOpt, String signature) {
        prefs.edit()
                .putBoolean(KEY_PENDING_WRITE, true)
                .putBoolean(KEY_PENDING_ACCESSIBILITY, accessibility)
                .putBoolean(KEY_PENDING_USAGE_STATS, usageStats)
                .putBoolean(KEY_PENDING_NOTIFICATIONS, notifications)
                .putBoolean(KEY_PENDING_BATTERY_OPT, batteryOpt)
                .putString(KEY_PENDING_SIGNATURE, signature)
                .apply();
    }

    private String buildStatusSignature(boolean accessibility, boolean usageStats,
            boolean notifications, boolean batteryOpt) {
        return (accessibility ? "1" : "0")
                + (usageStats ? "1" : "0")
                + (notifications ? "1" : "0")
                + (batteryOpt ? "1" : "0");
    }

    private String relativePath(DatabaseReference reference) {
        String rootUrl = rootRef.toString();
        String referenceUrl = reference.toString();
        if (referenceUrl.startsWith(rootUrl)) {
            String relative = referenceUrl.substring(rootUrl.length());
            return relative.startsWith("/") ? relative.substring(1) : relative;
        }
        return reference.getKey();
    }

    // Permission checking methods (same as ChildPermissionsActivity)
    private boolean isAccessibilityServiceEnabled() {
        String settingValue = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (settingValue != null) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                ComponentName enabledComponent =
                        ComponentName.unflattenFromString(splitter.next());
                if (enabledComponent != null
                        && enabledComponent.getPackageName().equals(getPackageName())
                        && enabledComponent.getClassName().equals(BlockService.class.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats",
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CHECK_NOW.equals(intent.getAction())) {
            immediateCheckPending = true;
            if (monitoringStarted && databaseRef != null && handler != null) {
                handler.post(() -> {
                    Log.d(TAG, "Running requested immediate permission check");
                    checkPermissionsAndReport();
                    immediateCheckPending = false;
                });
            }
        }
        return START_STICKY;
    }

    public static void requestImmediateCheck(Context context) {
        Intent intent = new Intent(context, PermissionMonitorService.class);
        intent.setAction(ACTION_CHECK_NOW);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not request immediate permission check: "
                    + error.getMessage());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && checkRunnable != null) {
            handler.removeCallbacks(checkRunnable);
        }
        Log.d(TAG, "PermissionMonitorService destroyed");
    }
}
