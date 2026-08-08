package online.monarchlabs.sentinel.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import online.monarchlabs.sentinel.models.SUsageAppInfo;
import online.monarchlabs.sentinel.models.SUsageDailyData;
import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.SessionManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manager class for fetching and processing app usage statistics.
 * Uses UsageEvents API for accurate per-day data (SUSAGE methodology).
 *
 * IMPORTANT: We only use UsageEvents because queryUsageStats returns CUMULATIVE
 * foreground time (total since install), not time for the specific date range.
 */
public class SUsageDataManager {
    private static final String TAG = "SUsageDataManager";
    private static final long CURRENT_PERIOD_GRACE_MS = 5000;
    private static final String PREF_USAGE_UPLOAD = "usage_upload_state";
    public static final int USAGE_SCHEMA_VERSION = 5;
    private static final String KEY_DAY_SCHEMA_PREFIX = "day_schema_";
    private static final String KEY_DAY_SUMMARY_HASH_PREFIX = "day_summary_hash_";
    private static final String KEY_DAY_APP_KEYS_PREFIX = "day_app_keys_";
    private static final String KEY_APP_DURATION_PREFIX = "app_duration_";
    private static final String KEY_BOOTSTRAP_CONNECTION_PREFIX = "bootstrap_connection_";
    private static final String KEY_BOOTSTRAP_SCHEMA_PREFIX = "bootstrap_schema_";
    private static final String KEY_BOOTSTRAP_GENERATION_PREFIX = "bootstrap_generation_";
    private static final String KEY_HISTORY_REVISION_PREFIX = "history_revision_";
    private static final String KEY_FINALIZED_DAY_PREFIX = "finalized_day_";
    private static final String KEY_CATALOG_HASH_PREFIX = "catalog_hash_";
    private static final String KEY_APP_STATE_HASH_PREFIX = "app_state_hash_";

    private static SUsageDataManager instance;
    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private boolean uploadInProgress;
    private boolean uploadQueued;
    private boolean uploadQueuedForceFullSnapshot;

    // Categories for app classification
    private static final Map<String, String> CATEGORY_MAP = new HashMap<>();

    // Core system packages to exclude from usage totals.
    // We avoid a hardcoded include list so OEM-specific user apps are counted too.
    private static final List<String> EXCLUDED_PACKAGES = new ArrayList<>();

    static {
        // Communication apps
        CATEGORY_MAP.put("com.whatsapp", "Communication");
        CATEGORY_MAP.put("com.facebook.orca", "Communication");
        CATEGORY_MAP.put("org.telegram.messenger", "Communication");
        CATEGORY_MAP.put("com.discord", "Communication");
        CATEGORY_MAP.put("com.skype.raider", "Communication");
        CATEGORY_MAP.put("com.viber.voip", "Communication");
        CATEGORY_MAP.put("com.google.android.apps.messaging", "Communication");

        // Entertainment apps
        CATEGORY_MAP.put("com.google.android.youtube", "Entertainment");
        CATEGORY_MAP.put("com.netflix.mediaclient", "Entertainment");
        CATEGORY_MAP.put("com.spotify.music", "Entertainment");
        CATEGORY_MAP.put("com.amazon.avod.thirdpartyclient", "Entertainment");
        CATEGORY_MAP.put("com.disney.disneyplus", "Entertainment");

        // Social apps
        CATEGORY_MAP.put("com.instagram.android", "Entertainment");
        CATEGORY_MAP.put("com.facebook.katana", "Entertainment");
        CATEGORY_MAP.put("com.twitter.android", "Entertainment");
        CATEGORY_MAP.put("com.zhiliaoapp.musically", "Entertainment"); // TikTok
        CATEGORY_MAP.put("com.reddit.frontpage", "Entertainment");
        CATEGORY_MAP.put("com.snapchat.android", "Entertainment");

        // Sentinel app should be visible in reports
        CATEGORY_MAP.put("online.monarchlabs.sentinel", "Parental App");

        // Games (common prefixes)
        CATEGORY_MAP.put("com.supercell", "Games");
        CATEGORY_MAP.put("com.king", "Games");
        CATEGORY_MAP.put("com.miniclip", "Games");
        CATEGORY_MAP.put("com.ea", "Games");
        CATEGORY_MAP.put("com.gameloft", "Games");

        // Core system services and components only.
        EXCLUDED_PACKAGES.add("android");
        EXCLUDED_PACKAGES.add("com.android.systemui");
        EXCLUDED_PACKAGES.add("com.android.launcher3");
        EXCLUDED_PACKAGES.add("com.android.permissioncontroller");
        EXCLUDED_PACKAGES.add("com.android.providers.settings");
        EXCLUDED_PACKAGES.add("com.android.shell");
        EXCLUDED_PACKAGES.add("com.android.externalstorage");
        EXCLUDED_PACKAGES.add("com.android.documentsui");
        EXCLUDED_PACKAGES.add("com.android.packageinstaller");
        EXCLUDED_PACKAGES.add("com.android.server.telecom");
        EXCLUDED_PACKAGES.add("com.google.android.gms");
        EXCLUDED_PACKAGES.add("com.google.android.gsf");
        EXCLUDED_PACKAGES.add("com.google.android.ext");
        EXCLUDED_PACKAGES.add("com.google.android.apps.wellbeing");
    }

    private SUsageDataManager(Context context) {
        this.context = context.getApplicationContext();
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = context.getPackageManager();
    }

    public static synchronized SUsageDataManager getInstance(Context context) {
        if (instance == null) {
            instance = new SUsageDataManager(context);
        }
        return instance;
    }

    public static void resetUploadStateForNewRelationship(Context context) {
        clearUploadState(context.getApplicationContext());
    }

    public static void clearForDisconnection(Context context) {
        Context appContext = context.getApplicationContext();
        clearUploadState(appContext);
        PipUsageTracker.getInstance(appContext).resetForNewRelationship();
        ChildUsageLedgerManager.clear(appContext);
    }

    private static void clearUploadState(Context appContext) {
        appContext.getSharedPreferences(PREF_USAGE_UPLOAD, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }
    /**
     * Get usage statistics for today
     */
    public SUsageDailyData getTodayUsage() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        return ChildUsageLedgerManager.getInstance(context).mergeDailyUsage(
                getUsageForPeriodUsingEvents(
                        calendar.getTimeInMillis(),
                        System.currentTimeMillis(),
                        Calendar.getInstance()));
    }
    /**
     * Return today's usage for one package, including its unfinished foreground
     * session. UsageStats totals can lag until the app moves to the background.
     */
    public long getTodayForegroundUsageMillis(String packageName) {
        if (usageStatsManager == null || packageName == null || packageName.isEmpty()) {
            return 0;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        long startTime = calendar.getTimeInMillis();
        long now = System.currentTimeMillis();
        long totalUsage = 0;
        Long foregroundStart = null;

        try {
            UsageEvents events = usageStatsManager.queryEvents(startTime, now);
            if (events == null) {
                return 0;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (!packageName.equals(event.getPackageName())) {
                    continue;
                }

                int eventType = event.getEventType();
                if (eventType == UsageEvents.Event.ACTIVITY_RESUMED
                        || eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (foregroundStart == null) {
                        foregroundStart = Math.max(startTime, event.getTimeStamp());
                    }
                } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED
                        || eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    if (foregroundStart != null && event.getTimeStamp() > foregroundStart) {
                        totalUsage += event.getTimeStamp() - foregroundStart;
                        foregroundStart = null;
                    }
                }
            }

            if (foregroundStart != null && now > foregroundStart) {
                totalUsage += now - foregroundStart;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read live usage for " + packageName + ": " + e.getMessage());
        }

        String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
        return totalUsage + PipUsageTracker.getInstance(context)
                .getUsageMillis(todayKey, packageName);
    }

    /**
     * Get weekly usage data (last 7 days)
     */
    public List<SUsageDailyData> getWeeklyUsage() {
        List<SUsageDailyData> weeklyData = new ArrayList<>();

        Calendar calendar = Calendar.getInstance();

        // Get data for last 7 days (including today)
        for (int i = 6; i >= 0; i--) {
            Calendar dayCalendar = (Calendar) calendar.clone();
            dayCalendar.add(Calendar.DAY_OF_YEAR, -i);
            dayCalendar.set(Calendar.HOUR_OF_DAY, 0);
            dayCalendar.set(Calendar.MINUTE, 0);
            dayCalendar.set(Calendar.SECOND, 0);
            dayCalendar.set(Calendar.MILLISECOND, 0);

            long startTime = dayCalendar.getTimeInMillis();

            Calendar endCalendar = (Calendar) dayCalendar.clone();
            endCalendar.add(Calendar.DAY_OF_YEAR, 1);
            long endTime = endCalendar.getTimeInMillis();

            // For today, use current time as end
            if (i == 0) {
                endTime = System.currentTimeMillis();
            }

            SUsageDailyData dailyUsage = getUsageForPeriodUsingEvents(startTime, endTime, dayCalendar);
            weeklyData.add(dailyUsage);
        }

        return ChildUsageLedgerManager.getInstance(context).mergeUsageWindow(weeklyData);
    }

    /**
     * Get usage for a specific time period using UsageEvents for accurate per-day
     * data.
     * This method calculates foreground time by tracking ACTIVITY_RESUMED and
     * ACTIVITY_PAUSED events.
     *
     * IMPORTANT: We only use UsageEvents because queryUsageStats and
     * queryAndAggregateUsageStats
     * return CUMULATIVE foreground time, not time for the specific date range.
     */
    private SUsageDailyData getUsageForPeriodUsingEvents(long startTime, long endTime, Calendar date) {
        SUsageDailyData dailyUsage = new SUsageDailyData(date);
        long collectionTime = System.currentTimeMillis();
        boolean includesCurrentTime = startTime <= collectionTime
                && endTime >= collectionTime - CURRENT_PERIOD_GRACE_MS;

        if (usageStatsManager == null) {
            Log.e(TAG, "UsageStatsManager is null");
            return dailyUsage;
        }

        Map<String, Long> usageMap = new HashMap<>();
        final Set<String> foregroundCandidates = new HashSet<>();

        // Use events-based calculation - this is the ONLY accurate method for per-day
        // data
        UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);

        if (usageEvents != null) {
            Map<String, Long> lastResumeTime = new HashMap<>();

            UsageEvents.Event event = new UsageEvents.Event();
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                String packageName = event.getPackageName();
                long eventTime = event.getTimeStamp();

                // Only process events within our time range
                if (eventTime < startTime || eventTime > endTime) {
                    continue;
                }

                int eventType = event.getEventType();

                if (eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    // App moved to foreground
                    lastResumeTime.put(packageName, eventTime);

                    // Mark candidate if className exists (real activity)
                    String cls = event.getClassName();
                    if (cls != null && !cls.trim().isEmpty()) {
                        foregroundCandidates.add(packageName);
                    }
                } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                        eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    // App moved to background
                    Long resumeTime = lastResumeTime.get(packageName);
                    if (resumeTime != null && resumeTime >= startTime) {
                        long duration = eventTime - resumeTime;
                        if (duration > 0 && duration < 24 * 60 * 60 * 1000) { // Max 24 hours
                            long currentTotal = usageMap.getOrDefault(packageName, 0L);
                            usageMap.put(packageName, currentTotal + duration);
                        }
                    }
                    lastResumeTime.remove(packageName);
                }
            }

            // Handle apps that are still in foreground (only for today/current period)
            if (includesCurrentTime) {
                for (Map.Entry<String, Long> entry : lastResumeTime.entrySet()) {
                    long resumeTime = entry.getValue();
                    if (resumeTime >= startTime) {
                        long duration = collectionTime - resumeTime;
                        if (duration > 0 && duration < 24 * 60 * 60 * 1000) { // Max 24 hours sanity check
                            long currentTotal = usageMap.getOrDefault(entry.getKey(), 0L);
                            usageMap.put(entry.getKey(), currentTotal + duration);
                        }
                    }
                }
            }
            // foregroundCandidates is available here for later checks
        }

        String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date.getTime());
        Map<String, Long> pipUsage = PipUsageTracker.getInstance(context).getUsageForDate(dateKey);
        for (Map.Entry<String, Long> entry : pipUsage.entrySet()) {
            usageMap.put(entry.getKey(), usageMap.getOrDefault(entry.getKey(), 0L) + entry.getValue());
        }

        // Convert to SUsageAppInfo and calculate totals
        long totalTime = 0;
        long communicationTime = 0;
        long entertainmentTime = 0;
        long gamesTime = 0;

        List<SUsageAppInfo> appList = new ArrayList<>();

        for (Map.Entry<String, Long> entry : usageMap.entrySet()) {
            String packageName = entry.getKey();
            long usageTime = entry.getValue();

            // Skip apps with almost no usage (less than 1 second), but include
            // our own app so the parent report shows Sentinel even for brief
            // foreground events (helps visibility during testing).
            if (usageTime < 1000 && !packageName.equals(context.getPackageName())) {
                continue;
            }

            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);

                // Skip only core system packages; keep user-facing OEM/system apps.
                if (shouldSkipAsCoreSystemApp(packageName, appInfo)) {
                    continue;
                }

                // UsageEvents-based filter: include only packages that produced
                // a foreground activity event (ACTIVITY_RESUMED / MOVE_TO_FOREGROUND)
                // or have a launch intent. This avoids counting system UI and
                // background-only components that nevertheless appear in
                // UsageStats results.
                boolean isForegroundCandidate = foregroundCandidates.contains(packageName);

                if (!isForegroundCandidate) {
                    // fallback: include if app is launchable (user-facing)
                    Intent launch = null;
                    try {
                        launch = packageManager.getLaunchIntentForPackage(packageName);
                    } catch (Exception ignored) {
                    }
                    if (launch == null) {
                        continue; // skip background-only package
                    }
                }

                // Exclude any home/launcher packages (various OEM launchers)
                try {
                    if (isHomePackage(packageName)) {
                        continue;
                    }
                } catch (Exception ignored) {
                }

                String appName = packageManager.getApplicationLabel(appInfo).toString();
                String category = getAppCategory(packageName);

                SUsageAppInfo appUsageInfo = new SUsageAppInfo(
                        packageName, appName, usageTime, category);
                appUsageInfo.setInstalled(true);
                appUsageInfo.setStatus("installed");

                appList.add(appUsageInfo);
                dailyUsage.addAppUsage(appUsageInfo);

                totalTime += usageTime;

                // Categorize time
                switch (category) {
                    case "Communication":
                        communicationTime += usageTime;
                        break;
                    case "Entertainment":
                        entertainmentTime += usageTime;
                        break;
                    case "Games":
                        gamesTime += usageTime;
                        break;
                }

            } catch (PackageManager.NameNotFoundException e) {
                if (EXCLUDED_PACKAGES.contains(packageName)) {
                    continue;
                }

                ChildUsageLedgerManager ledger = ChildUsageLedgerManager.getInstance(context);
                String cachedName = ledger.getCachedAppName(packageName);
                String appName = cachedName != null && !cachedName.isEmpty()
                        ? cachedName
                        : fallbackAppName(packageName);
                String cachedCategory = ledger.getCachedCategory(packageName);
                String category = cachedCategory != null && !cachedCategory.isEmpty()
                        ? cachedCategory
                        : getAppCategory(packageName);

                SUsageAppInfo appUsageInfo = new SUsageAppInfo(
                        packageName, appName, usageTime, category);
                appUsageInfo.setInstalled(false);
                appUsageInfo.setStatus("uninstalled");

                appList.add(appUsageInfo);
                dailyUsage.addAppUsage(appUsageInfo);
                totalTime += usageTime;

                switch (category) {
                    case "Communication":
                        communicationTime += usageTime;
                        break;
                    case "Entertainment":
                        entertainmentTime += usageTime;
                        break;
                    case "Games":
                        gamesTime += usageTime;
                        break;
                }
            }
        }

        dailyUsage.setTotalScreenTimeMillis(totalTime);
        dailyUsage.setCommunicationTimeMillis(communicationTime);
        dailyUsage.setEntertainmentTimeMillis(entertainmentTime);
        dailyUsage.setGamesTimeMillis(gamesTime);
        dailyUsage.setOtherTimeMillis(totalTime - communicationTime - entertainmentTime - gamesTime);
        dailyUsage.setLastUpdated(System.currentTimeMillis());

        Log.d(TAG, "Collected usage for " + dailyUsage.getDateKey() + ": " +
                appList.size() + " apps, total " + dailyUsage.getFormattedTotalTime());

        return dailyUsage;
    }

    public void recordAppUninstalled(String packageName) {
        recordAppUninstalled(packageName, null, null);
    }

    public void recordAppUninstalled(String packageName, String appName, String iconBase64) {
        long rawTodayUsage = getTodayForegroundUsageMillis(packageName);
        ChildUsageLedgerManager.getInstance(context)
                .markAppUninstalled(packageName, rawTodayUsage, appName, iconBase64);
    }

    public void recordAppInstalled(String packageName, String appName, String category) {
        recordAppInstalled(packageName, appName, category, null);
    }

    public void recordAppInstalled(String packageName, String appName,
            String category, String iconBase64) {
        long rawTodayUsage = getTodayForegroundUsageMillis(packageName);
        ChildUsageLedgerManager.getInstance(context)
                .markAppInstalled(packageName, appName, category, iconBase64, rawTodayUsage);
    }

    private String fallbackAppName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "Unknown app";
        }
        int dot = packageName.lastIndexOf('.');
        return dot >= 0 && dot < packageName.length() - 1
                ? packageName.substring(dot + 1)
                : packageName;
    }

    /**
     * Determine app category based on package name
     */
    private String getAppCategory(String packageName) {
        // Check exact match first
        if (CATEGORY_MAP.containsKey(packageName)) {
            return CATEGORY_MAP.get(packageName);
        }

        // Check prefix matches (for games from known publishers)
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            if (packageName.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Try to determine from package name patterns
        String lowerPackage = packageName.toLowerCase();
        if (lowerPackage.contains("game") || lowerPackage.contains("play")) {
            return "Games";
        }
        if (lowerPackage.contains("chat") || lowerPackage.contains("message")) {
            return "Communication";
        }
        if (lowerPackage.contains("video") || lowerPackage.contains("music") ||
                lowerPackage.contains("stream")) {
            return "Entertainment";
        }

        return "Other";
    }

    /**
     * Upload canonical usage data to Firebase
     */
    public void uploadToFirebase(String deviceId, final OnUploadCompleteListener listener) {
        uploadToFirebase(deviceId, false, listener);
    }

    public void uploadToFirebase(String deviceId, boolean forceFullSnapshot,
            final OnUploadCompleteListener listener) {
        if (deviceId == null || deviceId.isEmpty()) {
            if (listener != null) {
                listener.onError("Device ID is empty");
            }
            return;
        }

        Log.d(TAG, "Uploading canonical usage data for device: " + deviceId
                + ", forceFullSnapshot=" + forceFullSnapshot);
        uploadTodayUsageToV2(deviceId, forceFullSnapshot, listener);
    }

    private void uploadTodayUsageToV2(String deviceId, boolean forceFullSnapshot,
            final OnUploadCompleteListener listener) {
        synchronized (this) {
            if (uploadInProgress) {
                uploadQueued = true;
                uploadQueuedForceFullSnapshot =
                        uploadQueuedForceFullSnapshot || forceFullSnapshot;
                Log.d(TAG, "Usage upload already running; queued one follow-up sync");
                if (listener != null) {
                    listener.onSuccess();
                }
                return;
            }
            uploadInProgress = true;
        }

        executorService.execute(() -> {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREF_USAGE_UPLOAD, Context.MODE_PRIVATE);
            SessionManager session = new SessionManager(context);
            String connectionId = session.getConnectionId();
            if (connectionId == null || connectionId.trim().isEmpty()) {
                if (listener != null) {
                    listener.onError("No active child connection");
                }
                finishUsageUpload(deviceId);
                return;
            }

            boolean bootstrapNeeded = !connectionId.equals(prefs.getString(
                    KEY_BOOTSTRAP_CONNECTION_PREFIX + deviceId, ""))
                    || prefs.getInt(KEY_BOOTSTRAP_SCHEMA_PREFIX + deviceId, 0)
                    != USAGE_SCHEMA_VERSION;
            if (forceFullSnapshot) {
                Log.d(TAG, "Manual refresh requested; keeping it as a today delta");
            }

            try {
                String todayKey = new SimpleDateFormat(
                        "yyyy-MM-dd", Locale.US).format(new Date());
                List<SUsageDailyData> historical = getPreviousSixDaysUsage();
                List<SUsageDailyData> usageToCheck = new ArrayList<>();
                SUsageDailyData todayUsage = getTodayUsage();
                if (todayUsage != null) {
                    usageToCheck.add(todayUsage);
                }

                if (bootstrapNeeded) {
                    usageToCheck.addAll(historical);
                } else {
                    for (SUsageDailyData daily : historical) {
                        String finalizedKey = KEY_FINALIZED_DAY_PREFIX
                                + deviceId + "_" + daily.getDateKey();
                        if (!prefs.getBoolean(finalizedKey, false)) {
                            usageToCheck.add(daily);
                        }
                    }
                }

                if (usageToCheck.isEmpty()) {
                    if (listener != null) {
                        listener.onError("No usage data available");
                    }
                    finishUsageUpload(deviceId);
                    return;
                }

                Map<String, Object> usageUpdates = new HashMap<>();
                Map<String, Object> catalogUpdates = new HashMap<>();
                PendingPreferenceState pendingState =
                        new PendingPreferenceState();
                boolean historicalFinalization = false;
                for (SUsageDailyData daily : usageToCheck) {
                    appendDailyUsagePatch(
                            deviceId,
                            daily,
                            prefs,
                            usageUpdates,
                            pendingState,
                            bootstrapNeeded);
                    appendAppCatalogPatch(
                            deviceId,
                            daily,
                            prefs,
                            catalogUpdates,
                            pendingState);
                    if (!todayKey.equals(daily.getDateKey())) {
                        historicalFinalization = true;
                        pendingState.booleans.put(
                                KEY_FINALIZED_DAY_PREFIX + deviceId + "_"
                                        + daily.getDateKey(),
                                true);
                    }
                }

                int historyRevision = prefs.getInt(
                        KEY_HISTORY_REVISION_PREFIX + deviceId, 0);
                if (bootstrapNeeded || historicalFinalization) {
                    historyRevision = Math.max(1, historyRevision + 1);
                    pendingState.ints.put(
                            KEY_HISTORY_REVISION_PREFIX + deviceId,
                            historyRevision);
                }
                String historyGeneration = connectionId + ":usage:"
                        + USAGE_SCHEMA_VERSION + ":" + historyRevision;

                Map<String, Object> bootstrapMetadata = null;
                if (bootstrapNeeded || historicalFinalization) {
                    bootstrapMetadata = new HashMap<>();
                    bootstrapMetadata.put("connectionId", connectionId);
                    bootstrapMetadata.put("status", "complete");
                    bootstrapMetadata.put("schemaVersion", USAGE_SCHEMA_VERSION);
                    if (bootstrapNeeded) {
                        bootstrapMetadata.put(
                                "completedAt", System.currentTimeMillis());
                    }
                    bootstrapMetadata.put(
                            "historyUpdatedAt", System.currentTimeMillis());
                    bootstrapMetadata.put(
                            "historyGeneration", historyGeneration);
                    bootstrapMetadata.put(
                            "uploadedDayCount", usageToCheck.size());

                    if (bootstrapNeeded) {
                        pendingState.strings.put(
                                KEY_BOOTSTRAP_CONNECTION_PREFIX + deviceId,
                                connectionId);
                        pendingState.ints.put(
                                KEY_BOOTSTRAP_SCHEMA_PREFIX + deviceId,
                                USAGE_SCHEMA_VERSION);
                    }
                    pendingState.strings.put(
                            KEY_BOOTSTRAP_GENERATION_PREFIX + deviceId,
                            historyGeneration);
                }

                Log.d(TAG, "Uploading usage patch paths="
                        + usageUpdates.size() + ", catalog paths="
                        + catalogUpdates.size() + ", bootstrap="
                        + bootstrapNeeded + ", historyChanged="
                        + historicalFinalization);
                FirebaseSchemaV2Repository.syncUsagePatch(
                                deviceId,
                                usageUpdates,
                                catalogUpdates,
                                bootstrapMetadata)
                        .addOnSuccessListener(ignored -> {
                            SharedPreferences.Editor editor = prefs.edit();
                            pendingState.applyTo(editor);
                            editor.putLong(
                                    "last_success_at_" + deviceId,
                                    System.currentTimeMillis());
                            editor.apply();
                            Log.d(TAG, "Canonical v2 usage sync completed");
                            if (listener != null) {
                                listener.onSuccess();
                            }
                            finishUsageUpload(deviceId);
                        })
                        .addOnFailureListener(error -> {
                            Log.e(TAG, "Failed canonical v2 usage sync: "
                                    + error.getMessage());
                            if (listener != null) {
                                listener.onError(error.getMessage());
                            }
                            finishUsageUpload(deviceId);
                        });
            } catch (Exception error) {
                Log.e(TAG, "Error performing canonical v2 usage upload: "
                        + error.getMessage(), error);
                if (listener != null) {
                    listener.onError(error.getMessage());
                }
                finishUsageUpload(deviceId);
            }
        });
    }
    private void appendDailyUsagePatch(
            String deviceId,
            SUsageDailyData daily,
            SharedPreferences prefs,
            Map<String, Object> updates,
            PendingPreferenceState pendingState,
            boolean forceFullSnapshot) {
        if (daily == null || daily.getDateKey() == null || daily.getDateKey().isEmpty()) {
            return;
        }

        String dateKey = daily.getDateKey();
        String stateScope = deviceId + "_" + dateKey;
        String schemaKey = KEY_DAY_SCHEMA_PREFIX + stateScope;
        String summaryHashKey = KEY_DAY_SUMMARY_HASH_PREFIX + stateScope;
        String appKeysPreferenceKey = KEY_DAY_APP_KEYS_PREFIX + stateScope;
        boolean compactSchemaReady = !forceFullSnapshot
                && prefs.getInt(schemaKey, 0) >= USAGE_SCHEMA_VERSION;

        Map<String, Object> summary = buildDailySummary(daily);
        String summaryHash = stableString(summary);
        boolean summaryChanged = !summaryHash.equals(prefs.getString(summaryHashKey, ""));

        Map<String, Long> currentDurations = new HashMap<>();
        Map<String, Map<String, Object>> currentStates = new HashMap<>();
        for (SUsageAppInfo app : daily.getAppList()) {
            if (app == null || app.getPackageName() == null || app.getPackageName().isEmpty()) {
                continue;
            }
            String appKey = sanitizeFirebaseKey(app.getPackageName());
            currentDurations.put(appKey, Math.max(0L, app.getUsageTimeMillis()));
            Map<String, Object> state = buildAppState(app);
            if (!state.isEmpty()) {
                currentStates.put(appKey, state);
            }
        }

        boolean appsChanged = false;

        if (!compactSchemaReady) {
            Map<String, Object> compactApps = new HashMap<>();
            for (Map.Entry<String, Long> entry : currentDurations.entrySet()) {
                compactApps.put(entry.getKey(), entry.getValue());
            }
            updates.put(dateKey + "/apps", compactApps.isEmpty() ? null : compactApps);
            appsChanged = true;
        } else {
            for (Map.Entry<String, Long> entry : currentDurations.entrySet()) {
                String durationKey = appDurationPreferenceKey(
                        deviceId,
                        dateKey,
                        entry.getKey());
                long previousDuration = prefs.getLong(durationKey, Long.MIN_VALUE);
                if (previousDuration != entry.getValue()) {
                    updates.put(
                            dateKey + "/apps/" + entry.getKey(),
                            entry.getValue());
                    appsChanged = true;
                }
            }

        }

        boolean statesChanged = appendAppStatePatch(
                deviceId,
                dateKey,
                currentStates,
                prefs,
                updates,
                pendingState);

        if (summaryChanged || appsChanged || statesChanged || !compactSchemaReady) {
            for (Map.Entry<String, Object> entry : summary.entrySet()) {
                updates.put(dateKey + "/" + entry.getKey(), entry.getValue());
            }
            updates.put(dateKey + "/lastUpdated", System.currentTimeMillis());
            updates.put(dateKey + "/schemaVersion", USAGE_SCHEMA_VERSION);
        }

        if (summaryChanged || appsChanged || statesChanged || !compactSchemaReady) {
            pendingState.strings.put(summaryHashKey, summaryHash);
            pendingState.strings.put(
                    appKeysPreferenceKey,
                    encodeAppKeys(currentDurations.keySet()));
            pendingState.ints.put(schemaKey, USAGE_SCHEMA_VERSION);
            for (Map.Entry<String, Long> entry : currentDurations.entrySet()) {
                pendingState.longs.put(
                        appDurationPreferenceKey(deviceId, dateKey, entry.getKey()),
                        entry.getValue());
            }
        }
    }

    private boolean appendAppStatePatch(
            String deviceId,
            String dateKey,
            Map<String, Map<String, Object>> states,
            SharedPreferences prefs,
            Map<String, Object> updates,
            PendingPreferenceState pendingState) {
        boolean changed = false;
        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            String appKey = entry.getKey();
            Map<String, Object> state = entry.getValue();
            String hashKey = KEY_APP_STATE_HASH_PREFIX + deviceId + "_" + dateKey + "_" + appKey;
            String stateHash = stableString(state);
            if (stateHash.equals(prefs.getString(hashKey, ""))) {
                continue;
            }
            for (Map.Entry<String, Object> stateEntry : state.entrySet()) {
                updates.put(
                        dateKey + "/appStates/" + appKey + "/" + stateEntry.getKey(),
                        stateEntry.getValue());
            }
            updates.put(dateKey + "/appStates/" + appKey + "/stateUpdatedAt",
                    System.currentTimeMillis());
            pendingState.strings.put(hashKey, stateHash);
            changed = true;
        }
        return changed;
    }

    private Map<String, Object> buildAppState(SUsageAppInfo app) {
        Map<String, Object> state = new HashMap<>();
        if (app == null) {
            return state;
        }
        boolean hasInstallEventState = app.isUninstalled()
                || app.getUninstalledAt() > 0L
                || app.getReinstalledAt() > 0L;
        if (!hasInstallEventState) {
            return state;
        }
        state.put("installed", app.isInstalled());
        state.put("status", app.isUninstalled() ? "uninstalled" : "installed");
        if (app.getUninstalledAt() > 0L) {
            state.put("uninstalledAt", app.getUninstalledAt());
        }
        if (app.getReinstalledAt() > 0L) {
            state.put("reinstalledAt", app.getReinstalledAt());
        }
        return state;
    }

    private void appendAppCatalogPatch(
            String deviceId,
            SUsageDailyData daily,
            SharedPreferences prefs,
            Map<String, Object> catalogUpdates,
            PendingPreferenceState pendingState) {
        if (daily == null) {
            return;
        }
        for (SUsageAppInfo app : daily.getAppList()) {
            if (app == null || app.getPackageName() == null
                    || app.getPackageName().isEmpty()) {
                continue;
            }
            String appKey = sanitizeFirebaseKey(app.getPackageName());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("packageName", app.getPackageName());
            metadata.put("appName",
                    app.getAppName() != null ? app.getAppName() : app.getPackageName());
            metadata.put("category",
                    app.getCategory() != null ? app.getCategory() : "Other");
            metadata.put("schemaVersion", 1);
            String metadataHash = stableString(metadata);
            String hashKey = KEY_CATALOG_HASH_PREFIX + deviceId + "_" + appKey;
            if (metadataHash.equals(prefs.getString(hashKey, ""))) {
                continue;
            }
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                catalogUpdates.put(
                        appKey + "/" + entry.getKey(), entry.getValue());
            }
            catalogUpdates.put(
                    appKey + "/updatedAt", System.currentTimeMillis());
            pendingState.strings.put(hashKey, metadataHash);
        }
    }
    private Map<String, Object> buildDailySummary(SUsageDailyData daily) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("dateKey", daily.getDateKey());
        summary.put("totalScreenTimeMillis", daily.getTotalScreenTimeMillis());
        summary.put("communicationTimeMillis", daily.getCommunicationTimeMillis());
        summary.put("entertainmentTimeMillis", daily.getEntertainmentTimeMillis());
        summary.put("gamesTimeMillis", daily.getGamesTimeMillis());
        summary.put("otherTimeMillis", daily.getOtherTimeMillis());
        return summary;
    }

    private Set<String> decodeAppKeys(String encodedKeys) {
        Set<String> keys = new HashSet<>();
        if (encodedKeys == null || encodedKeys.isEmpty()) {
            return keys;
        }
        for (String key : encodedKeys.split(",")) {
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    private String encodeAppKeys(Set<String> appKeys) {
        List<String> sortedKeys = new ArrayList<>(appKeys);
        Collections.sort(sortedKeys);
        return android.text.TextUtils.join(",", sortedKeys);
    }

    private String appDurationPreferenceKey(String deviceId, String dateKey, String appKey) {
        return KEY_APP_DURATION_PREFIX + deviceId + "_" + dateKey + "_" + appKey;
    }
    private List<SUsageDailyData> getPreviousSixDaysUsage() {
        List<SUsageDailyData> historicalUsage = new ArrayList<>();
        Calendar calendar = Calendar.getInstance();

        for (int daysBack = 6; daysBack >= 1; daysBack--) {
            Calendar day = (Calendar) calendar.clone();
            day.add(Calendar.DAY_OF_YEAR, -daysBack);
            day.set(Calendar.HOUR_OF_DAY, 0);
            day.set(Calendar.MINUTE, 0);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);

            Calendar nextDay = (Calendar) day.clone();
            nextDay.add(Calendar.DAY_OF_YEAR, 1);
            historicalUsage.add(ChildUsageLedgerManager.getInstance(context).mergeDailyUsage(
                    getUsageForPeriodUsingEvents(
                            day.getTimeInMillis(),
                            nextDay.getTimeInMillis(),
                            day)));
        }
        return historicalUsage;
    }
    private void finishUsageUpload(String deviceId) {
        boolean shouldRunQueued;
        boolean forceQueuedSnapshot;
        synchronized (this) {
            uploadInProgress = false;
            shouldRunQueued = uploadQueued;
            forceQueuedSnapshot = uploadQueuedForceFullSnapshot;
            uploadQueued = false;
            uploadQueuedForceFullSnapshot = false;
        }
        if (shouldRunQueued) {
            Log.d(TAG, "Running queued usage sync after current upload finished");
            uploadTodayUsageToV2(deviceId, forceQueuedSnapshot, null);
        }
    }


    private String stableString(Object value) {
        if (value instanceof Map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            StringBuilder builder = new StringBuilder("{");
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                builder.append(entry.getKey())
                        .append(":")
                        .append(stableString(entry.getValue()))
                        .append(",");
            }
            return builder.append("}").toString();
        }
        return String.valueOf(value);
    }

    /**
     * Get total weekly screen time
     */
    public long getTotalWeeklyScreenTime() {
        List<SUsageDailyData> weeklyData = getWeeklyUsage();
        long total = 0;
        for (SUsageDailyData daily : weeklyData) {
            total += daily.getTotalScreenTimeMillis();
        }
        return total;
    }

    /**
     * Get average daily screen time for the week
     */
    public String getAverageDailyTime() {
        long totalWeekly = getTotalWeeklyScreenTime();
        long averageDaily = totalWeekly / 7;

        long hours = averageDaily / (1000 * 60 * 60);
        long minutes = (averageDaily / (1000 * 60)) % 60;

        if (hours > 0) {
            return hours + " hrs " + minutes + " min";
        }
        return minutes + " min";
    }

    /**
     * Listener interface for upload completion
     */
    private static final class PendingPreferenceState {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Long> longs = new HashMap<>();
        final Map<String, Integer> ints = new HashMap<>();
        final Map<String, Boolean> booleans = new HashMap<>();
        final Set<String> removals = new HashSet<>();

        void applyTo(SharedPreferences.Editor editor) {
            for (String key : removals) {
                editor.remove(key);
            }
            for (Map.Entry<String, String> entry : strings.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Long> entry : longs.entrySet()) {
                editor.putLong(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Integer> entry : ints.entrySet()) {
                editor.putInt(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Boolean> entry : booleans.entrySet()) {
                editor.putBoolean(entry.getKey(), entry.getValue());
            }
        }
    }
    public interface OnUploadCompleteListener {
        void onSuccess();

        void onError(String error);
    }

    /**
     * Sanitize a string to be used as a Firebase key.
     * Firebase does NOT allow: '.', '/', '#', '$', '[', ']'
     * Replaces all illegal characters with underscores.
     */
    private String sanitizeFirebaseKey(String key) {
        if (key == null || key.isEmpty()) {
            return "unknown";
        }
        // Replace all illegal Firebase characters with underscore
        return key.replaceAll("[.#$\\[\\]/]", "_");
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
     * Exclude core system tools, but keep launchable OEM apps that represent real user usage.
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

        // If it is a system app but does not expose a launcher entry, it is usually a
        // background/tool component rather than a user app.
        try {
            Intent launch = packageManager.getLaunchIntentForPackage(packageName);
            return launch == null;
        } catch (Exception ignored) {
            return true;
        }
    }
}
