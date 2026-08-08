package online.monarchlabs.sentinel.utils;

import android.os.Build;
import android.util.Log;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Safe Crashlytics wrapper for diagnostics that must never crash the app.
 */
public final class CrashlyticsLogger {
    private static final String TAG = "CrashlyticsLogger";

    private CrashlyticsLogger() {
    }

    public static void log(String component, String event) {
        try {
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.log(sanitize(component) + ": " + sanitize(event));
            crashlytics.setCustomKey("last_component", sanitize(component));
            crashlytics.setCustomKey("last_event", sanitize(event));
            crashlytics.setCustomKey("sdk_int", Build.VERSION.SDK_INT);
        } catch (Throwable throwable) {
            Log.w(TAG, "Crashlytics log skipped: " + throwable.getMessage());
        }
    }

    public static void recordNonFatal(String component, String event, Throwable throwable) {
        try {
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.log(sanitize(component) + ": " + sanitize(event));
            crashlytics.setCustomKey("last_component", sanitize(component));
            crashlytics.setCustomKey("last_event", sanitize(event));
            crashlytics.setCustomKey("sdk_int", Build.VERSION.SDK_INT);
            if (throwable != null) {
                crashlytics.recordException(throwable);
            }
        } catch (Throwable crashlyticsError) {
            Log.w(TAG, "Crashlytics non-fatal skipped: " + crashlyticsError.getMessage());
        }
    }

    public static void recordForegroundServiceRejected(
            String component,
            String foregroundServiceType,
            Throwable throwable
    ) {
        try {
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.log(sanitize(component) + ": foreground service start rejected");
            crashlytics.setCustomKey("last_component", sanitize(component));
            crashlytics.setCustomKey("last_event", "foreground_service_start_rejected");
            crashlytics.setCustomKey("foreground_service_type", sanitize(foregroundServiceType));
            crashlytics.setCustomKey("sdk_int", Build.VERSION.SDK_INT);
            if (throwable != null) {
                crashlytics.recordException(throwable);
            }
        } catch (Throwable crashlyticsError) {
            Log.w(TAG, "Crashlytics foreground-service log skipped: " + crashlyticsError.getMessage());
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }

        String sanitized = value.replaceAll("[^A-Za-z0-9_:. -]", "_");
        return sanitized.length() > 100 ? sanitized.substring(0, 100) : sanitized;
    }
}
