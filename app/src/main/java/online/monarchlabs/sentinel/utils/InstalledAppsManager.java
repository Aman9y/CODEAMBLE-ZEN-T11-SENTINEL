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
import android.util.Log;

import com.google.firebase.database.ServerValue;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InstalledAppsManager {
    private static final String TAG = "InstalledAppsManager";
    private static final String PREFS = "installed_apps_sync_v2";
    private static InstalledAppsManager instance;

    private final Context context;
    private final PackageManager packageManager;

    private InstalledAppsManager(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = this.context.getPackageManager();
    }

    public static synchronized InstalledAppsManager getInstance(Context context) {
        if (instance == null) {
            instance = new InstalledAppsManager(context);
        }
        return instance;
    }

    public void syncInstalledApps(String deviceId, OnSyncCompleteListener listener) {
        syncInstalledApps(deviceId, false, listener);
    }

    public void syncInstalledApps(String deviceId, boolean force,
            OnSyncCompleteListener listener) {
        if (deviceId == null || deviceId.isEmpty()) {
            if (listener != null) {
                listener.onError("No device ID");
            }
            return;
        }

        new Thread(() -> {
            try {
                List<Map<String, Object>> apps = collectInstalledApps();
                String inventoryHash = calculateInventoryHash(apps);
                SharedPreferences preferences =
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String hashKey = "hash_" + deviceId;
                if (!force && inventoryHash.equals(preferences.getString(hashKey, ""))) {
                    Log.d(TAG, "Installed-app inventory unchanged; upload skipped");
                    if (listener != null) {
                        listener.onSuccess(apps.size());
                    }
                    return;
                }

                uploadToFirebase(deviceId, apps, inventoryHash, listener);
            } catch (Exception error) {
                Log.e(TAG, "Error collecting installed apps", error);
                if (listener != null) {
                    listener.onError(error.getMessage());
                }
            }
        }, "sentinel-app-inventory").start();
    }

    private List<Map<String, Object>> collectInstalledApps() {
        List<Map<String, Object>> apps = new ArrayList<>();
        List<ApplicationInfo> installed =
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo appInfo : installed) {
            if (appInfo.packageName.equals(context.getPackageName())) {
                continue;
            }
            if (packageManager.getLaunchIntentForPackage(appInfo.packageName) == null
                    && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }

            try {
                PackageInfo packageInfo =
                        packageManager.getPackageInfo(appInfo.packageName, 0);
                Map<String, Object> app = new HashMap<>();
                app.put("packageName", appInfo.packageName);
                app.put("appName", appInfo.loadLabel(packageManager).toString());
                app.put("isSystemApp",
                        (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                app.put("versionCode", getVersionCode(packageInfo));
                app.put("versionName",
                        packageInfo.versionName != null ? packageInfo.versionName : "");
                app.put("category",
                        AppCategorizer.getCategory(context, appInfo.packageName).getDisplayName());
                app.put("packageUpdatedAt", packageInfo.lastUpdateTime);
                app.put("lastUpdated", packageInfo.lastUpdateTime);
                try {
                    app.put("iconBase64", encodeIconToBase64(appInfo.packageName));
                } catch (Exception error) {
                    Log.w(TAG, "Could not encode icon for " + appInfo.packageName);
                }
                String iconBase64 = app.get("iconBase64") instanceof String
                        ? (String) app.get("iconBase64") : null;
                AppInventoryDeltaSync.rememberAppMetadata(
                        context,
                        appInfo.packageName,
                        String.valueOf(app.get("appName")),
                        iconBase64);
                apps.add(app);
            } catch (Exception error) {
                Log.w(TAG, "Skipping installed package " + appInfo.packageName, error);
            }
        }

        Collections.sort(apps, (first, second) ->
                String.valueOf(first.get("packageName"))
                        .compareTo(String.valueOf(second.get("packageName"))));
        return apps;
    }

    private void uploadToFirebase(String deviceId, List<Map<String, Object>> apps,
            String inventoryHash, OnSyncCompleteListener listener) {
        Map<String, Object> appsByKey = new HashMap<>();
        for (Map<String, Object> app : apps) {
            String packageName = (String) app.get("packageName");
            appsByKey.put(sanitizeFirebaseKey(packageName), app);
        }

        String revisionId = UUID.randomUUID().toString();
        Map<String, Object> inventory = new HashMap<>();
        inventory.put("deviceId", deviceId);
        inventory.put("revisionId", revisionId);
        inventory.put("inventoryHash", inventoryHash);
        inventory.put("lastUpdated", ServerValue.TIMESTAMP);
        inventory.put("appCount", apps.size());
        inventory.put("apps", appsByKey);

        Map<String, Object> updates = new HashMap<>();
        updates.put(FirebaseSchemaV2Repository.ROOT
                + "/device_installs/" + deviceId, inventory);
        for (Map.Entry<String, Object> entry : appsByKey.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> catalogEntry = new HashMap<>(
                    (Map<String, Object>) entry.getValue());
            catalogEntry.put("installed", true);
            catalogEntry.put("updatedAt", ServerValue.TIMESTAMP);
            updates.put(FirebaseSchemaV2Repository.ROOT + "/app_catalog/"
                    + deviceId + "/" + entry.getKey(), catalogEntry);
        }

        com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference().updateChildren(updates)
                .addOnSuccessListener(ignored -> {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putString("hash_" + deviceId, inventoryHash)
                            .putString("revision_" + deviceId, revisionId)
                            .apply();
                    if (listener != null) {
                        listener.onSuccess(apps.size());
                    }
                })
                .addOnFailureListener(error -> {
                    Log.e(TAG, "Failed to upload installed apps", error);
                    if (listener != null) {
                        listener.onError(error.getMessage());
                    }
                });
    }

    public String calculateCurrentInventoryHash() throws Exception {
        List<String> fingerprints = new ArrayList<>();
        for (ApplicationInfo appInfo :
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (appInfo.packageName.equals(context.getPackageName())) {
                continue;
            }
            if (packageManager.getLaunchIntentForPackage(appInfo.packageName) == null
                    && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }
            PackageInfo packageInfo = packageManager.getPackageInfo(appInfo.packageName, 0);
            fingerprints.add(appInfo.packageName + "|" + getVersionCode(packageInfo) + "|"
                    + packageInfo.lastUpdateTime + "\n");
        }
        Collections.sort(fingerprints);
        return hashFingerprints(fingerprints);
    }

    public void rememberInventoryVersion(String deviceId, String inventoryHash,
            String revisionId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("hash_" + deviceId, inventoryHash)
                .putString("revision_" + deviceId, revisionId)
                .apply();
    }

    private String calculateInventoryHash(List<Map<String, Object>> apps)
            throws Exception {
        List<String> fingerprints = new ArrayList<>();
        for (Map<String, Object> app : apps) {
            fingerprints.add(String.valueOf(app.get("packageName")) + "|"
                    + String.valueOf(app.get("versionCode")) + "|"
                    + String.valueOf(app.get("packageUpdatedAt")) + "\n");
        }
        return hashFingerprints(fingerprints);
    }

    private String hashFingerprints(List<String> fingerprints) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String fingerprint : fingerprints) {
            digest.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder hash = new StringBuilder();
        for (byte item : digest.digest()) {
            hash.append(String.format("%02x", item & 0xff));
        }
        return hash.toString();
    }
    private long getVersionCode(PackageInfo packageInfo) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }

    private String encodeIconToBase64(String packageName) throws Exception {
        Drawable drawable = packageManager.getApplicationIcon(packageName);
        Bitmap bitmap = drawableToBitmap(drawable);
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 48, 48, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.PNG, 100, output);
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        Bitmap bitmap = Bitmap.createBitmap(
                Math.max(1, drawable.getIntrinsicWidth()),
                Math.max(1, drawable.getIntrinsicHeight()),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private String sanitizeFirebaseKey(String key) {
        return key == null || key.isEmpty()
                ? "unknown" : key.replaceAll("[.#$\\[\\]/]", "_");
    }

    public interface OnSyncCompleteListener {
        void onSuccess(int appCount);

        void onError(String error);
    }
}