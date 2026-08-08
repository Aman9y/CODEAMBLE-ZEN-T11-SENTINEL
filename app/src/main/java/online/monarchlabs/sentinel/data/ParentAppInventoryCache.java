package online.monarchlabs.sentinel.data;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class ParentAppInventoryCache {
    private static final String TAG = "ParentAppInventory";
    private static final String DIRECTORY = "parent_app_inventory";

    private ParentAppInventoryCache() {
    }

    public static final class Entry {
        public final String revisionId;
        public final Map<String, Object> apps;

        public Entry(String revisionId, Map<String, Object> apps) {
            this.revisionId = revisionId;
            this.apps = apps;
        }
    }

    public static Entry load(Context context, String parentUid, String deviceId) {
        AtomicFile file = fileFor(context, parentUid, deviceId);
        if (!file.getBaseFile().exists()) {
            return null;
        }

        try (FileInputStream input = file.openRead();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }

            JSONObject root = new JSONObject(
                    new String(output.toByteArray(), StandardCharsets.UTF_8));
            JSONObject appsJson = root.optJSONObject("apps");
            Map<String, Object> apps = appsJson != null
                    ? jsonObjectToMap(appsJson) : new HashMap<>();
            return new Entry(root.optString("revisionId", ""), apps);
        } catch (Exception error) {
            Log.w(TAG, "Ignoring unreadable inventory cache", error);
            file.delete();
            return null;
        }
    }

    public static void save(Context context, String parentUid, String deviceId,
            String revisionId, Map<String, Object> apps) {
        AtomicFile file = fileFor(context, parentUid, deviceId);
        FileOutputStream output = null;
        try {
            JSONObject root = new JSONObject();
            root.put("revisionId", revisionId != null ? revisionId : "");
            root.put("apps", new JSONObject(apps != null ? apps : new HashMap<>()));

            output = file.startWrite();
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) {
                file.failWrite(output);
            }
            Log.w(TAG, "Could not persist inventory cache", error);
        }
    }

    public static void clear(Context context, String parentUid, String deviceId) {
        fileFor(context, parentUid, deviceId).delete();
    }

    private static AtomicFile fileFor(Context context, String parentUid, String deviceId) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create inventory cache directory");
        }
        String scope = String.valueOf(parentUid) + ":" + String.valueOf(deviceId);
        return new AtomicFile(new File(directory, sha256(scope) + ".json"));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static Map<String, Object> jsonObjectToMap(JSONObject object)
            throws JSONException {
        Map<String, Object> result = new HashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, jsonValue(object.get(key)));
        }
        return result;
    }

    private static Object jsonValue(Object value) throws JSONException {
        if (value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            return jsonObjectToMap((JSONObject) value);
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> items = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                items.add(jsonValue(array.get(index)));
            }
            return items;
        }
        return value;
    }
}