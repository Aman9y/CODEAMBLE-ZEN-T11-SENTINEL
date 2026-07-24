package online.monarchlabs.sentinel.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import online.monarchlabs.sentinel.SessionManager;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class AppInventoryDeltaSync {
    public static final String OPERATION_UPSERT = "UPSERT";
    public static final String OPERATION_REMOVE = "REMOVE";

    private static final String PREFS = "app_inventory_delta_sync";
    private static final String KEY_LIVE_MONITOR_ACTIVE = "live_monitor_active";
    private static final String KEY_LIVE_MONITOR_HEARTBEAT = "live_monitor_heartbeat";
    private static final String KEY_APP_NAME_PREFIX = "app_name_";
    private static final String KEY_APP_ICON_PREFIX = "app_icon_";
    private static final long LIVE_MONITOR_STALE_MS = 2L * 60L * 1000L;

    public interface Callback {
        void onSuccess();
        void onError(Exception error);
    }

    private AppInventoryDeltaSync() {
    }

    public static void syncAsync(Context context, String packageName,
            String operation, String eventAction, Callback callback) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                syncBlocking(appContext, packageName, operation, eventAction);
                if (callback != null) {
                    callback.onSuccess();
                }
            } catch (Exception error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        }, "sentinel-app-inventory-delta").start();
    }

    public static void syncBlocking(Context context, String packageName,
            String operation, String eventAction) throws Exception {
        if (packageName == null || packageName.isEmpty()
                || operation == null || operation.isEmpty()) {
            return;
        }

        Context appContext = context.getApplicationContext();
        String deviceId = new SessionManager(appContext).getChildDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }

        PackageManager packageManager = appContext.getPackageManager();
        String appKey = sanitizeFirebaseKey(packageName);
        String revisionId = UUID.randomUUID().toString();
        String inventoryHash = InstalledAppsManager.getInstance(appContext)
                .calculateCurrentInventoryHash();

        Map<String, Object> updates = new HashMap<>();
        String inventoryBase = "v2/device_installs/" + deviceId;
        Map<String, Object> app = null;
        String eventAppName = getCachedAppName(appContext, packageName);
        String eventIconBase64 = getCachedAppIcon(appContext, packageName);
        String catalogBase = "v2/app_catalog/" + deviceId + "/" + appKey;
        if (OPERATION_REMOVE.equals(operation)) {
            SUsageDataManager.getInstance(appContext)
                    .recordAppUninstalled(packageName, eventAppName, eventIconBase64);
            updates.put(inventoryBase + "/apps/" + appKey, null);
            updates.put(catalogBase + "/packageName", packageName);
            updates.put(catalogBase + "/installed", false);
            updates.put(catalogBase + "/updatedAt", ServerValue.TIMESTAMP);
            if (eventAppName != null && !eventAppName.isEmpty()) {
                updates.put(catalogBase + "/appName", eventAppName);
            }
            if (eventIconBase64 != null && !eventIconBase64.isEmpty()) {
                updates.put(catalogBase + "/iconBase64", eventIconBase64);
            }
        } else {
            app = buildAppData(packageManager, packageName);
            if (app == null) {
                return;
            }
            app.put("installed", true);
            app.put("updatedAt", ServerValue.TIMESTAMP);
            updates.put(inventoryBase + "/apps/" + appKey, app);
            updates.put(catalogBase, app);
            eventAppName = stringValue(app.get("appName"));
            eventIconBase64 = stringValue(app.get("iconBase64"));
            rememberAppMetadata(appContext, packageName, eventAppName, eventIconBase64);
            SUsageDataManager.getInstance(appContext)
                    .recordAppInstalled(packageName, eventAppName, null, eventIconBase64);
        }
        updates.put(inventoryBase + "/revisionId", revisionId);
        updates.put(inventoryBase + "/inventoryHash", inventoryHash);
        updates.put(inventoryBase + "/appCount", countVisibleApps(appContext));
        updates.put(inventoryBase + "/lastUpdated", ServerValue.TIMESTAMP);

        if (eventAction != null && !eventAction.isEmpty()) {
            String eventId = claimEventId(
                    appContext, packageName, eventAction);
            if (eventAppName == null || eventAppName.isEmpty()) {
                eventAppName = resolveAppName(
                        packageManager,
                        packageName,
                        OPERATION_UPSERT.equals(operation));
            }
            Map<String, Object> event = new HashMap<>();
            event.put("eventId", eventId);
            event.put("appName", eventAppName);
            event.put("packageName", packageName);
            event.put("action", eventAction);
            event.put("timestamp", System.currentTimeMillis());
            event.put("schemaVersion", 2);
            updates.put(
                    "v2/app_events/" + deviceId + "/" + eventId,
                    event);
        }
        Tasks.await(
                FirebaseDatabase.getInstance().getReference().updateChildren(updates),
                30,
                TimeUnit.SECONDS);
        InstalledAppsManager.getInstance(appContext)
                .rememberInventoryVersion(deviceId, inventoryHash, revisionId);
        SUsageDataManager.getInstance(appContext).uploadToFirebase(deviceId, null);
    }

    public static void rememberAppMetadata(Context context, String packageName,
            String appName, String iconBase64) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return;
        }

        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit();
        if (appName != null && !appName.isEmpty()) {
            editor.putString(KEY_APP_NAME_PREFIX + packageName, appName);
        }
        if (iconBase64 != null && !iconBase64.isEmpty()) {
            editor.putString(KEY_APP_ICON_PREFIX + packageName, iconBase64);
        }
        editor.apply();
    }

    private static String getCachedAppName(Context context, String packageName) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APP_NAME_PREFIX + packageName, null);
    }

    private static String getCachedAppIcon(Context context, String packageName) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_APP_ICON_PREFIX + packageName, null);
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    public static boolean isLiveMonitorRecentlyActive(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_LIVE_MONITOR_ACTIVE, false)) {
            return false;
        }
        long heartbeat = prefs.getLong(KEY_LIVE_MONITOR_HEARTBEAT, 0L);
        return heartbeat > 0L
                && System.currentTimeMillis() - heartbeat < LIVE_MONITOR_STALE_MS;
    }

    public static void markLiveMonitorActive(Context context, boolean active) {
        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LIVE_MONITOR_ACTIVE, active);
        if (active) {
            editor.putLong(KEY_LIVE_MONITOR_HEARTBEAT, System.currentTimeMillis());
        } else {
            editor.remove(KEY_LIVE_MONITOR_HEARTBEAT);
        }
        editor.apply();
    }

    public static void updateLiveMonitorHeartbeat(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LIVE_MONITOR_ACTIVE, true)
                .putLong(KEY_LIVE_MONITOR_HEARTBEAT, System.currentTimeMillis())
                .apply();
    }

    private static synchronized String claimEventId(
            Context context, String packageName, String eventAction) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        String fingerprint = packageName + "|" + eventAction;
        long now = System.currentTimeMillis();
        String previousFingerprint = prefs.getString(
                "last_event_fingerprint", "");
        long previousAt = prefs.getLong("last_event_claimed_at", 0L);
        String previousId = prefs.getString("last_event_id", "");
        if (fingerprint.equals(previousFingerprint)
                && now - previousAt < 30_000L
                && !previousId.isEmpty()) {
            return previousId;
        }

        String eventId = sanitizeFirebaseKey(packageName)
                + "_" + eventAction.toLowerCase(java.util.Locale.US)
                + "_" + now;
        prefs.edit()
                .putString("last_event_fingerprint", fingerprint)
                .putLong("last_event_claimed_at", now)
                .putString("last_event_id", eventId)
                .commit();
        return eventId;
    }
    private static Map<String, Object> buildAppData(PackageManager packageManager,
            String packageName) throws Exception {
        ApplicationInfo appInfo =
                packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        if (packageManager.getLaunchIntentForPackage(packageName) == null
                && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return null;
        }

        PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 0);
        Map<String, Object> app = new HashMap<>();
        app.put("packageName", packageName);
        app.put("appName", appInfo.loadLabel(packageManager).toString());
        app.put("isSystemApp", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        app.put("versionCode", getVersionCode(packageInfo));
        app.put("versionName",
                packageInfo.versionName != null ? packageInfo.versionName : "");
        app.put("category",
                AppCategorizer.getCategory(packageName, appInfo.loadLabel(packageManager).toString()).getDisplayName());
        app.put("packageUpdatedAt", packageInfo.lastUpdateTime);
        app.put("lastUpdated", packageInfo.lastUpdateTime);
        app.put("iconBase64", encodeIcon(packageManager, packageName));
        return app;
    }

    private static int countVisibleApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        int count = 0;
        for (ApplicationInfo appInfo :
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (appInfo.packageName.equals(context.getPackageName())) {
                continue;
            }
            if (packageManager.getLaunchIntentForPackage(appInfo.packageName) != null
                    || (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                count++;
            }
        }
        return count;
    }

    private static long getVersionCode(PackageInfo packageInfo) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }

    private static String resolveAppName(PackageManager packageManager,
            String packageName, boolean installed) {
        if (!installed) {
            return packageName.substring(packageName.lastIndexOf('.') + 1);
        }
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return appInfo.loadLabel(packageManager).toString();
        } catch (Exception ignored) {
            return packageName.substring(packageName.lastIndexOf('.') + 1);
        }
    }

    private static String encodeIcon(PackageManager packageManager, String packageName)
            throws Exception {
        Drawable drawable = packageManager.getApplicationIcon(packageName);
        Bitmap bitmap;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            bitmap = Bitmap.createBitmap(
                    Math.max(1, drawable.getIntrinsicWidth()),
                    Math.max(1, drawable.getIntrinsicHeight()),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 48, 48, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.PNG, 100, output);
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private static String sanitizeFirebaseKey(String key) {
        return key.replaceAll("[.#$\\[\\]/]", "_");
    }
}
