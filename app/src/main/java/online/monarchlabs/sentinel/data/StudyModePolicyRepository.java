package online.monarchlabs.sentinel.data;

import android.util.Base64;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import online.monarchlabs.sentinel.models.StudyModePolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;

public final class StudyModePolicyRepository {
    private StudyModePolicyRepository() {
    }

    public static Task<DataSnapshot> read(String deviceId) {
        if (isBlank(deviceId)) {
            return Tasks.forException(new IllegalArgumentException("Missing child device id"));
        }
        return FirebaseDatabase.getInstance().getReference()
                .child(FirebaseSchemaV2Repository.ROOT)
                .child("device_modes")
                .child(deviceId)
                .child(StudyModeContract.MODE_ID)
                .get();
    }

    public static Task<Void> save(String deviceId, StudyModePolicy policy) {
        if (isBlank(deviceId)) {
            return Tasks.forException(new IllegalArgumentException("Missing child device id"));
        }
        if (policy == null) {
            return Tasks.forException(new IllegalArgumentException("Missing Study Mode policy"));
        }

        Map<String, Object> data = new LinkedHashMap<>(policy.toMap());
        data.put("blockedPackages", encodeBooleanMap(policy.blockedPackages));
        data.put("allowedOverrides", encodeBooleanMap(policy.allowedOverrides));
        data.put("modeId", StudyModeContract.MODE_ID);
        data.put("schemaVersion", StudyModeContract.POLICY_SCHEMA_VERSION);
        data.put("updatedAt", ServerValue.TIMESTAMP);

        return FirebaseDatabase.getInstance().getReference()
                .child(FirebaseSchemaV2Repository.ROOT)
                .child("device_modes")
                .child(deviceId)
                .child(StudyModeContract.MODE_ID)
                .setValue(data);
    }

    public static StudyModePolicy fromSnapshot(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) {
            return null;
        }
        Object raw = snapshot.getValue();
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        StudyModePolicy policy = StudyModePolicy.createDefault();
        policy.enabled = boolValue(map.get("enabled"), false);
        policy.timezone = stringValue(map.get("timezone"), StudyModeContract.DEFAULT_TIMEZONE);
        policy.days = parseDays(map.get("days"));
        policy.timeSlots = parseTimeSlots(map.get("timeSlots"));
        policy.categories = parseCategories(map.get("categories"));
        policy.blockedPackages = parseBooleanMap(map.get("blockedPackages"));
        policy.allowedOverrides = parseBooleanMap(map.get("allowedOverrides"));
        policy.sessionAllowedPackages = parseSessionAllows(map.get("sessionAllows"));
        policy.schemaVersion = intValue(map.get("schemaVersion"), 1);
        policy.updatedAt = longValue(map.get("updatedAt"), 0L);
        return policy;
    }

    private static List<String> parseDays(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                String day = normalizeDay(item);
                if (!day.isEmpty()) {
                    result.add(day);
                }
            }
        } else if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                String day = normalizeDay(item);
                if (!day.isEmpty()) {
                    result.add(day);
                }
            }
        }
        return result.isEmpty() ? StudyModePolicy.createDefault().days : result;
    }

    private static List<StudyModePolicy.TimeSlot> parseTimeSlots(Object value) {
        List<StudyModePolicy.TimeSlot> result = new ArrayList<>();
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                StudyModePolicy.TimeSlot slot = parseSlot(item);
                if (slot != null) {
                    result.add(slot);
                }
            }
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) {
                StudyModePolicy.TimeSlot slot = parseSlot(item);
                if (slot != null) {
                    result.add(slot);
                }
            }
        }
        return result.isEmpty() ? StudyModePolicy.createDefault().timeSlots : result;
    }

    private static StudyModePolicy.TimeSlot parseSlot(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        return new StudyModePolicy.TimeSlot(
                stringValue(map.get("start"), "15:00"),
                stringValue(map.get("end"), "18:00"));
    }

    private static Map<String, StudyModePolicy.CategorySelection> parseCategories(Object value) {
        StudyModePolicy defaults = StudyModePolicy.createDefault();
        Map<String, StudyModePolicy.CategorySelection> result = new LinkedHashMap<>(defaults.categories);
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String key = stringValue(entry.getKey(), "");
            if (key.isEmpty()) {
                continue;
            }
            boolean enabled = false;
            if (entry.getValue() instanceof Map) {
                enabled = boolValue(((Map<?, ?>) entry.getValue()).get("enabled"), false);
            } else {
                enabled = boolValue(entry.getValue(), false);
            }
            result.put(key, new StudyModePolicy.CategorySelection(enabled));
        }
        return result;
    }

    private static Map<String, Boolean> parseBooleanMap(Object value) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String key = decodePackageKey(stringValue(entry.getKey(), ""));
            if (!key.isEmpty()) {
                result.put(key, boolValue(entry.getValue(), false));
            }
        }
        return result;
    }


    private static Map<String, String> parseSessionAllows(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String packageName = decodePackageKey(stringValue(entry.getKey(), ""));
            String sessionKey = "";
            boolean allowed = false;
            Object rawValue = entry.getValue();
            if (rawValue instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) rawValue;
                packageName = stringValue(map.get("packageName"), packageName);
                sessionKey = stringValue(map.get("sessionKey"), "");
                allowed = boolValue(map.get("allowed"), true);
            } else if (rawValue instanceof String) {
                sessionKey = stringValue(rawValue, "");
                allowed = true;
            } else {
                allowed = boolValue(rawValue, false);
            }
            if (allowed && !isBlank(packageName) && !isBlank(sessionKey)) {
                result.put(packageName, sessionKey);
            }
        }
        return result;
    }

    private static Map<String, Boolean> encodeBooleanMap(Map<String, Boolean> source) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<String, Boolean> entry : source.entrySet()) {
            String key = encodePackageKey(entry.getKey());
            if (!key.isEmpty()) {
                result.put(key, Boolean.TRUE.equals(entry.getValue()));
            }
        }
        return result;
    }

    private static String encodePackageKey(String packageName) {
        if (isBlank(packageName)) {
            return "";
        }
        return "pkg_" + Base64.encodeToString(
                packageName.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String decodePackageKey(String key) {
        if (isBlank(key) || !key.startsWith("pkg_")) {
            return key == null ? "" : key;
        }
        try {
            byte[] decoded = Base64.decode(
                    key.substring(4),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeDay(Object value) {
        String day = stringValue(value, "").trim().toUpperCase(Locale.US);
        switch (day) {
            case "SUN":
            case "MON":
            case "TUE":
            case "WED":
            case "THU":
            case "FRI":
            case "SAT":
                return day;
            default:
                return "";
        }
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
