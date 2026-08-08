package online.monarchlabs.sentinel.assistant.parser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeParser {
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("\\b(?:for|to)\\s+(\\d+)\\s*(minute|minutes|min|mins|m|hour|hours|h|hr|hrs|day|days|d|ghanta|ghante|din)\\b");
    private static final Pattern DURATION_PATTERN_REVERSED =
            Pattern.compile("\\b(\\d+)\\s*(minute|minutes|min|mins|m|hour|hours|h|hr|hrs|day|days|d|ghanta|ghante|din)\\s+(?:for|to)\\b");
    private static final Pattern DURATION_STANDALONE_PATTERN =
            Pattern.compile("\\b(\\d+)\\s*(minute|minutes|min|mins|m|hour|hours|h|hr|hrs|day|days|d|ghanta|ghante|din)\\b");
    private static final Pattern RANGE_WITH_MERIDIEM =
            Pattern.compile("\\b(?:from|for|between)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s+(?:to|and)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b");
        private static final Pattern RANGE_WITH_MERIDIEM_HINGLISH =
            Pattern.compile("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s+se\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s*(?:tak)?\\b");
    private static final Pattern AMBIGUOUS_RANGE =
            Pattern.compile("\\b(?:from|for|between)\\s+(\\d{1,2})\\s+(?:to|and)\\s+(\\d{1,2})\\b");
        private static final Pattern AMBIGUOUS_RANGE_HINGLISH =
            Pattern.compile("\\b(\\d{1,2})\\s+se\\s+(\\d{1,2})\\s*(?:tak)?\\b");

    /**
     * Conversational time words parents actually type, mapped to concrete
     * minute ranges. Keys are checked longest-first with word boundaries so
     * "tonight" never collides with "night". Start/end are minutes-of-day.
     */
    private static final Map<String, int[]> RELATIVE_TIMES = new LinkedHashMap<>();
    static {
        RELATIVE_TIMES.put("this evening", new int[]{18 * 60, 22 * 60});
        RELATIVE_TIMES.put("this morning", new int[]{6 * 60, 9 * 60});
        RELATIVE_TIMES.put("late night", new int[]{21 * 60, 23 * 60 + 59});
        RELATIVE_TIMES.put("tonight", new int[]{18 * 60, 22 * 60});
        RELATIVE_TIMES.put("evening", new int[]{18 * 60, 22 * 60});
        RELATIVE_TIMES.put("morning", new int[]{6 * 60, 9 * 60});
        RELATIVE_TIMES.put("afternoon", new int[]{12 * 60, 17 * 60});
        RELATIVE_TIMES.put("night", new int[]{21 * 60, 23 * 60 + 59});
    }

    public Long parseDurationMillis(String input) {
        String lower = input == null ? "" : input.toLowerCase(java.util.Locale.US);
        Matcher matcher = DURATION_PATTERN.matcher(lower);
        if (!matcher.find()) {
            matcher = DURATION_PATTERN_REVERSED.matcher(lower);
            if (!matcher.find()) {
                matcher = DURATION_STANDALONE_PATTERN.matcher(lower);
                if (!matcher.find()) {
                    return null;
                }
            }
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        if (unit.startsWith("day") || "d".equals(unit)) {
            return amount * 24L * 60L * 60L * 1000L;
        }
        if (unit.startsWith("hour") || unit.startsWith("hr") || "h".equals(unit)) {
            return amount * 60L * 60L * 1000L;
        }
        return amount * 60L * 1000L;
    }

    public TimeRange parseTimeRange(String input) {
        String value = input == null ? "" : input.toLowerCase(java.util.Locale.US);
        Matcher matcher = RANGE_WITH_MERIDIEM.matcher(value);
        if (matcher.find()) {
            int start = toMinutes(matcher.group(1), matcher.group(2), matcher.group(3));
            int end = toMinutes(matcher.group(4), matcher.group(5), matcher.group(6));
            return new TimeRange(start, end);
        }
        matcher = RANGE_WITH_MERIDIEM_HINGLISH.matcher(value);
        if (matcher.find()) {
            int start = toMinutes(matcher.group(1), matcher.group(2), matcher.group(3));
            int end = toMinutes(matcher.group(4), matcher.group(5), matcher.group(6));
            return new TimeRange(start, end);
        }
        // Conversational time words (tonight, morning, ...).
        for (Map.Entry<String, int[]> entry : RELATIVE_TIMES.entrySet()) {
            if (containsWord(value, entry.getKey())) {
                return new TimeRange(entry.getValue()[0], entry.getValue()[1]);
            }
        }
        return null;
    }

    public boolean hasAmbiguousRange(String input) {
        String value = input == null ? "" : input.toLowerCase(java.util.Locale.US);
        return parseTimeRange(value) == null
                && (AMBIGUOUS_RANGE.matcher(value).find() || AMBIGUOUS_RANGE_HINGLISH.matcher(value).find());
    }

    public static boolean hasDuration(String input) {
        String value = input == null ? "" : input.toLowerCase(java.util.Locale.US);
        return DURATION_PATTERN.matcher(value).find()
                || DURATION_PATTERN_REVERSED.matcher(value).find()
                || DURATION_STANDALONE_PATTERN.matcher(value).find();
    }

    /** True if the input contains a conversational time word (tonight, morning, ...). */
    public static boolean hasRelativeTime(String input) {
        String value = input == null ? "" : input.toLowerCase(java.util.Locale.US);
        for (String key : RELATIVE_TIMES.keySet()) {
            if (containsWord(value, key)) {
                return true;
            }
        }
        return false;
    }

    public String parseRepeatRule(String input) {
        String value = input == null ? "" : input.toLowerCase(java.util.Locale.US);
        if (value.contains("every weekday")) {
            return "weekdays";
        }
        if (value.contains("every day")) {
            return "daily";
        }
        if (value.contains("daily")) {
            return "daily";
        }
        if (value.contains("weekends")) {
            return "weekends";
        }
        return null;
    }

    private static boolean containsWord(String input, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(input).find();
    }

    private int toMinutes(String hourValue, String minuteValue, String meridiem) {
        int hour = Integer.parseInt(hourValue);
        int minute = minuteValue == null ? 0 : Integer.parseInt(minuteValue);
        if ("pm".equals(meridiem) && hour != 12) {
            hour += 12;
        }
        if ("am".equals(meridiem) && hour == 12) {
            hour = 0;
        }
        return hour * 60 + minute;
    }
}
