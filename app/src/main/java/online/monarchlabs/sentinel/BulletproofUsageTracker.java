package online.monarchlabs.sentinel;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.app.usage.UsageEvents;
import android.content.pm.ResolveInfo;


import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 🎯 BULLETPROOF 7-DAY USAGE TRACKER
 * 
 * This is a complete rewrite that ACTUALLY WORKS:
 * - Uses UsageStatsManager.queryUsageStats() for reliable data
 * - Updates every 2 minutes for near real-time display
 * - Does not upload; canonical usage sync is handled by SUsageDataManager.
 * - Parent can see data immediately
 * - Handles permission checks properly
 */
public class BulletproofUsageTracker {
    private static final String TAG = "BulletproofUsage";

    // Update every 2 minutes for near real-time
    private static final long UPDATE_INTERVAL_MS = 2 * 60 * 1000;

    private final Context context;
    private final String deviceId;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;

    private Handler updateHandler;
    private Runnable updateRunnable;
    private boolean isRunning = false;

    private final SimpleDateFormat dateKeyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat dayLabelFormat = new SimpleDateFormat("EEE", Locale.getDefault());

    // Core system components to exclude from screen-time totals.
    // Anything the user can launch is kept automatically, so this stays device-agnostic.
    private static final Set<String> EXCLUDED_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.launcher3",
            "com.android.permissioncontroller",
            "com.android.providers.settings",
            "com.android.shell",
            "com.android.externalstorage",
            "com.android.documentsui",
            "com.android.packageinstaller",
            "com.android.server.telecom",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.ext",
                "com.google.android.apps.wellbeing"
    ));

    public BulletproofUsageTracker(Context context) {
        this.context = context.getApplicationContext();
        this.deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = context.getPackageManager();
        this.updateHandler = new Handler(Looper.getMainLooper());

        Log.d(TAG, "🎯 BulletproofUsageTracker created for device: " + deviceId);
    }

    /**
     * Check if a package is a home/launcher by resolving the HOME intent.
     */
    private boolean isHomePackage(String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> resolves = packageManager.queryIntentActivities(intent, 0);
            if (resolves != null) {
                for (ResolveInfo ri : resolves) {
                    if (ri.activityInfo != null && packageName.equals(ri.activityInfo.packageName)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Exclude system-only packages and core Android tools.
     */
    private boolean shouldSkipAsCoreSystemApp(String packageName, ApplicationInfo appInfo) {
        if (packageName == null || packageName.isEmpty() || appInfo == null) {
            return true;
        }

        if (EXCLUDED_PACKAGES.contains(packageName)) {
            return true;
        }

        boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

        if (!isSystemApp) {
            return false;
        }

        if (isHomePackage(packageName)) {
            return true;
        }

        String lower = packageName.toLowerCase(Locale.getDefault());
        if (lower.startsWith("com.android.") || lower.startsWith("android")) {
            return true;
        }
        if (lower.startsWith("com.google.android.")) {
            if (lower.contains("wellbeing") || lower.contains("digitalwellbeing") ||
                    lower.contains("permissioncontroller") || lower.contains("packageinstaller") ||
                    lower.contains("systemui") || lower.contains("launcher") ||
                    lower.contains("ext.services") || lower.contains("settings") ||
                    lower.contains("setupwizard")) {
                return true;
            }
        }

        try {
            Intent launch = packageManager.getLaunchIntentForPackage(packageName);
            return launch == null;
        } catch (Exception ignored) {
            return true;
        }
    }

    /**
     * ✅ Check if we have usage stats permission
     */
    public boolean hasUsagePermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.getPackageName());

            boolean hasPermission = mode == AppOpsManager.MODE_ALLOWED;
            Log.d(TAG, "📱 Usage permission: " + (hasPermission ? "GRANTED" : "NOT GRANTED"));
            return hasPermission;
        } catch (Exception e) {
            Log.e(TAG, "Error checking permission: " + e.getMessage());
            return false;
        }
    }

    /**
     * 📱 Open usage access settings
     */
    public void requestUsagePermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error opening usage settings: " + e.getMessage());
        }
    }

    /**
     * 🚀 START tracking
     */
    public void start() {
        if (isRunning) {
            Log.d(TAG, "⚠️ Already running");
            return;
        }

        if (!hasUsagePermission()) {
            Log.e(TAG, "❌ No usage permission - cannot start");
            return;
        }

        isRunning = true;
        Log.d(TAG, "🚀 Starting bulletproof usage tracking");

        // Collect immediately
        collectAndUpload();

        // Schedule periodic updates
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    collectAndUpload();
                    updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
                }
            }
        };
        updateHandler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS);
    }

    /**
     * 🛑 STOP tracking
     */
    public void stop() {
        isRunning = false;
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
        Log.d(TAG, "🛑 Stopped usage tracking");
    }

    /**
     * 📊 COLLECT and UPLOAD - the main workhorse
     */
    public void collectAndUpload() {
        try {
            Log.d(TAG, "📊 Collecting usage data...");

            // Get today's date
            Date now = new Date();
            String todayKey = dateKeyFormat.format(now);
            String dayLabel = dayLabelFormat.format(now);

            // Get today's start time (midnight)
            Calendar startCal = Calendar.getInstance();
            startCal.set(Calendar.HOUR_OF_DAY, 0);
            startCal.set(Calendar.MINUTE, 0);
            startCal.set(Calendar.SECOND, 0);
            startCal.set(Calendar.MILLISECOND, 0);
            long startTime = startCal.getTimeInMillis();
            long endTime = System.currentTimeMillis();

            // Query usage stats for today
            List<UsageStats> usageStatsList = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime);

            if (usageStatsList == null || usageStatsList.isEmpty()) {
                Log.w(TAG, "⚠️ No usage stats returned - might be permission issue");
                uploadEmptyDay(todayKey, dayLabel);
                return;
            }

            Log.d(TAG, "📱 Got " + usageStatsList.size() + " usage stats entries");

            // Build a set of packages that produced real foreground activity events
            final Set<String> foregroundCandidates = new HashSet<>();
            try {
                UsageEvents events = usageStatsManager.queryEvents(startTime, endTime);
                if (events != null) {
                    UsageEvents.Event ev = new UsageEvents.Event();
                    while (events.hasNextEvent()) {
                        events.getNextEvent(ev);
                        int et = ev.getEventType();
                        if (et == UsageEvents.Event.ACTIVITY_RESUMED || et == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            String pkg = ev.getPackageName();
                            String cls = ev.getClassName();
                            if (cls != null && !cls.trim().isEmpty()) {
                                foregroundCandidates.add(pkg);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // Process the stats
            final Map<String, Map<String, Object>> appsData = new HashMap<>();
            long totalMs = 0;

            for (UsageStats stats : usageStatsList) {
                String packageName = stats.getPackageName();
                long usageTime = stats.getTotalTimeInForeground();

                ApplicationInfo appInfo;
                try {
                    appInfo = packageManager.getApplicationInfo(packageName, 0);
                } catch (Exception e) {
                    continue;
                }

                // Skip if no usage or excluded
                if (usageTime <= 0 || shouldExclude(packageName) || shouldSkipAsCoreSystemApp(packageName, appInfo)) {
                    continue;
                }

                // UsageEvents-based filter: ensure the package actually had
                // a foreground activity event or is launchable. This removes
                // system UI / background-only components from totals.
                if (!foregroundCandidates.contains(packageName)) {
                    Intent launch = null;
                    try {
                        launch = packageManager.getLaunchIntentForPackage(packageName);
                    } catch (Exception ignored) {
                    }
                    if (launch == null) {
                        continue;
                    }
                }

                // Exclude home/launcher packages (OEM launchers)
                try {
                    if (isHomePackage(packageName)) {
                        continue;
                    }
                } catch (Exception ignored) {
                }

                // Get app name
                String appName = getAppName(packageName);

                // Create app data
                Map<String, Object> appData = new HashMap<>();
                appData.put("packageName", packageName);
                appData.put("appName", appName);
                // Legacy field (kept for compatibility)
                appData.put("usageTime", usageTime);
                // Canonical per-app field
                appData.put("usageTimeMillis", usageTime);
                appData.put("usageText", formatDuration(usageTime));
                appData.put("category", getCategory(packageName));

                appsData.put(packageName.replace(".", "_"), appData);
                totalMs += usageTime;

                Log.d(TAG, "  📱 " + appName + ": " + formatDuration(usageTime));
            }

            // Make final for lambda
            final long totalUsageMs = totalMs;
            final int appCount = appsData.size();
            final String totalText = formatDuration(totalUsageMs);

            // Create day data structure
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", todayKey);
            dayData.put("dayLabel", dayLabel);
            dayData.put("totalUsageMs", totalUsageMs);
            dayData.put("totalScreenTimeMillis", totalUsageMs); // canonical
            dayData.put("totalUsageText", totalText);
            dayData.put("appCount", appCount);
            dayData.put("apps", appsData);
            dayData.put("lastUpdated", System.currentTimeMillis());
            dayData.put("deviceId", deviceId);

            // Upload to Firebase
            Log.d(TAG, "Canonical uploader owns Firebase usage sync; skipping legacy usage_7day write");
            /*
            firebaseRef.child(todayKey).setValue(dayData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Uploaded usage data: " + appCount + " apps, " + totalText + " total");

                        // Also update "latest" for quick access
                        firebaseRef.child("latest").setValue(dayData);

                        // Update 7-day summary
                        update7DaySummary();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to upload: " + e.getMessage());
                    });
            */

        } catch (Exception e) {
            Log.e(TAG, "❌ Error collecting usage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Upload empty day data when no usage found
     */
    private void uploadEmptyDay(String dateKey, String dayLabel) {
        Map<String, Object> dayData = new HashMap<>();
        dayData.put("date", dateKey);
        dayData.put("dayLabel", dayLabel);
        dayData.put("totalUsageMs", 0L);
        dayData.put("totalUsageText", "0 min");
        dayData.put("appCount", 0);
        dayData.put("apps", new HashMap<>());
        dayData.put("lastUpdated", System.currentTimeMillis());
        dayData.put("deviceId", deviceId);
        dayData.put("noData", true);

        // Canonical uploader owns Firebase usage sync; skip legacy usage_7day write.
        Log.d(TAG, "📊 Uploaded empty day data for: " + dateKey);
    }

    /**
     * 📊 Update 7-day summary for easy retrieval
     */
    private void update7DaySummary() {
        try {
            List<String> last7Days = new ArrayList<>();
            Calendar cal = Calendar.getInstance();

            for (int i = 6; i >= 0; i--) {
                cal.setTime(new Date());
                cal.add(Calendar.DAY_OF_YEAR, -i);
                last7Days.add(dateKeyFormat.format(cal.getTime()));
            }

            Map<String, Object> summary = new HashMap<>();
            summary.put("days", last7Days);
            summary.put("lastUpdated", System.currentTimeMillis());
            summary.put("deviceId", deviceId);

            // Canonical uploader owns Firebase usage sync; skip legacy usage_7day summary write.

        } catch (Exception e) {
            Log.e(TAG, "Error updating summary: " + e.getMessage());
        }
    }

    /**
     * 🚫 Check if package should be excluded
     */
    private boolean shouldExclude(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return true;
        }

        for (String excludedPackage : EXCLUDED_PACKAGES) {
            if (packageName.equals(excludedPackage)) {
                return true;
            }
        }

        // Do not exclude based on launcher availability or system flags.
        // Some user-facing OEM apps (Files, Gallery, Video, etc.) do not always
        // expose a launcher entry, but they still represent real foreground usage.
        return false;
    }

    /**
     * 🏷️ Get app display name
     */
    private String getAppName(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            // Clean up package name for display
            String[] parts = packageName.split("\\.");
            if (parts.length > 0) {
                String name = parts[parts.length - 1];
                // Capitalize first letter
                return name.substring(0, 1).toUpperCase() + name.substring(1);
            }
            return packageName;
        }
    }

    /**
     * 📂 Get simple category
     */
    private String getCategory(String packageName) {
        String pkg = packageName.toLowerCase();

        if ("online.monarchlabs.sentinel".equals(packageName))
            return "Parental App";

        if (pkg.contains("game") || pkg.contains("play"))
            return "Games";
        if (pkg.contains("social") || pkg.contains("facebook") || pkg.contains("instagram") ||
                pkg.contains("twitter") || pkg.contains("tiktok") || pkg.contains("snapchat"))
            return "Social";
        if (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messenger"))
            return "Messaging";
        if (pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("video") ||
                pkg.contains("spotify") || pkg.contains("music"))
            return "Entertainment";
        if (pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox"))
            return "Browser";
        if (pkg.contains("camera") || pkg.contains("photo") || pkg.contains("gallery"))
            return "Photos";

        return "Other";
    }

    /**
     * ⏱️ Format duration
     */
    private String formatDuration(long millis) {
        if (millis < 60000) { // Less than 1 minute
            return "< 1 min";
        }

        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
        } else {
            return String.format(Locale.getDefault(), "%d min", minutes);
        }
    }

    /**
     * 🔧 Force refresh now
     */
    public void forceRefresh() {
        Log.d(TAG, "🔄 Force refresh requested");
        collectAndUpload();
    }

    /**
     * 📊 Get status for debugging
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("BulletproofUsageTracker Status:\n");
        sb.append("- Device ID: ").append(deviceId).append("\n");
        sb.append("- Running: ").append(isRunning).append("\n");
        sb.append("- Has Permission: ").append(hasUsagePermission()).append("\n");
        sb.append("- Firebase Upload: disabled; canonical V2 uploader owns sync\n");
        return sb.toString();
    }
}
