package online.monarchlabs.sentinel.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import online.monarchlabs.sentinel.models.StudyModePolicy;

/** Local-only Study Mode draft storage for the UI phase. */
public final class StudyModeDraftStore {
    private static final String PREFS = "study_mode_drafts";
    private static final String KEY_PREFIX = "draft_";

    private StudyModeDraftStore() {
    }

    public static StudyModePolicy load(Context context, String deviceId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(key(deviceId), null);
        if (json == null || json.trim().isEmpty()) {
            return StudyModePolicy.createDefault();
        }
        try {
            boolean hasSchemaVersion = json.contains("\"schemaVersion\"");
            StudyModePolicy policy = new Gson().fromJson(json, StudyModePolicy.class);
            policy = normalize(policy);
            if (!hasSchemaVersion) {
                policy.schemaVersion = 1;
            }
            return policy;
        } catch (Exception ignored) {
            return StudyModePolicy.createDefault();
        }
    }

    public static void save(Context context, String deviceId, StudyModePolicy policy) {
        if (policy == null) {
            return;
        }
        policy.updatedAt = System.currentTimeMillis();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(key(deviceId), new Gson().toJson(policy))
                .apply();
    }

    public static void clear(Context context, String deviceId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(key(deviceId))
                .apply();
    }

    private static StudyModePolicy normalize(StudyModePolicy policy) {
        if (policy == null) {
            policy = StudyModePolicy.createDefault();
        }
        StudyModePolicy defaults = StudyModePolicy.createDefault();
        if (policy.timezone == null || policy.timezone.trim().isEmpty()) {
            policy.timezone = defaults.timezone;
        }
        if (policy.days == null || policy.days.isEmpty()) {
            policy.days = defaults.days;
        }
        if (policy.timeSlots == null || policy.timeSlots.isEmpty()) {
            policy.timeSlots = defaults.timeSlots;
        }
        if (policy.categories == null || policy.categories.isEmpty()) {
            policy.categories = defaults.categories;
        }
        if (policy.blockedPackages == null) {
            policy.blockedPackages = defaults.blockedPackages;
        }
        if (policy.allowedOverrides == null) {
            policy.allowedOverrides = defaults.allowedOverrides;
        }
        if (policy.sessionAllowedPackages == null) {
            policy.sessionAllowedPackages = defaults.sessionAllowedPackages;
        }
        return policy;
    }

    private static String key(String deviceId) {
        String safeDeviceId = deviceId == null || deviceId.trim().isEmpty() ? "no_device" : deviceId;
        return KEY_PREFIX + safeDeviceId;
    }
}
