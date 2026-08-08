package online.monarchlabs.sentinel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.app.ActivityManager;

import online.monarchlabs.sentinel.utils.CrashlyticsLogger;
import online.monarchlabs.sentinel.services.AppTimerService;

import java.util.List;

/**
 * 🛡️ BULLETPROOF SERVICE WATCHDOG
 * 
 * This class ensures critical services stay alive even on aggressive OEMs.
 * It uses AlarmManager to periodically check and restart services if they die.
 * 
 * Features:
 * - Periodic service health checks (every 60 seconds)
 * - Automatic service restart if killed
 * - Works even when app is in background
 * - Survives OEM battery optimizations
 * - Boot receiver integration
 */
public class ServiceWatchdog extends BroadcastReceiver {
    private static final String TAG = "ServiceWatchdog";

    private static final String ACTION_CHECK_SERVICES = "online.monarchlabs.sentinel.CHECK_SERVICES";
    private static final String ACTION_RESTART_SERVICES = "online.monarchlabs.sentinel.RESTART_SERVICES";

    // Check interval: 60 seconds (aggressive OEMs kill services quickly)
    private static final long CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "🐕 Watchdog received: " + action);

        if (action == null) {
            // Default action - check services
            checkAndRestartServices(context);
            return;
        }

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
                Log.d(TAG, "📱 Device booted - starting services");
                startAllServices(context);
                schedulePeriodicChecks(context);
                break;

            case ACTION_CHECK_SERVICES:
            case ACTION_RESTART_SERVICES:
                checkAndRestartServices(context);
                break;

            case Intent.ACTION_MY_PACKAGE_REPLACED:
                Log.d(TAG, "📦 App updated - restarting services");
                startAllServices(context);
                schedulePeriodicChecks(context);
                break;

            default:
                checkAndRestartServices(context);
                break;
        }
    }

    /**
     * 🚀 Start all critical services
     */
    public static void startAllServices(Context context) {
        ChildServiceCoordinator.start(context, "watchdog_start_all");
        if (ChildServiceCoordinator.isManagingStartup(context)) {
            return;
        }
        Log.d(TAG, "🚀 Starting all critical services...");

        try {
            SessionManager sessionManager = new SessionManager(context);

            // Only start services for child devices
            if (!"child".equals(sessionManager.getUserType())) {
                Log.d(TAG, "Not a child device - skipping service start");
                return;
            }

            if (!sessionManager.isLoggedIn()) {
                Log.d(TAG, "User not logged in - skipping service start");
                return;
            }

            // 1. Start RemoteBlockService (CRITICAL - handles blocking)
            startServiceSafely(context, RemoteBlockService.class, "RemoteBlockService");


            // 2. Schedule Usage Upload (WorkManager Backup)
            try {
                online.monarchlabs.sentinel.workers.UsageUploadScheduler.schedulePeriodicUpload(context);
                Log.d(TAG, "UsageUploadScheduler scheduled");
            } catch (Exception e) {
                Log.e(TAG, "Failed to schedule UsageUploadScheduler: " + e.getMessage());
            }

            // 3. Start AppTimerService (Per-App Timers)
            try {
                String childDeviceId = sessionManager.getChildDeviceId();
                if (childDeviceId != null && !childDeviceId.isEmpty()) {
                    AppTimerService.start(context, childDeviceId);
                    Log.d(TAG, "✅ AppTimerService started");
                } else {
                    Log.w(TAG, "⚠️ AppTimerService start skipped - childDeviceId is null or empty");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to start AppTimerService: " + e.getMessage());
            }

            Log.d(TAG, "✅ All services started successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting services: " + e.getMessage());
        }
    }

    /**
     * 🔍 Check if services are running and restart if needed
     */
    public static void checkAndRestartServices(Context context) {
        ChildServiceCoordinator.ensureCriticalService(context, "watchdog_health_check");
        if (ChildServiceCoordinator.isManagingStartup(context)) {
            schedulePeriodicChecks(context);
            return;
        }
        Log.d(TAG, "🔍 Checking service health...");

        try {
            SessionManager sessionManager = new SessionManager(context);

            // Only check for child devices
            if (!"child".equals(sessionManager.getUserType())) {
                return;
            }

            if (!sessionManager.isLoggedIn()) {
                return;
            }

            // Check RemoteBlockService
            if (!isServiceRunning(context, RemoteBlockService.class)) {
                Log.w(TAG, "⚠️ RemoteBlockService is DEAD - restarting!");
                startServiceSafely(context, RemoteBlockService.class, "RemoteBlockService");
            } else {
                Log.d(TAG, "✅ RemoteBlockService is alive");
            }


            // Check AppTimerService (Per-App Timers)
            if (!isServiceRunning(context, AppTimerService.class)) {
                Log.w(TAG, "⚠️ AppTimerService is DEAD - restarting!");
                String childDeviceId = sessionManager.getChildDeviceId();
                if (childDeviceId != null && !childDeviceId.isEmpty()) {
                    AppTimerService.start(context, childDeviceId);
                } else {
                    Log.w(TAG, "⚠️ AppTimerService restart skipped - childDeviceId is null or empty");
                }
            } else {
                Log.d(TAG, "✅ AppTimerService is alive");
            }

            // Reschedule next check
            schedulePeriodicChecks(context);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error checking services: " + e.getMessage());
        }
    }

    /**
     * 📅 Schedule periodic service health checks using AlarmManager
     */
    public static void schedulePeriodicChecks(Context context) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                Log.e(TAG, "AlarmManager is null!");
                return;
            }

            Intent intent = new Intent(context, ServiceWatchdog.class);
            intent.setAction(ACTION_CHECK_SERVICES);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    1001, // Unique request code
                    intent,
                    flags);

            // Cancel existing alarm
            alarmManager.cancel(pendingIntent);

            // Schedule next check
            long triggerTime = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS;

            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent);

            Log.d(TAG, "📅 Next service check scheduled in " + (CHECK_INTERVAL_MS / 1000) + " seconds");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error scheduling checks: " + e.getMessage());
        }
    }

    /**
     * 🛑 Cancel all scheduled checks
     */
    public static void cancelPeriodicChecks(Context context) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null)
                return;

            Intent intent = new Intent(context, ServiceWatchdog.class);
            intent.setAction(ACTION_CHECK_SERVICES);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 1001, intent, flags);
            alarmManager.cancel(pendingIntent);

            Log.d(TAG, "🛑 Periodic checks cancelled");

        } catch (Exception e) {
            Log.e(TAG, "Error cancelling checks: " + e.getMessage());
        }
    }

    /**
     * 🔧 Safely start a foreground service
     */
    private static void startServiceSafely(Context context, Class<?> serviceClass, String serviceName) {
        try {
            Intent serviceIntent = new Intent(context, serviceClass);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(serviceIntent);
                    Log.d(TAG, "✅ Started " + serviceName + " via startForegroundService");
                } catch (Exception e) {
                    Log.w(TAG, "⚠️ Failed to start service " + serviceName + " directly: " + e.getMessage() + ". Device might be strict or app backgrounded.");
                }
            } else {
                context.startService(serviceIntent);
                Log.d(TAG, "✅ Started " + serviceName + " via startService");
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start " + serviceName + ": " + e.getMessage());
        }
    }

    /**
     * 🔍 Check if a service is currently running
     */
    private static boolean isAppInForeground(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }

            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) {
                return false;
            }

            String packageName = context.getPackageName();
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (packageName.equals(process.processName)) {
                    return process.importance
                            <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking foreground state: " + e.getMessage());
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    public static boolean isServiceRunning(Context context, Class<?> serviceClass) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null)
                return false;

            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
            if (services == null)
                return false;

            for (ActivityManager.RunningServiceInfo service : services) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error checking service status: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🚀 Initialize the watchdog (call this from ChildDashboardActivity)
     */
    public static void initialize(Context context) {
        Log.d(TAG, "🐕 Initializing Service Watchdog...");

        try {
            SessionManager sessionManager = new SessionManager(context);

            // Only initialize for child devices
            if (!"child".equals(sessionManager.getUserType())) {
                Log.d(TAG, "Not a child device - watchdog not needed");
                return;
            }

            // Apply Device Owner protections if available
            applyDeviceOwnerProtections(context);

            // Start all services
            startAllServices(context);

            // Schedule periodic health checks
            schedulePeriodicChecks(context);

            Log.d(TAG, "✅ Service Watchdog initialized successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing watchdog: " + e.getMessage());
        }
    }

    /**
     * 🛡️ Apply Device Owner protections to prevent OEM killing services
     */
    /**
     * 🛡️ Apply standard protections
     */
    private static void applyDeviceOwnerProtections(Context context) {
        Log.d(TAG, "ℹ️ Device Owner disabled - using standard protections");
        // 1. Use foreground services (already done)
        // 2. Use WakeLocks (already done in RemoteBlockService)
        // 3. Use AlarmManager for periodic restarts (already done)
    }

    /**
     * 📋 Show instructions for setting up Device Owner via ADB
     * (Deprecated/Disabled)
     */
    public static String getDeviceOwnerSetupInstructions() {
        return "Device Owner feature is currently disabled.";
    }
}
