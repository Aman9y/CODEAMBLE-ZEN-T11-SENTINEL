package online.monarchlabs.sentinel;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import online.monarchlabs.sentinel.services.AppTimerService;
import online.monarchlabs.sentinel.utils.SUsageDataManager;
import online.monarchlabs.sentinel.workers.UsageUploadScheduler;

import java.util.Map;

/** Clears state owned by a removed parent-child relationship. */
public final class ChildConnectionDataCleaner {
    private static final String TAG = "ChildDataCleaner";
    private static final String DELAYED_BLOCK_ACTION =
            "online.monarchlabs.sentinel.ENFORCE_DELAYED_BLOCK";

    private static final String[] GLOBAL_FEATURE_PREFS = {
            "blocked_apps",
            "scheduled_block_policy_ids",
            "app_timer_blocked_apps",
            "smart_timer_prefs",
            "timer_state",
            "timer_prefs",
            "app_timer_prefs",
            "permanent_limiter_notifications",
            "usage_limiter",
            "usage_limiter_prefs",
            "usage_data",
            "app_usage_cache",
            "usage_data_cache_prefs",
            "usage_dates",
            "child_location_prefs",
            "PermissionStatus"
    };

    private ChildConnectionDataCleaner() {
    }

    public static void clear(Context context, String deviceId) {
        clear(context, deviceId, false);
    }

    public static void clearForDisconnect(Context context, String deviceId) {
        clear(context, deviceId, true);
    }

    private static void clear(
            Context context, String deviceId, boolean eraseUsageHistory) {
        Context appContext = context.getApplicationContext();
        UsageUploadScheduler.cancelAllUploads(appContext);
        cancelDelayedBlockAlarms(appContext);

        for (String prefName : GLOBAL_FEATURE_PREFS) {
            appContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit();
        }

        if (deviceId != null && !deviceId.isEmpty()) {
            String[] devicePrefs = {
                    "app_timer_sync_" + deviceId,
                    "applied_block_policies_" + deviceId,
                    "applied_focus_policies_" + deviceId,
                    "blocked_apps_" + deviceId,
                    "app_limits_policy_migration_" + deviceId,
                    "date_aware_usage_" + deviceId
            };
            for (String prefName : devicePrefs) {
                appContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit();
            }
            AppTimerService.clearLocalConnectionState(appContext, deviceId);
        }

        if (eraseUsageHistory) {
            SUsageDataManager.clearForDisconnection(appContext);
        } else {
            SUsageDataManager.resetUploadStateForNewRelationship(appContext);
        }

        Intent update = new Intent(
                "online.monarchlabs.sentinel.BLOCKED_APPS_UPDATED");
        update.setPackage(appContext.getPackageName());
        update.putExtra("blocked_count", 0);
        appContext.sendBroadcast(update);
        Log.d(TAG, "Cleared child relationship feature state for device: " + deviceId);
    }
    private static void cancelDelayedBlockAlarms(Context context) {
        SharedPreferences scheduled = context.getSharedPreferences(
                "scheduled_blocks", Context.MODE_PRIVATE);
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            for (Map.Entry<String, ?> entry : scheduled.getAll().entrySet()) {
                String packageName = entry.getKey();
                Intent intent = new Intent(context, RemoteBlockService.class);
                intent.setAction(DELAYED_BLOCK_ACTION);
                int flags = PendingIntent.FLAG_NO_CREATE;
                if (android.os.Build.VERSION.SDK_INT
                        >= android.os.Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent pendingIntent = PendingIntent.getService(
                        context, packageName.hashCode(), intent, flags);
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent);
                    pendingIntent.cancel();
                }
            }
        }
        scheduled.edit().clear().commit();
    }
}