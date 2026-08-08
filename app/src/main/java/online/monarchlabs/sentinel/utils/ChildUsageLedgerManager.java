package online.monarchlabs.sentinel.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import online.monarchlabs.sentinel.models.SUsageAppInfo;
import online.monarchlabs.sentinel.models.SUsageDailyData;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Child-side source of truth for the rolling usage window.
 * Android UsageStats is treated as an input; this ledger prevents uninstalling
 * an app from erasing already observed usage for today or finalized days.
 */
public final class ChildUsageLedgerManager {
    private static final String PREFS = "child_usage_ledger_v1";
    private static final String KEY_DAY_PREFIX = "day_";
    private static final int ROLLING_DAY_COUNT = 7;

    private static ChildUsageLedgerManager instance;

    private final Context context;
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();
    private final Type dayType = new TypeToken<LedgerDay>() { }.getType();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private ChildUsageLedgerManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized ChildUsageLedgerManager getInstance(Context context) {
        if (instance == null) {
            instance = new ChildUsageLedgerManager(context);
        }
        return instance;
    }

    public static void clear(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    public synchronized SUsageDailyData mergeDailyUsage(SUsageDailyData rawDaily) {
        if (rawDaily == null || isBlank(rawDaily.getDateKey())) {
            return rawDaily;
        }

        String dateKey = rawDaily.getDateKey();
        boolean today = dateKey.equals(todayKey());
        LedgerDay day = loadDay(dateKey);
        if (day == null) {
            day = new LedgerDay();
            day.dateKey = dateKey;
        }
        if (day.apps == null) {
            day.apps = new HashMap<>();
        }

        if (today || !day.finalized) {
            for (SUsageAppInfo rawApp : rawDaily.getAppList()) {
                mergeRawApp(day, rawApp, today);
            }
        }

        if (!today) {
            day.finalized = true;
        }
        day.updatedAt = System.currentTimeMillis();
        saveDay(day);
        pruneOldDays();
        return toDailyData(day);
    }

    public synchronized List<SUsageDailyData> mergeUsageWindow(List<SUsageDailyData> rawDays) {
        List<SUsageDailyData> merged = new ArrayList<>();
        if (rawDays == null) {
            return merged;
        }
        for (SUsageDailyData rawDay : rawDays) {
            SUsageDailyData daily = mergeDailyUsage(rawDay);
            if (daily != null) {
                merged.add(daily);
            }
        }
        return merged;
    }

    public synchronized void markAppUninstalled(String packageName, long rawTodayUsageMillis,
            String appName, String iconBase64) {
        if (isBlank(packageName)) {
            return;
        }

        String dateKey = todayKey();
        LedgerDay day = loadDay(dateKey);
        if (day == null) {
            day = new LedgerDay();
            day.dateKey = dateKey;
        }
        if (day.apps == null) {
            day.apps = new HashMap<>();
        }

        LedgerApp app = day.apps.get(packageName);
        if (app == null) {
            app = new LedgerApp();
            app.packageName = packageName;
            app.appName = fallbackName(packageName);
            app.category = AppCategorizer.getCategory(packageName).getDisplayName();
            day.apps.put(packageName, app);
        }
        if (!isBlank(appName)) {
            app.appName = appName;
        }
        if (!isBlank(iconBase64)) {
            app.iconBase64 = iconBase64;
        }

        long preservedUsage = Math.max(app.usageMillis, Math.max(0L, rawTodayUsageMillis));
        app.usageMillis = preservedUsage;
        app.lastRawUsageMillis = Math.max(0L, rawTodayUsageMillis);
        app.installed = false;
        app.status = "uninstalled";
        app.uninstalledAt = System.currentTimeMillis();
        app.updatedAt = app.uninstalledAt;
        day.updatedAt = app.updatedAt;
        saveDay(day);
        pruneOldDays();
    }

    public synchronized void markAppInstalled(String packageName, String appName,
            String category, String iconBase64, long rawTodayUsageMillis) {
        if (isBlank(packageName)) {
            return;
        }

        String dateKey = todayKey();
        LedgerDay day = loadDay(dateKey);
        if (day == null) {
            day = new LedgerDay();
            day.dateKey = dateKey;
        }
        if (day.apps == null) {
            day.apps = new HashMap<>();
        }

        LedgerApp app = day.apps.get(packageName);
        if (app == null) {
            app = new LedgerApp();
            app.packageName = packageName;
            day.apps.put(packageName, app);
        }

        if (!isBlank(appName)) {
            app.appName = appName;
        } else if (isBlank(app.appName)) {
            app.appName = fallbackName(packageName);
        }
        if (!isBlank(category)) {
            app.category = category;
        } else if (isBlank(app.category)) {
            app.category = AppCategorizer.getCategory(packageName).getDisplayName();
        }
        if (!isBlank(iconBase64)) {
            app.iconBase64 = iconBase64;
        }

        long now = System.currentTimeMillis();
        if (!app.installed || "uninstalled".equals(app.status)) {
            app.usageAtReinstall = Math.max(0L, app.usageMillis);
            app.rawBaselineAtReinstall = Math.max(0L, rawTodayUsageMillis);
            app.reinstalledAt = now;
        }
        app.installed = true;
        app.status = "installed";
        app.lastRawUsageMillis = Math.max(0L, rawTodayUsageMillis);
        app.updatedAt = now;
        day.updatedAt = now;
        saveDay(day);
        pruneOldDays();
    }

    public synchronized String getCachedAppName(String packageName) {
        LedgerApp app = findRecentApp(packageName);
        return app != null ? app.appName : null;
    }

    public synchronized String getCachedCategory(String packageName) {
        LedgerApp app = findRecentApp(packageName);
        return app != null ? app.category : null;
    }

    private void mergeRawApp(LedgerDay day, SUsageAppInfo rawApp, boolean today) {
        if (rawApp == null || isBlank(rawApp.getPackageName())) {
            return;
        }

        String packageName = rawApp.getPackageName();
        LedgerApp app = day.apps.get(packageName);
        if (app == null) {
            app = new LedgerApp();
            app.packageName = packageName;
            day.apps.put(packageName, app);
        }

        long rawUsage = Math.max(0L, rawApp.getUsageTimeMillis());
        if (!isBlank(rawApp.getAppName())) {
            app.appName = rawApp.getAppName();
        } else if (isBlank(app.appName)) {
            app.appName = fallbackName(packageName);
        }
        if (!isBlank(rawApp.getCategory())) {
            app.category = rawApp.getCategory();
        } else if (isBlank(app.category)) {
            app.category = AppCategorizer.getCategory(packageName).getDisplayName();
        }
        if (!isBlank(rawApp.getIconBase64())) {
            app.iconBase64 = rawApp.getIconBase64();
        }

        if (rawApp.isInstalled() && !app.installed) {
            app.usageAtReinstall = Math.max(0L, app.usageMillis);
            app.rawBaselineAtReinstall = rawUsage;
            app.reinstalledAt = System.currentTimeMillis();
            app.installed = true;
            app.status = "installed";
        } else if (!rawApp.isInstalled() && today) {
            app.installed = false;
            app.status = "uninstalled";
            if (app.uninstalledAt <= 0L) {
                app.uninstalledAt = System.currentTimeMillis();
            }
        } else if (isBlank(app.status)) {
            app.installed = true;
            app.status = "installed";
        }

        long mergedUsage = rawUsage;
        if (app.reinstalledAt > app.uninstalledAt && app.usageAtReinstall > 0L) {
            long postReinstallDelta = Math.max(0L, rawUsage - app.rawBaselineAtReinstall);
            mergedUsage = app.usageAtReinstall + postReinstallDelta;
        }
        app.usageMillis = Math.max(app.usageMillis, mergedUsage);
        app.lastRawUsageMillis = rawUsage;
        app.updatedAt = System.currentTimeMillis();
    }

    private SUsageDailyData toDailyData(LedgerDay day) {
        SUsageDailyData daily = new SUsageDailyData();
        daily.setDateKey(day.dateKey);
        daily.setLastUpdated(day.updatedAt);

        long total = 0L;
        long communication = 0L;
        long entertainment = 0L;
        long games = 0L;

        for (LedgerApp entry : day.apps.values()) {
            if (entry == null || isBlank(entry.packageName)) {
                continue;
            }
            long usage = Math.max(0L, entry.usageMillis);
            if (usage < 1000L && !entry.packageName.equals(context.getPackageName())) {
                continue;
            }

            String appName = !isBlank(entry.appName) ? entry.appName : fallbackName(entry.packageName);
            String category = !isBlank(entry.category)
                    ? entry.category
                    : AppCategorizer.getCategory(entry.packageName).getDisplayName();
            SUsageAppInfo appInfo = new SUsageAppInfo(
                    entry.packageName,
                    appName,
                    usage,
                    category);
            appInfo.setIconBase64(entry.iconBase64);
            appInfo.setInstalled(entry.installed);
            appInfo.setStatus(!isBlank(entry.status) ? entry.status
                    : entry.installed ? "installed" : "uninstalled");
            appInfo.setUninstalledAt(entry.uninstalledAt);
            appInfo.setReinstalledAt(entry.reinstalledAt);
            daily.addAppUsage(appInfo);

            total += usage;
            if ("Communication".equals(category)) {
                communication += usage;
            } else if ("Entertainment".equals(category)) {
                entertainment += usage;
            } else if ("Games".equals(category)) {
                games += usage;
            }
        }

        daily.setTotalScreenTimeMillis(total);
        daily.setCommunicationTimeMillis(communication);
        daily.setEntertainmentTimeMillis(entertainment);
        daily.setGamesTimeMillis(games);
        daily.setOtherTimeMillis(Math.max(0L, total - communication - entertainment - games));
        return daily;
    }

    private LedgerApp findRecentApp(String packageName) {
        if (isBlank(packageName)) {
            return null;
        }
        for (String dateKey : rollingDateKeys()) {
            LedgerDay day = loadDay(dateKey);
            if (day != null && day.apps != null && day.apps.containsKey(packageName)) {
                return day.apps.get(packageName);
            }
        }
        return null;
    }

    private LedgerDay loadDay(String dateKey) {
        String json = preferences.getString(KEY_DAY_PREFIX + dateKey, null);
        if (isBlank(json)) {
            return null;
        }
        try {
            LedgerDay day = gson.fromJson(json, dayType);
            if (day != null && day.apps == null) {
                day.apps = new HashMap<>();
            } else if (day != null && day.apps != null) {
                // Ensure Gson didn't produce LinkedTreeMaps due to ProGuard obfuscation
                for (Object value : day.apps.values()) {
                    if (!(value instanceof LedgerApp)) {
                        throw new ClassCastException("Corrupted LedgerApp data: " + 
                                (value != null ? value.getClass().getName() : "null"));
                    }
                }
            }
            return day;
        } catch (Exception ignored) {
            preferences.edit().remove(KEY_DAY_PREFIX + dateKey).apply();
            return null;
        }
    }

    private void saveDay(LedgerDay day) {
        if (day == null || isBlank(day.dateKey)) {
            return;
        }
        preferences.edit()
                .putString(KEY_DAY_PREFIX + day.dateKey, gson.toJson(day))
                .apply();
    }

    private void pruneOldDays() {
        Set<String> keep = new HashSet<>(rollingDateKeys());
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key != null && key.startsWith(KEY_DAY_PREFIX)) {
                String dateKey = key.substring(KEY_DAY_PREFIX.length());
                if (!keep.contains(dateKey)) {
                    editor.remove(key);
                }
            }
        }
        editor.apply();
    }

    private List<String> rollingDateKeys() {
        List<String> dates = new ArrayList<>();
        Calendar calendar = Calendar.getInstance();
        for (int i = ROLLING_DAY_COUNT - 1; i >= 0; i--) {
            Calendar day = (Calendar) calendar.clone();
            day.add(Calendar.DAY_OF_YEAR, -i);
            dates.add(dateFormat.format(day.getTime()));
        }
        return dates;
    }

    private String todayKey() {
        return dateFormat.format(new Date());
    }

    private String fallbackName(String packageName) {
        if (isBlank(packageName)) {
            return "Unknown app";
        }
        int dot = packageName.lastIndexOf('.');
        return dot >= 0 && dot < packageName.length() - 1
                ? packageName.substring(dot + 1)
                : packageName;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class LedgerDay {
        String dateKey;
        Map<String, LedgerApp> apps = new HashMap<>();
        boolean finalized;
        long updatedAt;
    }

    private static final class LedgerApp {
        String packageName;
        String appName;
        String category;
        String iconBase64;
        long usageMillis;
        boolean installed = true;
        String status = "installed";
        long uninstalledAt;
        long reinstalledAt;
        long lastRawUsageMillis;
        long rawBaselineAtReinstall;
        long usageAtReinstall;
        long updatedAt;
    }
}