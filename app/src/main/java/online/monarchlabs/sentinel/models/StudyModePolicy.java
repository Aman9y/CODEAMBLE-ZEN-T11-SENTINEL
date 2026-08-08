package online.monarchlabs.sentinel.models;

import online.monarchlabs.sentinel.AppBlockingPolicy;
import online.monarchlabs.sentinel.data.StudyModeContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Study Mode V1 policy stored under v2/device_modes/{deviceId}/study. */
public class StudyModePolicy {
    public boolean enabled;
    public String timezone;
    public List<String> days;
    public List<TimeSlot> timeSlots;
    public Map<String, CategorySelection> categories;
    public Map<String, Boolean> blockedPackages;
    public Map<String, Boolean> allowedOverrides;
    public Map<String, String> sessionAllowedPackages;
    public int schemaVersion;
    public long updatedAt;

    public StudyModePolicy() {
        timezone = StudyModeContract.DEFAULT_TIMEZONE;
        days = defaultWeekdays();
        timeSlots = new ArrayList<>();
        categories = defaultCategories();
        blockedPackages = new LinkedHashMap<>();
        allowedOverrides = new LinkedHashMap<>();
        sessionAllowedPackages = new LinkedHashMap<>();
        schemaVersion = StudyModeContract.POLICY_SCHEMA_VERSION;
    }

    public static StudyModePolicy createDefault() {
        StudyModePolicy policy = new StudyModePolicy();
        policy.timeSlots.add(new TimeSlot("15:00", "18:00"));
        return policy;
    }

    public Set<String> getEffectiveBlockedPackages() {
        Set<String> result = new HashSet<>();
        if (blockedPackages != null) {
            for (Map.Entry<String, Boolean> entry : blockedPackages.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())
                        && !AppBlockingPolicy.isUnblockable(entry.getKey())) {
                    result.add(entry.getKey());
                }
            }
        }
        if (allowedOverrides != null) {
            for (Map.Entry<String, Boolean> entry : allowedOverrides.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    result.remove(entry.getKey());
                }
            }
        }
        return result;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", enabled);
        map.put("timezone", timezone);
        map.put("days", days);
        map.put("timeSlots", timeSlotsToMap());
        map.put("categories", categoriesToMap());
        map.put("blockedPackages", blockedPackages != null ? blockedPackages : new HashMap<>());
        map.put("allowedOverrides", allowedOverrides != null ? allowedOverrides : new HashMap<>());
        map.put("schemaVersion", schemaVersion);
        map.put("updatedAt", updatedAt);
        return map;
    }

    private Map<String, Object> timeSlotsToMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (timeSlots == null) {
            return result;
        }
        int count = Math.min(timeSlots.size(), StudyModeContract.MAX_TIME_SLOTS);
        for (int i = 0; i < count; i++) {
            TimeSlot slot = timeSlots.get(i);
            if (slot != null) {
                result.put("slot_" + (i + 1), slot.toMap());
            }
        }
        return result;
    }

    private Map<String, Object> categoriesToMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (categories == null) {
            return result;
        }
        for (Map.Entry<String, CategorySelection> entry : categories.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue().toMap());
            }
        }
        return result;
    }

    private static List<String> defaultWeekdays() {
        return new ArrayList<>(Arrays.asList("MON", "TUE", "WED", "THU", "FRI"));
    }

    private static Map<String, CategorySelection> defaultCategories() {
        Map<String, CategorySelection> result = new LinkedHashMap<>();
        result.put(StudyModeContract.CATEGORY_SOCIAL, new CategorySelection(true));
        result.put(StudyModeContract.CATEGORY_GAMES, new CategorySelection(true));
        result.put(StudyModeContract.CATEGORY_ENTERTAINMENT, new CategorySelection(true));
        result.put(StudyModeContract.CATEGORY_OTHER, new CategorySelection(false));
        return result;
    }

    public static class TimeSlot {
        public String start;
        public String end;

        public TimeSlot() {
        }

        public TimeSlot(String start, String end) {
            this.start = start;
            this.end = end;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("start", start);
            map.put("end", end);
            return map;
        }
    }

    public static class CategorySelection {
        public boolean enabled;

        public CategorySelection() {
        }

        public CategorySelection(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("enabled", enabled);
            return map;
        }
    }
}
