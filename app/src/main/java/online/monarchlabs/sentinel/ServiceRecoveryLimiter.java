package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;

public class ServiceRecoveryLimiter {
    private static final String TAG = "ServiceRecoveryLimiter";
    private static final String PREF_RESTART_STATS = "service_restart_stats";
    private static final int MAX_RESTARTS = 3;
    private static final long WINDOW_MS = 5 * 60 * 1000L; // 5 mins
    private static final long COOLDOWN_MS = 15 * 60 * 1000L; // 15 mins
    private static final int MAX_COOLDOWNS_PER_HOUR = 5;
    private static final long COOLDOWN_STORM_WINDOW_MS = 60 * 60 * 1000L; // 1 hour

    public static boolean canRestart(Context context, String serviceName) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_RESTART_STATS, Context.MODE_PRIVATE);
        long currentTime = System.currentTimeMillis();

        // 1. Check if cooldown is active
        long cooldownActiveUntil = prefs.getLong(serviceName + "_cooldown_until", 0);
        if (currentTime < cooldownActiveUntil) {
            Log.w(TAG, "Restarts blocked by cooldown for service: " + serviceName + ". Remaining: " + ((cooldownActiveUntil - currentTime) / 1000) + "s");
            return false;
        }

        // 2. Parse existing restart history
        String historyStr = prefs.getString(serviceName + "_history", "");
        List<Long> timestamps = parseTimestamps(historyStr);

        // Remove timestamps older than sliding window
        timestamps.removeIf(time -> (currentTime - time) > WINDOW_MS);

        // 3. If exceeded, enter cooldown
        if (timestamps.size() >= MAX_RESTARTS) {
            Log.e(TAG, "Crash loop detected! Entering 15-minute cooldown for service: " + serviceName);
            
            // Record cooldown event
            long cooldownUntil = currentTime + COOLDOWN_MS;
            prefs.edit()
                .putLong(serviceName + "_cooldown_until", cooldownUntil)
                .remove(serviceName + "_history")
                .apply();
            
            // Update health status in Firebase/SharedPreferences
            notifyDegradedState(context, serviceName, "crash_loop_cooldown");

            // Track consecutive cooldowns for auto-rollback
            trackCooldownStorm(context, serviceName, currentTime);
            return false;
        }

        // 4. Record this attempt
        timestamps.add(currentTime);
        prefs.edit().putString(serviceName + "_history", serializeTimestamps(timestamps)).apply();
        return true;
    }

    private static void trackCooldownStorm(Context context, String serviceName, long currentTime) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_RESTART_STATS, Context.MODE_PRIVATE);
        String cooldownHistoryStr = prefs.getString(serviceName + "_cooldown_history", "");
        List<Long> cooldowns = parseTimestamps(cooldownHistoryStr);

        // Clean up entries older than 1 hour
        cooldowns.removeIf(time -> (currentTime - time) > COOLDOWN_STORM_WINDOW_MS);
        cooldowns.add(currentTime);

        prefs.edit().putString(serviceName + "_cooldown_history", serializeTimestamps(cooldowns)).apply();

        if (cooldowns.size() >= MAX_COOLDOWNS_PER_HOUR) {
            Log.e(TAG, "🚨 CRASH STORM DETECTED! Triggering automatic rollback to legacy watchdog...");
            
            // Trigger programmatic auto-rollback
            SharedPreferences configPrefs = context.getSharedPreferences("self_healing_config", Context.MODE_PRIVATE);
            configPrefs.edit()
                    .putBoolean("use_accessibility_supervisor", false)
                    .putBoolean("use_legacy_alarm_watchdog", true)
                    .apply();

            // Re-schedule legacy watchdogs
            ServiceWatchdog.schedulePeriodicChecks(context);

            notifyDegradedState(context, serviceName, "auto_rollback_triggered_due_to_crash_storm");
        }
    }

    private static void notifyDegradedState(Context context, String service, String reason) {
        try {
            SessionManager sessionManager = new SessionManager(context);
            String deviceId = sessionManager.getChildDeviceId();
            if (deviceId != null && !deviceId.isEmpty()) {
                Map<String, Object> health = new HashMap<>();
                health.put("inCooldown", true);
                health.put("degradedReason", service + "_" + reason);
                health.put("lastUpdated", System.currentTimeMillis());
                FirebaseSchemaV2Repository.syncDeviceHealth(deviceId, health)
                        .addOnFailureListener(error ->
                                Log.w(TAG, "v2 health sync deferred: " + error.getMessage()));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update Firebase degraded state: " + e.getMessage());
        }
    }

    private static List<Long> parseTimestamps(String historyStr) {
        List<Long> list = new ArrayList<>();
        if (historyStr == null || historyStr.isEmpty()) {
            return list;
        }
        String[] parts = historyStr.split(",");
        for (String part : parts) {
            try {
                list.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }

    private static String serializeTimestamps(List<Long> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append(list.get(i));
            if (i < list.size() - 1) {
                sb.append(",");
            }
        }
        return sb.toString();
    }
}
