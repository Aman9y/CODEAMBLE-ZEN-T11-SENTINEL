package online.monarchlabs.sentinel.utils;

import online.monarchlabs.sentinel.data.StudyModeContract;
import online.monarchlabs.sentinel.models.StudyModePolicy;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Local evaluator for Study Mode V1 schedules. */
public final class StudyModeScheduleEvaluator {
    private StudyModeScheduleEvaluator() {
    }

    public static boolean isActiveNow(StudyModePolicy policy) {
        long now = System.currentTimeMillis();
        String timezone = policy != null && policy.timezone != null && !policy.timezone.isEmpty()
                ? policy.timezone
                : StudyModeContract.DEFAULT_TIMEZONE;
        return isActiveAt(policy, now, TimeZone.getTimeZone(timezone));
    }

    public static boolean isActiveAt(StudyModePolicy policy, long timestampMillis, TimeZone zone) {
        if (policy == null || !policy.enabled || policy.days == null
                || policy.days.isEmpty() || policy.timeSlots == null
                || policy.timeSlots.isEmpty()) {
            return false;
        }

        Calendar calendar = Calendar.getInstance(zone != null ? zone : TimeZone.getDefault());
        calendar.setTimeInMillis(timestampMillis);
        if (!selectedDays(policy.days).contains(dayCode(calendar))) {
            return false;
        }

        int minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        int checked = 0;
        for (StudyModePolicy.TimeSlot slot : policy.timeSlots) {
            if (checked >= StudyModeContract.MAX_TIME_SLOTS) {
                break;
            }
            checked++;
            if (slotContains(slot, minuteOfDay)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOverlappingSlots(List<StudyModePolicy.TimeSlot> slots) {
        if (slots == null) {
            return false;
        }
        for (int i = 0; i < slots.size(); i++) {
            StudyModePolicy.TimeSlot first = slots.get(i);
            int firstStart = parseMinutes(first != null ? first.start : null);
            int firstEnd = parseMinutes(first != null ? first.end : null);
            if (firstStart < 0 || firstEnd <= firstStart) {
                continue;
            }
            for (int j = i + 1; j < slots.size(); j++) {
                StudyModePolicy.TimeSlot second = slots.get(j);
                int secondStart = parseMinutes(second != null ? second.start : null);
                int secondEnd = parseMinutes(second != null ? second.end : null);
                if (secondStart < 0 || secondEnd <= secondStart) {
                    continue;
                }
                if (firstStart < secondEnd && secondStart < firstEnd) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isValidSameDaySlot(StudyModePolicy.TimeSlot slot) {
        int start = parseMinutes(slot != null ? slot.start : null);
        int end = parseMinutes(slot != null ? slot.end : null);
        return start >= 0 && end > start;
    }

    private static boolean slotContains(StudyModePolicy.TimeSlot slot, int minuteOfDay) {
        if (!isValidSameDaySlot(slot)) {
            return false;
        }
        int start = parseMinutes(slot.start);
        int end = parseMinutes(slot.end);
        return minuteOfDay >= start && minuteOfDay < end;
    }

    private static int parseMinutes(String hhmm) {
        if (hhmm == null || !hhmm.matches("\\d{2}:\\d{2}")) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(hhmm.substring(0, 2));
            int minute = Integer.parseInt(hhmm.substring(3, 5));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return -1;
            }
            return hour * 60 + minute;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static Set<String> selectedDays(List<String> days) {
        Set<String> result = new HashSet<>();
        for (String day : days) {
            if (day != null) {
                result.add(day.trim().toUpperCase(Locale.US));
            }
        }
        return result;
    }

    private static String dayCode(Calendar calendar) {
        switch (calendar.get(Calendar.DAY_OF_WEEK)) {
            case Calendar.MONDAY:
                return "MON";
            case Calendar.TUESDAY:
                return "TUE";
            case Calendar.WEDNESDAY:
                return "WED";
            case Calendar.THURSDAY:
                return "THU";
            case Calendar.FRIDAY:
                return "FRI";
            case Calendar.SATURDAY:
                return "SAT";
            case Calendar.SUNDAY:
            default:
                return "SUN";
        }
    }
}
