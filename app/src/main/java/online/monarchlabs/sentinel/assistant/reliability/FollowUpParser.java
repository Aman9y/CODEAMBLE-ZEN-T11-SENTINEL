package online.monarchlabs.sentinel.assistant.reliability;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

public final class FollowUpParser {
    public enum Type {
        YES,
        NO,
        CANCEL,
        FIRST,
        SECOND,
        NUMBER,
        DURATION,
        UNKNOWN
    }

    public static final class FollowUpResult {
        public final Type type;
        public final String raw;
        public final Object value; // holds Parsed duration long, index Integer, or confirmation Boolean

        public FollowUpResult(Type type, String raw, Object value) {
            this.type = type;
            this.raw = raw;
            this.value = value;
        }
    }

    public static FollowUpResult parse(String text) {
        if (text == null) {
            return new FollowUpResult(Type.UNKNOWN, "", null);
        }
        String trimmed = text.trim();
        String lower = trimmed.toLowerCase(Locale.US);

        if ("cancel".equals(lower) || "never mind".equals(lower)) {
            return new FollowUpResult(Type.CANCEL, trimmed, null);
        }

        if ("yes".equals(lower) || "confirm".equals(lower) || "ok".equals(lower) || "yep".equals(lower) || "true".equals(lower)) {
            return new FollowUpResult(Type.YES, trimmed, true);
        }

        if ("no".equals(lower) || "nope".equals(lower) || "false".equals(lower)) {
            return new FollowUpResult(Type.NO, trimmed, false);
        }

        if ("first".equals(lower) || "1st".equals(lower) || "the first".equals(lower) || "first one".equals(lower)) {
            return new FollowUpResult(Type.FIRST, trimmed, 0);
        }

        if ("second".equals(lower) || "2nd".equals(lower) || "the second".equals(lower) || "second one".equals(lower)) {
            return new FollowUpResult(Type.SECOND, trimmed, 1);
        }

        // Check for duration patterns like "30 mins", "30", "1 hour", "1.5h"
        Pattern durationPattern = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours)?$");
        Matcher durationMatcher = durationPattern.matcher(lower);
        if (durationMatcher.matches()) {
            double amount = Double.parseDouble(durationMatcher.group(1));
            String unit = durationMatcher.group(2);
            long millis;
            if (unit == null || unit.startsWith("m")) {
                millis = (long) (amount * TimeUnit.MINUTES.toMillis(1));
            } else {
                millis = (long) (amount * TimeUnit.HOURS.toMillis(1));
            }
            return new FollowUpResult(Type.DURATION, trimmed, millis);
        }

        // Generic number
        if (trimmed.matches("\\d+")) {
            return new FollowUpResult(Type.NUMBER, trimmed, Integer.parseInt(trimmed));
        }

        return new FollowUpResult(Type.UNKNOWN, trimmed, null);
    }
}
