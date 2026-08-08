package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Durable child-side timer execution state.
 *
 * Firebase owns timer policy while this store owns the live execution state.
 * Keeping the execution fields here lets the child calculate timers after an
 * app restart or while offline without continuously writing countdown ticks.
 */
public final class AppTimerLocalStore {
    private static final String TAG = "AppTimerLocalStore";
    private static final String PREF_PREFIX = "app_timers_local_";

    private AppTimerLocalStore() {
    }

    public static List<TimerRecord> load(Context context, String deviceId) {
        List<TimerRecord> records = new ArrayList<>();
        if (deviceId == null || deviceId.isEmpty()) {
            return records;
        }

        SharedPreferences prefs = getPreferences(context, deviceId);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                continue;
            }
            try {
                TimerRecord record = TimerRecord.fromJson((String) entry.getValue());
                if (record.packageName == null || record.packageName.isEmpty()) {
                    record.packageName = entry.getKey();
                }
                records.add(record);
            } catch (JSONException e) {
                Log.w(TAG, "Ignoring invalid cached timer for " + entry.getKey(), e);
            }
        }
        return records;
    }

    public static void replaceAll(Context context, String deviceId,
                                  Collection<TimerRecord> records) {
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }

        SharedPreferences.Editor editor = getPreferences(context, deviceId).edit().clear();
        for (TimerRecord record : records) {
            putOnEditor(editor, record);
        }
        editor.apply();
    }

    public static void put(Context context, String deviceId, TimerRecord record) {
        if (deviceId == null || deviceId.isEmpty() || record == null) {
            return;
        }
        SharedPreferences.Editor editor = getPreferences(context, deviceId).edit();
        putOnEditor(editor, record);
        editor.apply();
    }

    public static void remove(Context context, String deviceId, String packageName) {
        if (deviceId == null || deviceId.isEmpty()
                || packageName == null || packageName.isEmpty()) {
            return;
        }
        getPreferences(context, deviceId).edit().remove(packageName).commit();
    }

    public static void clear(Context context, String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }
        getPreferences(context, deviceId).edit().clear().commit();
    }

    private static void putOnEditor(SharedPreferences.Editor editor, TimerRecord record) {
        if (record == null || record.packageName == null || record.packageName.isEmpty()) {
            return;
        }
        try {
            editor.putString(record.packageName, record.toJson().toString());
        } catch (JSONException e) {
            Log.w(TAG, "Unable to cache timer for " + record.packageName, e);
        }
    }

    private static SharedPreferences getPreferences(Context context, String deviceId) {
        return context.getSharedPreferences(PREF_PREFIX + deviceId, Context.MODE_PRIVATE);
    }

    public static class TimerRecord {
        public String packageName;
        public String key;
        public String appName;
        public long remainingTimeMillis;
        public long dailyLimitMillis;
        public long exceedTimeMillis;
        public long usageAtSetMillis = -1L;
        public long activationRemainingMillis;
        public long policyVersion;
        public long executionVersion;
        public long lastEvaluatedAt;
        public long lastEvaluatedElapsedRealtime;
        public long expiredAt;
        public long cancelledAt;
        public long lastSyncedExecutionVersion;
        public String state = "ACTIVE";
        public String lastResetDate = "";
        public boolean pendingSync;
        public boolean active;
        public boolean expired;

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("packageName", packageName);
            json.put("key", key != null ? key : "");
            json.put("appName", appName != null ? appName : "");
            json.put("remainingTimeMillis", remainingTimeMillis);
            json.put("dailyLimitMillis", dailyLimitMillis);
            json.put("exceedTimeMillis", exceedTimeMillis);
            json.put("usageAtSetMillis", usageAtSetMillis);
            json.put("activationRemainingMillis", activationRemainingMillis);
            json.put("policyVersion", policyVersion);
            json.put("executionVersion", executionVersion);
            json.put("lastEvaluatedAt", lastEvaluatedAt);
            json.put("lastEvaluatedElapsedRealtime", lastEvaluatedElapsedRealtime);
            json.put("expiredAt", expiredAt);
            json.put("cancelledAt", cancelledAt);
            json.put("lastSyncedExecutionVersion", lastSyncedExecutionVersion);
            json.put("state", state != null ? state : "ACTIVE");
            json.put("lastResetDate", lastResetDate != null ? lastResetDate : "");
            json.put("pendingSync", pendingSync);
            json.put("active", active);
            json.put("expired", expired);
            return json;
        }

        static TimerRecord fromJson(String value) throws JSONException {
            JSONObject json = new JSONObject(value);
            TimerRecord record = new TimerRecord();
            record.packageName = json.optString("packageName", "");
            record.key = json.optString("key", "");
            record.appName = json.optString("appName", "");
            record.remainingTimeMillis = json.optLong("remainingTimeMillis", 0L);
            record.dailyLimitMillis = json.optLong("dailyLimitMillis",
                    record.remainingTimeMillis);
            record.exceedTimeMillis = json.optLong("exceedTimeMillis", 0L);
            record.usageAtSetMillis = json.optLong("usageAtSetMillis", -1L);
            record.activationRemainingMillis = json.optLong(
                    "activationRemainingMillis", record.remainingTimeMillis);
            record.policyVersion = json.optLong("policyVersion", 0L);
            record.executionVersion = json.optLong("executionVersion", 0L);
            record.lastEvaluatedAt = json.optLong("lastEvaluatedAt", 0L);
            record.lastEvaluatedElapsedRealtime = json.optLong(
                    "lastEvaluatedElapsedRealtime", 0L);
            record.expiredAt = json.optLong("expiredAt", 0L);
            record.cancelledAt = json.optLong("cancelledAt", 0L);
            record.lastSyncedExecutionVersion = json.optLong(
                    "lastSyncedExecutionVersion", 0L);
            record.state = json.optString("state", "");
            record.lastResetDate = json.optString("lastResetDate", "");
            record.pendingSync = json.optBoolean("pendingSync", false);
            record.active = json.optBoolean("active", false);
            record.expired = json.optBoolean("expired",
                    record.remainingTimeMillis <= 0 && !record.active);
            if (record.state.isEmpty()) {
                record.state = record.expired ? "EXPIRED"
                        : (record.active ? "ACTIVE" : "PAUSED");
            }
            return record;
        }
    }
}
