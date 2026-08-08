package online.monarchlabs.sentinel.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tracks time for apps that remain visibly active in picture-in-picture. */
public final class PipUsageTracker {
    private static final String PREFS_NAME = "pip_usage";
    private static final String KEY_SEPARATOR = "|";

    private static PipUsageTracker instance;

    private final SharedPreferences preferences;
    private final Map<String, Long> activeSince = new HashMap<>();

    private PipUsageTracker(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized PipUsageTracker getInstance(Context context) {
        if (instance == null) {
            instance = new PipUsageTracker(context);
        }
        return instance;
    }

    public synchronized void updateVisiblePackages(Set<String> visiblePackages) {
        long now = System.currentTimeMillis();
        Set<String> packages = visiblePackages == null
                ? new HashSet<>()
                : new HashSet<>(visiblePackages);

        for (String packageName : new HashSet<>(activeSince.keySet())) {
            if (!packages.contains(packageName)) {
                persistElapsed(packageName, activeSince.remove(packageName), now);
            }
        }

        for (String packageName : packages) {
            if (packageName != null && !packageName.isEmpty() && !activeSince.containsKey(packageName)) {
                activeSince.put(packageName, now);
            }
        }
    }

    public synchronized void checkpoint() {
        long now = System.currentTimeMillis();
        for (String packageName : new HashSet<>(activeSince.keySet())) {
            persistElapsed(packageName, activeSince.get(packageName), now);
            activeSince.put(packageName, now);
        }
    }

    public synchronized void resetForNewRelationship() {
        activeSince.clear();
        preferences.edit().clear().commit();
    }

    public synchronized long getUsageMillis(String dateKey, String packageName) {
        long usage = preferences.getLong(storageKey(dateKey, packageName), 0);
        Long startedAt = activeSince.get(packageName);
        if (startedAt != null && dateKey.equals(todayKey())) {
            usage += Math.max(0, System.currentTimeMillis() - startedAt);
        }
        return usage;
    }

    public synchronized Map<String, Long> getUsageForDate(String dateKey) {
        Map<String, Long> usage = new HashMap<>();
        String prefix = dateKey + KEY_SEPARATOR;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof Long) {
                usage.put(entry.getKey().substring(prefix.length()), (Long) entry.getValue());
            }
        }

        if (dateKey.equals(todayKey())) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> entry : activeSince.entrySet()) {
                usage.put(entry.getKey(), usage.getOrDefault(entry.getKey(), 0L)
                        + Math.max(0, now - entry.getValue()));
            }
        }
        return usage;
    }

    private void persistElapsed(String packageName, Long startedAt, long now) {
        if (startedAt == null || now <= startedAt) {
            return;
        }
        String key = storageKey(todayKey(), packageName);
        preferences.edit()
                .putLong(key, preferences.getLong(key, 0) + (now - startedAt))
                .apply();
    }

    private String storageKey(String dateKey, String packageName) {
        return dateKey + KEY_SEPARATOR + packageName;
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
