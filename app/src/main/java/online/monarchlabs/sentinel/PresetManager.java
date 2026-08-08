package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores future Focus Mode presets locally.
 *
 * Focus Mode is intentionally disabled in the v2 stabilization release. Normal
 * per-app blocking continues through v2 device policies in the app-limits UI.
 */
public final class PresetManager {
    private static final String TAG = "PresetManager";
    private static final String PREF_NAME = "device_presets";
    private static final String UNAVAILABLE =
            "Focus Mode is not available in this build";

    private final SharedPreferences prefs;
    private final Gson gson;

    public interface OnPresetSavedListener {
        void onSuccess();
        void onError(String error);
    }

    public interface OnPresetAppliedListener {
        void onSuccess(int blockedApps, int totalApps);
        void onError(String error);
    }

    public interface OnPresetLoadedListener {
        void onSuccess(FocusModePreset preset);
        void onError(String error);
    }

    public PresetManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public boolean hasPreset(String deviceId) {
        return prefs.contains(presetKey(deviceId));
    }

    public boolean isFocusModeActive(String deviceId) {
        return false;
    }

    public FocusModePreset getPreset(String deviceId) {
        String json = prefs.getString(presetKey(deviceId), null);
        if (json == null) {
            return null;
        }
        try {
            return gson.fromJson(json, FocusModePreset.class);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not parse local preset for " + deviceId, error);
            return null;
        }
    }

    public List<FocusModePreset> getAllPresets() {
        List<FocusModePreset> result = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith("preset_")
                    || key.startsWith("preset_count_")) {
                continue;
            }
            FocusModePreset preset =
                    getPreset(key.substring("preset_".length()));
            if (preset != null) {
                result.add(preset);
            }
        }
        return result;
    }

    public void savePreset(FocusModePreset preset,
            OnPresetSavedListener listener) {
        if (preset == null || preset.deviceId == null
                || preset.deviceId.trim().isEmpty()) {
            listener.onError("Device is required");
            return;
        }
        if (preset.createdTimestamp <= 0L) {
            preset.createdTimestamp = System.currentTimeMillis();
        }
        preset.isActive = false;
        prefs.edit()
                .putString(presetKey(preset.deviceId), gson.toJson(preset))
                .putInt(countKey(preset.deviceId),
                        preset.blockedAppPackages == null
                                ? 0 : preset.blockedAppPackages.size())
                .commit();
        listener.onSuccess();
    }

    public void updatePreset(FocusModePreset preset,
            OnPresetSavedListener listener) {
        FocusModePreset existing =
                preset == null ? null : getPreset(preset.deviceId);
        if (existing != null) {
            preset.createdTimestamp = existing.createdTimestamp;
        }
        savePreset(preset, listener);
    }

    public void deletePreset(String deviceId,
            OnPresetSavedListener listener) {
        prefs.edit()
                .remove(presetKey(deviceId))
                .remove(countKey(deviceId))
                .remove(activeKey(deviceId))
                .commit();
        listener.onSuccess();
    }

    public void activatePreset(String deviceId,
            OnPresetAppliedListener listener) {
        listener.onError(UNAVAILABLE);
    }

    public void deactivatePreset(String deviceId,
            OnPresetAppliedListener listener) {
        prefs.edit().remove(activeKey(deviceId)).commit();
        listener.onError(UNAVAILABLE);
    }

    public void loadPresetFromFirebase(String deviceId,
            OnPresetLoadedListener listener) {
        FocusModePreset preset = getPreset(deviceId);
        if (preset == null) {
            listener.onError("No local preset found");
        } else {
            listener.onSuccess(preset);
        }
    }

    public PresetStats getPresetStats(String deviceId) {
        FocusModePreset preset = getPreset(deviceId);
        if (preset == null) {
            return null;
        }
        PresetStats stats = new PresetStats();
        stats.deviceId = deviceId;
        stats.deviceName = preset.deviceName;
        stats.totalBlockedApps = preset.blockedAppPackages == null
                ? 0 : preset.blockedAppPackages.size();
        stats.isActive = false;
        stats.createdDate = preset.createdTimestamp;
        stats.lastActivated = preset.lastActivatedTimestamp;
        return stats;
    }

    private static String presetKey(String deviceId) {
        return "preset_" + deviceId;
    }

    private static String countKey(String deviceId) {
        return "preset_count_" + deviceId;
    }

    private static String activeKey(String deviceId) {
        return "focus_active_" + deviceId;
    }

    public static final class PresetStats {
        public String deviceId;
        public String deviceName;
        public int totalBlockedApps;
        public boolean isActive;
        public long createdDate;
        public long lastActivated;
    }
}