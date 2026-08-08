package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.services.AppTimerService;
import online.monarchlabs.sentinel.services.PackageChangeService;
import online.monarchlabs.sentinel.services.PermissionMonitorService;
import online.monarchlabs.sentinel.services.PersistentConnectionService;
import online.monarchlabs.sentinel.utils.InstalledAppsManager;

/**
 * Starts child services in controlled phases so Firebase listeners, foreground
 * services, usage queries, and app scans do not all compete during dashboard startup.
 */
public final class ChildServiceCoordinator {
    private static final String TAG = "ChildServiceCoordinator";
    private static final String PREFS = "child_service_coordinator";
    private static final String ONBOARDING_PREFS = "child_onboarding_state";
    private static final long START_DEBOUNCE_MS = 10 * 60_000L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static boolean phasesScheduled;
    private static long lastStartRequestTime;
    private static String scheduledSessionKey;
    private static String capabilityPublishSessionKey;

    private ChildServiceCoordinator() {
    }

    public static void start(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        SessionManager session = new SessionManager(appContext);
        if (!isReady(appContext, session)) {
            Log.d(TAG, "Skipping startup; child session is not ready. Reason=" + reason);
            return;
        }

        String sessionKey = getSessionKey(session);
        publishAppLimitsCapabilityIfNeeded(
                appContext, session.getChildDeviceId(), sessionKey);

        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            SharedPreferences prefs = appContext.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            long persistedLastStart = prefs.getLong("last_completed_start", 0L);
            String persistedSessionKey = prefs.getString(
                    "last_completed_session_key", "");
            String requestedSessionKey = prefs.getString(
                    "last_start_session_key", "");
            boolean sameScheduledSession = phasesScheduled
                    && sessionKey.equals(scheduledSessionKey);
            boolean sameRecentRequest = sessionKey.equals(requestedSessionKey)
                    && now - lastStartRequestTime < START_DEBOUNCE_MS;
            boolean sameRecentCompletion = sessionKey.equals(persistedSessionKey)
                    && now - persistedLastStart < START_DEBOUNCE_MS;
            if (sameScheduledSession || sameRecentRequest || sameRecentCompletion) {
                Log.d(TAG, "Ignoring duplicate startup request. Reason=" + reason);
                ensureCriticalService(appContext, "duplicate_" + reason);
                return;
            }

            if (phasesScheduled) {
                MAIN_HANDLER.removeCallbacksAndMessages(null);
            }
            phasesScheduled = true;
            scheduledSessionKey = sessionKey;
            lastStartRequestTime = now;
            prefs.edit()
                    .putLong("last_start_request", now)
                    .putString("last_start_session_key", sessionKey)
                    .putString("last_reason", reason)
                    .apply();
        }

        runPhase(appContext, sessionKey, 0L, "remote_commands",
                () -> startForegroundService(appContext, RemoteBlockService.class));
        runPhase(appContext, sessionKey, 2_000L, "connection",
                () -> PersistentConnectionService.startService(appContext));
        runPhase(appContext, sessionKey, 6_000L, "permission_health",
                () -> startForegroundService(appContext, PermissionMonitorService.class));
        runPhase(appContext, sessionKey, 10_000L, "package_monitor",
                () -> startRegularService(appContext, PackageChangeService.class));
        runPhase(appContext, sessionKey, 14_000L, "timer_monitor", () -> {
            String deviceId = new SessionManager(appContext).getChildDeviceId();
            if (deviceId != null && !deviceId.isEmpty()) {
                AppTimerService.start(appContext, deviceId);
            }
        });
        runPhase(appContext, sessionKey, 16_000L, "installed_apps_sync", () -> {
            String deviceId = new SessionManager(appContext).getChildDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                return;
            }

            InstalledAppsManager.getInstance(appContext)
                    .syncInstalledApps(deviceId, new InstalledAppsManager.OnSyncCompleteListener() {
                        @Override
                        public void onSuccess(int appCount) {
                            Log.d(TAG, "Installed-app sync completed with " + appCount + " apps");
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Installed-app sync failed: " + error);
                        }
                    });
        });
        runPhase(appContext, sessionKey, 18_000L, "daily_reset",
                () -> startRegularService(appContext, DailyTimerResetService.class));
        runPhase(appContext, sessionKey, 20_000L, "background_work", () ->
                online.monarchlabs.sentinel.workers.UsageUploadScheduler
                        .schedulePeriodicUpload(appContext));
        runPhase(appContext, sessionKey, 24_000L, "watchdog", () -> {
            ServiceWatchdog.schedulePeriodicChecks(appContext);
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_completed_start", System.currentTimeMillis())
                    .putString("last_completed_session_key", sessionKey)
                    .apply();
        });
    }

    private static void publishAppLimitsCapabilityIfNeeded(
            Context context, String deviceId, String sessionKey) {
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String schemaKey = "app_limits_devices_schema_" + deviceId;
        String sessionCacheKey = "app_limits_session_" + deviceId;
        if (sessionKey.equals(prefs.getString(sessionCacheKey, ""))
                && prefs.getInt(schemaKey, 0)
                >= FirebaseSchemaV2Repository.APP_LIMITS_SCHEMA_VERSION) {
            return;
        }
        synchronized (LOCK) {
            if (sessionKey.equals(capabilityPublishSessionKey)) {
                return;
            }
            capabilityPublishSessionKey = sessionKey;
        }
        FirebaseSchemaV2Repository.publishAppLimitsCapability(deviceId)
                .addOnSuccessListener(ignored -> {
                    SessionManager currentSession = new SessionManager(context);
                    if (!sessionKey.equals(getSessionKey(currentSession))) {
                        return;
                    }
                    prefs.edit()
                            .putInt(schemaKey,
                                    FirebaseSchemaV2Repository.APP_LIMITS_SCHEMA_VERSION)
                            .putString(sessionCacheKey, sessionKey)
                            .apply();
                })
                .addOnFailureListener(error ->
                        Log.w(TAG, "App Limits capability publish deferred: "
                                + error.getMessage()))
                .addOnCompleteListener(ignored -> {
                    synchronized (LOCK) {
                        if (sessionKey.equals(capabilityPublishSessionKey)) {
                            capabilityPublishSessionKey = null;
                        }
                    }
                });
    }

    public static void stopForDisconnect(Context context) {
        String deviceId = new SessionManager(
                context.getApplicationContext()).getChildDeviceId();
        stopForDisconnect(context, deviceId);
    }

    public static void stopForDisconnect(Context context, String deviceId) {
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            MAIN_HANDLER.removeCallbacksAndMessages(null);
            phasesScheduled = false;
            lastStartRequestTime = 0L;
            scheduledSessionKey = null;
            capabilityPublishSessionKey = null;
        }

        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();

        appContext.stopService(new Intent(appContext, RemoteBlockService.class));
        AppTimerService.stop(appContext);
        appContext.stopService(new Intent(appContext, PackageChangeService.class));
        appContext.stopService(new Intent(appContext, PermissionMonitorService.class));
        appContext.stopService(new Intent(appContext, DailyTimerResetService.class));
        PersistentConnectionService.stopService(appContext);
        ChildConnectionDataCleaner.clearForDisconnect(appContext, deviceId);
        Log.d(TAG, "Stopped child services and cleared connection-scoped state");
    }
    public static void ensureCriticalService(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        SessionManager session = new SessionManager(appContext);
        if (!isReady(appContext, session)) {
            return;
        }

        boolean remoteBlockRunning =
                ServiceWatchdog.isServiceRunning(appContext, RemoteBlockService.class);
        boolean appTimerRunning =
                ServiceWatchdog.isServiceRunning(appContext, AppTimerService.class);
        boolean packageMonitorRunning =
                ServiceWatchdog.isServiceRunning(appContext, PackageChangeService.class);

        if (!remoteBlockRunning
                && ServiceRecoveryLimiter.canRestart(appContext, "RemoteBlockService")) {
            startForegroundService(appContext, RemoteBlockService.class);
            Log.d(TAG, "Recovered RemoteBlockService. Reason=" + reason);
        }

        if (!appTimerRunning
                && ServiceRecoveryLimiter.canRestart(appContext, "AppTimerService")) {
            String deviceId = session.getChildDeviceId();
            AppTimerService.start(appContext, deviceId);
            Log.d(TAG, "Recovered AppTimerService. Reason=" + reason);
        }

        if (!packageMonitorRunning
                && ServiceRecoveryLimiter.canRestart(appContext, "PackageChangeService")) {
            startRegularService(appContext, PackageChangeService.class);
            Log.d(TAG, "Recovered PackageChangeService. Reason=" + reason);
        }

        if (!remoteBlockRunning || !appTimerRunning || !packageMonitorRunning) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("last_critical_recovery", System.currentTimeMillis())
                    .putString("last_recovery_reason", reason)
                    .apply();
        }
    }

    public static boolean isManagingStartup(Context context) {
        // The coordinator is the sole startup owner, including while onboarding
        // intentionally prevents any services from being started.
        return context != null;
    }

    private static boolean isReady(Context context, SessionManager session) {
        boolean onboarding = context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
                .getBoolean("permission_setup_active", false);
        return !onboarding
                && session.isLoggedIn()
                && "child".equals(session.getUserType())
                && session.getChildDeviceId() != null
                && !session.getChildDeviceId().isEmpty();
    }

    private static String getSessionKey(SessionManager session) {
        String connectionId = session.getConnectionId();
        if (connectionId != null && !connectionId.trim().isEmpty()) {
            return connectionId;
        }
        return session.getChildDeviceId() + ":" + session.getConnectionLinkedAt();
    }

    private static void runPhase(Context context, String sessionKey,
            long delayMs, String name, Runnable action) {
        MAIN_HANDLER.postDelayed(() -> {
            try {
                SessionManager session = new SessionManager(context);
                if (!isReady(context, session)
                        || !sessionKey.equals(getSessionKey(session))) {
                    Log.d(TAG, "Cancelling phase " + name
                            + "; child connection changed");
                    return;
                }
                action.run();
                Log.d(TAG, "Completed startup phase: " + name);
            } catch (Exception e) {
                Log.e(TAG, "Startup phase failed: " + name + ": " + e.getMessage());
            } finally {
                if ("watchdog".equals(name)) {
                    synchronized (LOCK) {
                        if (sessionKey.equals(scheduledSessionKey)) {
                            phasesScheduled = false;
                            scheduledSessionKey = null;
                        }
                    }
                }
            }
        }, delayMs);
    }

    private static void startForegroundService(Context context, Class<?> serviceClass) {
        Intent intent = new Intent(context, serviceClass);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private static void startRegularService(Context context, Class<?> serviceClass) {
        context.startService(new Intent(context, serviceClass));
    }
}
