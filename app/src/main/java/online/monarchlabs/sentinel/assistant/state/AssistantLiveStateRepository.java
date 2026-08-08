package online.monarchlabs.sentinel.assistant.state;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AssistantLiveStateRepository {
    public interface RefreshCallback {
        void onComplete(LiveStateSnapshot snapshot);
    }

    public static class LiveStateSnapshot {
        public String deviceId;
        public long refreshedAtMillis;
        public final List<String> installedApps = new ArrayList<>();
        public final List<String> activeTimerPackages = new ArrayList<>();
        public final List<String> blockedPackages = new ArrayList<>();
        public final java.util.Map<String, String> appNameToPackage = new java.util.HashMap<>();
        public final java.util.Map<String, Long> appUsageMillis = new java.util.HashMap<>();
        public final java.util.Map<String, String> packageToAppName = new java.util.HashMap<>();
        public String foregroundApp;
    }

    private final Context appContext;

    public AssistantLiveStateRepository(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void refresh(String deviceId, RefreshCallback callback) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            if (callback != null) {
                callback.onComplete(new LiveStateSnapshot());
            }
            return;
        }

        // 1. Immediately return cached snapshot to ensure instant load time
        LiveStateSnapshot cachedSnapshot = getCachedSnapshot(deviceId);
        if (cachedSnapshot != null && callback != null) {
            callback.onComplete(cachedSnapshot);
        }

        final LiveStateSnapshot snapshot = new LiveStateSnapshot();
        snapshot.deviceId = deviceId;
        snapshot.refreshedAtMillis = System.currentTimeMillis();

        SharedPreferences devicePrefs = appContext.getSharedPreferences(
                "blocked_apps_" + deviceId, Context.MODE_PRIVATE);
        SharedPreferences sharedPrefs = appContext.getSharedPreferences(
                "blocked_apps_prefs", Context.MODE_PRIVATE);
        collectBlocked(snapshot.blockedPackages, devicePrefs.getAll());
        collectBlocked(snapshot.blockedPackages, sharedPrefs.getAll());

        Map<String, Object> request = new HashMap<>();
        request.put("requestId", "assistant_" + snapshot.refreshedAtMillis);
        request.put("requestedAt", snapshot.refreshedAtMillis);
        request.put("requestedBy", "assistant");
        request.put("status", "pending");
        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("timer_state_requests")
                .child(deviceId)
                .setValue(request);

        // Define references for parallel database queries
        DatabaseReference fgRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_status")
                .child(deviceId)
                .child("foreground_app");

        DatabaseReference installsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_installs")
                .child(deviceId)
                .child("apps");

        DatabaseReference timersRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_policies")
                .child(deviceId)
                .child("app_timers");

        DatabaseReference blockedRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_policies")
                .child(deviceId)
                .child("blocked_apps");

        String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
        DatabaseReference usageRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("usage_daily")
                .child(deviceId)
                .child(dateKey)
                .child("apps");

        // Fire all queries concurrently
        com.google.android.gms.tasks.Task<DataSnapshot> fgTask = fgRef.get();
        com.google.android.gms.tasks.Task<DataSnapshot> installsTask = installsRef.get();
        com.google.android.gms.tasks.Task<DataSnapshot> timersTask = timersRef.get();
        com.google.android.gms.tasks.Task<DataSnapshot> blockedTask = blockedRef.get();
        com.google.android.gms.tasks.Task<DataSnapshot> usageTask = usageRef.get();

        com.google.android.gms.tasks.Tasks.whenAllComplete(fgTask, installsTask, timersTask, blockedTask, usageTask)
                .addOnCompleteListener(allTasks -> {
                    // Process foreground app
                    if (fgTask.isSuccessful() && fgTask.getResult() != null) {
                        String fgApp = fgTask.getResult().getValue(String.class);
                        if (fgApp != null && !fgApp.trim().isEmpty()) {
                            snapshot.foregroundApp = fgApp.trim();
                        }
                    }

                    // Process installed apps
                    if (installsTask.isSuccessful() && installsTask.getResult() != null) {
                        collectInstalledApps(snapshot, installsTask.getResult());
                    }

                    // Process app timers
                    if (timersTask.isSuccessful() && timersTask.getResult() != null) {
                        collectTimerPackages(snapshot.activeTimerPackages, timersTask.getResult());
                    }

                    // Process blocked apps list
                    if (blockedTask.isSuccessful() && blockedTask.getResult() != null) {
                        DataSnapshot blockedSnap = blockedTask.getResult();
                        for (DataSnapshot child : blockedSnap.getChildren()) {
                            String originalPackageName = child.child("packageName").getValue(String.class);
                            Boolean blocked = child.child("blocked").getValue(Boolean.class);
                            if (originalPackageName != null && !originalPackageName.trim().isEmpty() && Boolean.TRUE.equals(blocked)) {
                                if (!snapshot.blockedPackages.contains(originalPackageName)) {
                                    snapshot.blockedPackages.add(originalPackageName);
                                }
                            }
                        }
                    }

                    // Process usage
                    if (usageTask.isSuccessful() && usageTask.getResult() != null) {
                        collectAppUsage(snapshot, usageTask.getResult());
                    }

                    // Save fresh snapshot to cache
                    saveSnapshotToCache(deviceId, snapshot);

                    // Return fresh values
                    if (callback != null) {
                        callback.onComplete(snapshot);
                    }
                });
    }

    private void collectAppUsage(LiveStateSnapshot snapshot, DataSnapshot dataSnapshot) {
        for (DataSnapshot child : dataSnapshot.getChildren()) {
            String packageName = child.child("packageName").getValue(String.class);
            if (packageName == null || packageName.trim().isEmpty()) {
                packageName = child.getKey();
            }
            if (packageName != null) {
                packageName = packageName.trim();
                Long usage = null;
                try {
                    // Try parsing as compact schema (value is Long)
                    Object val = child.getValue();
                    if (val instanceof Number) {
                        usage = ((Number) val).longValue();
                    } else {
                        // Fallback to object schema
                        usage = child.child("usageTimeMillis").getValue(Long.class);
                        if (usage == null) {
                            usage = child.child("usageTime").getValue(Long.class);
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }

                if (usage != null && usage > 0) {
                    snapshot.appUsageMillis.put(packageName, usage);
                }
                String appName = child.child("appName").getValue(String.class);
                if (appName != null && !appName.trim().isEmpty()) {
                    String trimmedName = appName.trim();
                    snapshot.packageToAppName.put(packageName, trimmedName);
                    snapshot.packageToAppName.put(packageName.replace('.', '_'), trimmedName);
                    snapshot.packageToAppName.put(packageName.replace('_', '.'), trimmedName);
                }
            }
        }
    }

    private void collectInstalledApps(LiveStateSnapshot snapshot, DataSnapshot dataSnapshot) {
        for (DataSnapshot child : dataSnapshot.getChildren()) {
            String appName = child.child("appName").getValue(String.class);
            if (appName == null || appName.trim().isEmpty()) {
                appName = child.child("name").getValue(String.class);
            }
            String packageName = child.child("packageName").getValue(String.class);
            if (packageName == null || packageName.trim().isEmpty()) {
                packageName = child.getKey();
            }

            if (appName != null && !appName.trim().isEmpty()) {
                String trimmedName = appName.trim();
                if (!snapshot.installedApps.contains(trimmedName)) {
                    snapshot.installedApps.add(trimmedName);
                }
                if (packageName != null && !packageName.trim().isEmpty()) {
                    String pkg = packageName.trim();
                    snapshot.appNameToPackage.put(trimmedName.toLowerCase(java.util.Locale.US), pkg);
                    snapshot.packageToAppName.put(pkg, trimmedName);
                    snapshot.packageToAppName.put(pkg.replace('.', '_'), trimmedName);
                    snapshot.packageToAppName.put(pkg.replace('_', '.'), trimmedName);
                }
            }
        }
    }

    private void collectTimerPackages(List<String> target, DataSnapshot snapshot) {
        for (DataSnapshot child : snapshot.getChildren()) {
            String packageName = child.child("packageName").getValue(String.class);
            if (packageName == null || packageName.trim().isEmpty()) {
                packageName = child.getKey();
            }
            if (packageName != null && !packageName.trim().isEmpty() && !target.contains(packageName.trim())) {
                target.add(packageName.trim());
            }
        }
    }

    private void collectBlocked(List<String> target, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue()) && !target.contains(entry.getKey())) {
                target.add(entry.getKey());
            }
        }
    }

    private LiveStateSnapshot getCachedSnapshot(String deviceId) {
        try {
            SharedPreferences cachePrefs = appContext.getSharedPreferences("assistant_live_state_cache", Context.MODE_PRIVATE);
            String json = cachePrefs.getString("cache_" + deviceId, null);
            if (json != null) {
                LiveStateSnapshot snapshot = new com.google.gson.Gson().fromJson(json, LiveStateSnapshot.class);
                if (snapshot != null && snapshot.appUsageMillis != null) {
                    java.util.Map<String, Long> converted = new java.util.HashMap<>();
                    for (java.util.Map.Entry<String, ?> entry : snapshot.appUsageMillis.entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof Number) {
                            converted.put(entry.getKey(), ((Number) value).longValue());
                        }
                    }
                    snapshot.appUsageMillis.clear();
                    snapshot.appUsageMillis.putAll(converted);
                }
                return snapshot;
            }
        } catch (Exception e) {
            android.util.Log.e("AssistantLiveStateRepo", "Failed to load cached snapshot: " + e.getMessage());
        }
        return null;
    }

    private void saveSnapshotToCache(String deviceId, LiveStateSnapshot snapshot) {
        try {
            SharedPreferences cachePrefs = appContext.getSharedPreferences("assistant_live_state_cache", Context.MODE_PRIVATE);
            String json = new com.google.gson.Gson().toJson(snapshot);
            cachePrefs.edit().putString("cache_" + deviceId, json).apply();
        } catch (Exception e) {
            android.util.Log.e("AssistantLiveStateRepo", "Failed to save snapshot to cache: " + e.getMessage());
        }
    }
}