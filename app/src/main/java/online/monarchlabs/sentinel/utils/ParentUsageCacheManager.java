package online.monarchlabs.sentinel.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Small, bounded parent-side cache for canonical V2 usage data and app icons.
 */
public final class ParentUsageCacheManager {
    private static final String TAG = "ParentUsageCache";
    private static final String PREF_NAME = "parent_historical_usage_cache";
    private static final String DAY_PREFIX = "day::";
    private static final String SCOPE_PREFIX = "scope::";
    private static final String ICON_PREFIX = "icon::";
    private static final String APP_PACKAGE_PREFIX = "app_package::";
    private static final String APP_NAME_PREFIX = "app_name::";
    private static final String APP_CATEGORY_PREFIX = "app_category::";
    private static final String ICON_REFRESH_PREFIX = "icon_refresh::";
    private static final String SEPARATOR = "::";
    private static final int CACHE_SCHEMA_VERSION = 4;
    private static final long ICON_CACHE_TTL_MS = 24L * 60L * 60L * 1000L;

    private static ParentUsageCacheManager instance;

    private final SharedPreferences preferences;
    private final Gson gson;
    private final Type mapType = new TypeToken<Map<String, Object>>() { }.getType();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private ParentUsageCacheManager(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public static synchronized ParentUsageCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new ParentUsageCacheManager(context);
        }
        return instance;
    }

    /**
     * Select the cache namespace for one relationship. Returns true when cached
     * historical days were invalidated because the relationship changed.
     */
    public boolean setUsageScope(
            String deviceId,
            String connectionId,
            String historyGeneration) {
        if (!isValid(deviceId) || !isValid(connectionId)) {
            return false;
        }
        String generation = isValid(historyGeneration)
                ? historyGeneration : connectionId;
        String nextScope = connectionId + "|" + generation;
        String scopeKey = scopePreferenceKey(deviceId);
        String previousScope = preferences.getString(scopeKey, "");
        if (nextScope.equals(previousScope)) {
            return false;
        }

        String dayPrefix = DAY_PREFIX + accountScope() + SEPARATOR
                + deviceId + SEPARATOR;
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(dayPrefix)) {
                editor.remove(key);
            }
        }
        editor.putString(scopeKey, nextScope).commit();
        return true;
    }
    /** Store a historical day fetched directly from its exact Firebase path. */
    public void cacheDailyUsage(String deviceId, String dateKey, Map<String, Object> dailyMap) {
        cacheUsage(deviceId, dateKey, dailyMap, true);
    }

    /** Store a live snapshot. It must be revalidated after the date rolls over. */
    public void cacheLiveDailyUsage(String deviceId, String dateKey, Map<String, Object> dailyMap) {
        cacheUsage(deviceId, dateKey, dailyMap, false);
    }

    private void cacheUsage(String deviceId, String dateKey, Map<String, Object> dailyMap,
            boolean finalized) {
        if (!isValid(deviceId) || !isValid(dateKey) || dailyMap == null) {
            return;
        }

        try {
            CacheEntry entry = new CacheEntry();
            entry.schemaVersion = CACHE_SCHEMA_VERSION;
            entry.status = "present";
            entry.fetchedAt = System.currentTimeMillis();
            entry.sourceLastUpdated = readLong(dailyMap.get("lastUpdated"));
            entry.finalized = finalized;
            entry.data = dailyMap;
            preferences.edit().putString(dayKey(deviceId, dateKey), gson.toJson(entry)).apply();
        } catch (Exception error) {
            Log.e(TAG, "Failed to cache usage for " + deviceId + "/" + dateKey, error);
        }
    }

    /** Remember an empty response briefly so absent dates are not fetched repeatedly. */
    public void cacheMissingDay(String deviceId, String dateKey) {
        if (!isValid(deviceId) || !isValid(dateKey)) {
            return;
        }

        CacheEntry entry = new CacheEntry();
        entry.schemaVersion = CACHE_SCHEMA_VERSION;
        entry.status = "missing";
        entry.fetchedAt = System.currentTimeMillis();
        entry.finalized = true;
        preferences.edit().putString(dayKey(deviceId, dateKey), gson.toJson(entry)).apply();
    }

    /**
     * Return cached data when it is safe to use. A live snapshot from a day that
     * has since become historical is deliberately re-fetched once.
     */
    public Map<String, Object> getDailyUsage(String deviceId, String dateKey) {
        CacheEntry entry = readEntry(deviceId, dateKey);
        if (entry == null || !"present".equals(entry.status) || entry.data == null) {
            return null;
        }
        if (!entry.finalized && !dateKey.equals(todayKey())) {
            return null;
        }
        return entry.data;
    }

    public boolean isMissingDayFresh(String deviceId, String dateKey) {
        CacheEntry entry = readEntry(deviceId, dateKey);
        return entry != null
                && "missing".equals(entry.status)
                && entry.finalized;
    }

    public void invalidateDay(String deviceId, String dateKey) {
        if (isValid(deviceId) && isValid(dateKey)) {
            preferences.edit().remove(dayKey(deviceId, dateKey)).apply();
        }
    }

    public void cacheAppMetadata(
            String deviceId,
            String appKey,
            String packageName,
            String appName,
            String category,
            String iconBase64) {
        if (!isValid(deviceId) || !isValid(appKey)) {
            return;
        }

        SharedPreferences.Editor editor = preferences.edit();
        if (isValid(packageName)) {
            editor.putString(appMetadataKey(APP_PACKAGE_PREFIX, deviceId, appKey), packageName);
        }
        if (isValid(appName)) {
            editor.putString(appMetadataKey(APP_NAME_PREFIX, deviceId, appKey), appName);
        }
        if (isValid(category)) {
            editor.putString(appMetadataKey(APP_CATEGORY_PREFIX, deviceId, appKey), category);
        }
        if (isValid(iconBase64)) {
            editor.putString(appMetadataKey(ICON_PREFIX, deviceId, appKey), iconBase64);
        }
        editor.apply();
    }

    public String getAppPackageName(String deviceId, String appKey) {
        return getAppMetadataValue(APP_PACKAGE_PREFIX, deviceId, appKey);
    }

    public String getAppName(String deviceId, String appKey) {
        return getAppMetadataValue(APP_NAME_PREFIX, deviceId, appKey);
    }

    public String getAppCategory(String deviceId, String appKey) {
        return getAppMetadataValue(APP_CATEGORY_PREFIX, deviceId, appKey);
    }

    public String getAppIconByKey(String deviceId, String appKey) {
        return getAppMetadataValue(ICON_PREFIX, deviceId, appKey);
    }

    public void cacheAppIcon(String deviceId, String packageName, String iconBase64) {
        cacheAppMetadata(
                deviceId,
                sanitizeAppKey(packageName),
                packageName,
                null,
                null,
                iconBase64);
    }

    public String getAppIcon(String deviceId, String packageName) {
        return getAppIconByKey(deviceId, sanitizeAppKey(packageName));
    }

    public boolean hasFreshIconCache(String deviceId) {
        if (!isValid(deviceId)) {
            return false;
        }
        long refreshedAt = preferences.getLong(iconRefreshKey(deviceId), 0L);
        return refreshedAt > 0L && System.currentTimeMillis() - refreshedAt < ICON_CACHE_TTL_MS;
    }

    public void markIconCacheRefreshed(String deviceId) {
        if (isValid(deviceId)) {
            preferences.edit()
                    .putLong(iconRefreshKey(deviceId), System.currentTimeMillis())
                    .apply();
        }
    }

    /** Keep today plus the previous six calendar days. */
    public void pruneOldCache(String deviceId) {
        if (!isValid(deviceId)) {
            return;
        }

        Calendar oldestAllowed = Calendar.getInstance();
        oldestAllowed.set(Calendar.HOUR_OF_DAY, 0);
        oldestAllowed.set(Calendar.MINUTE, 0);
        oldestAllowed.set(Calendar.SECOND, 0);
        oldestAllowed.set(Calendar.MILLISECOND, 0);
        oldestAllowed.add(Calendar.DAY_OF_YEAR, -6);

        String prefix = DAY_PREFIX + accountScope() + SEPARATOR + deviceId
                + SEPARATOR + usageScope(deviceId) + SEPARATOR;
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String dateKey = key.substring(prefix.length());
            try {
                Date date = dateFormat.parse(dateKey);
                if (date == null || date.getTime() < oldestAllowed.getTimeInMillis()) {
                    editor.remove(key);
                }
            } catch (Exception ignored) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    public void clearDevice(String deviceId) {
        if (!isValid(deviceId)) {
            return;
        }

        String scopedDeviceSuffix = SEPARATOR + deviceId + SEPARATOR;
        String scopedDeviceEnd = SEPARATOR + deviceId;
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            boolean belongsToCurrentAccount = key.contains(SEPARATOR + accountScope() + SEPARATOR);
            boolean belongsToDevice = key.contains(scopedDeviceSuffix)
                    || key.endsWith(scopedDeviceEnd);
            if (belongsToCurrentAccount && belongsToDevice) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    public void clearAll() {
        preferences.edit().clear().apply();
    }

    private CacheEntry readEntry(String deviceId, String dateKey) {
        if (!isValid(deviceId) || !isValid(dateKey)) {
            return null;
        }
        String json = preferences.getString(dayKey(deviceId, dateKey), null);
        if (json == null) {
            return null;
        }
        try {
            CacheEntry entry = gson.fromJson(json, CacheEntry.class);
            if (entry != null && entry.schemaVersion == CACHE_SCHEMA_VERSION) {
                return entry;
            }
            if (entry != null && entry.schemaVersion > 0 && entry.data != null) {
                cacheUsage(deviceId, dateKey, entry.data, entry.finalized);
                entry.schemaVersion = CACHE_SCHEMA_VERSION;
                return entry;
            }

            // Read the short-lived unwrapped local cache once, then upgrade it.
            Map<String, Object> legacyData = gson.fromJson(json, mapType);
            if (legacyData != null) {
                cacheUsage(deviceId, dateKey, legacyData, false);
                CacheEntry upgraded = new CacheEntry();
                upgraded.schemaVersion = CACHE_SCHEMA_VERSION;
                upgraded.status = "present";
                upgraded.fetchedAt = System.currentTimeMillis();
                upgraded.data = legacyData;
                return upgraded;
            }
        } catch (Exception error) {
            Log.w(TAG, "Discarding invalid cache entry for " + deviceId + "/" + dateKey);
            preferences.edit().remove(dayKey(deviceId, dateKey)).apply();
        }
        return null;
    }

    private String dayKey(String deviceId, String dateKey) {
        return DAY_PREFIX + accountScope() + SEPARATOR + deviceId + SEPARATOR
                + usageScope(deviceId) + SEPARATOR + dateKey;
    }

    private String scopePreferenceKey(String deviceId) {
        return SCOPE_PREFIX + accountScope() + SEPARATOR + deviceId;
    }

    private String usageScope(String deviceId) {
        return preferences.getString(scopePreferenceKey(deviceId), "unscoped");
    }

    private String appMetadataKey(String prefix, String deviceId, String appKey) {
        return prefix + accountScope() + SEPARATOR + deviceId + SEPARATOR + appKey;
    }

    private String getAppMetadataValue(String prefix, String deviceId, String appKey) {
        if (!isValid(deviceId) || !isValid(appKey)) {
            return null;
        }
        return preferences.getString(appMetadataKey(prefix, deviceId, appKey), null);
    }

    private String iconRefreshKey(String deviceId) {
        return ICON_REFRESH_PREFIX + accountScope() + SEPARATOR + deviceId;
    }

    private String accountScope() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null && user.getUid() != null ? user.getUid() : "signed_out";
    }

    private String sanitizeAppKey(String packageName) {
        if (!isValid(packageName)) {
            return "unknown";
        }
        return packageName.replaceAll("[.#$\\[\\]/]", "_");
    }

    private String todayKey() {
        return dateFormat.format(new Date());
    }

    private boolean isValid(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private long readLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static final class CacheEntry {
        int schemaVersion;
        String status;
        long fetchedAt;
        long sourceLastUpdated;
        boolean finalized;
        Map<String, Object> data;
    }
}
