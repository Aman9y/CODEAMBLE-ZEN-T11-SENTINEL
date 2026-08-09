package online.monarchlabs.sentinel.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/** Keeps internal identifiers and corrupted text out of child-facing labels. */
public final class ChildDisplayName {
    public static final String FALLBACK = "Child Device";

    private static final Pattern UUID = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern HEX_TOKEN = Pattern.compile("(?i)^[0-9a-f]{16,64}$");
    private static final Pattern LONG_MACHINE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{24,}$");

    private ChildDisplayName() {
    }

    public static String resolve(String deviceId, String... candidates) {
        if (candidates != null) {
            for (String candidate : candidates) {
                String cleaned = clean(candidate);
                if (isDisplayable(deviceId, cleaned)) {
                    return cleaned;
                }
            }
        }
        return FALLBACK;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static boolean isDisplayable(String deviceId, String value) {
        if (value.isEmpty() || value.length() > 80 || containsMojibake(value)) {
            return false;
        }
        if (deviceId != null && value.equalsIgnoreCase(deviceId.trim())) {
            return false;
        }

        String lower = value.toLowerCase(Locale.US);
        if ("null".equals(lower) || "undefined".equals(lower)
                || "unknown".equals(lower) || lower.startsWith("conn_")
                || lower.startsWith("migrated_")) {
            return false;
        }
        return !UUID.matcher(value).matches()
                && !HEX_TOKEN.matcher(value).matches()
                && !LONG_MACHINE_TOKEN.matcher(value).matches();
    }

    private static boolean containsMojibake(String value) {
        return value.indexOf('\uFFFD') >= 0
                || value.contains("Ã")
                || value.contains("Â")
                || value.contains("â€")
                || value.contains("âœ")
                || value.contains("âš")
                || value.contains("ðŸ");
    }
}
