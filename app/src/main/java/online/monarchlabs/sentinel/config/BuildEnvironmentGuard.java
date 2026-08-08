package online.monarchlabs.sentinel.config;

import android.content.Context;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import online.monarchlabs.sentinel.BuildConfig;

/** Prevents a build flavor from starting with credentials for another environment. */
public final class BuildEnvironmentGuard {
    private static final String TAG = "BuildEnvironment";

    private BuildEnvironmentGuard() {
    }

    public static void verify(Context context) {
        FirebaseApp app = FirebaseApp.getInstance();
        FirebaseOptions options = app.getOptions();

        String expectedProjectId = required(
                "EXPECTED_FIREBASE_PROJECT_ID",
                BuildConfig.EXPECTED_FIREBASE_PROJECT_ID);
        String expectedDatabaseUrl = normalizeUrl(required(
                "EXPECTED_FIREBASE_DATABASE_URL",
                BuildConfig.EXPECTED_FIREBASE_DATABASE_URL));
        String actualProjectId = required(
                "Firebase project ID",
                options.getProjectId());
        String actualDatabaseUrl = normalizeUrl(required(
                "Firebase database URL",
                options.getDatabaseUrl()));

        boolean development = "development".equals(BuildConfig.BUILD_ENVIRONMENT);
        if (development
                && (actualProjectId.equals(BuildConfig.PRODUCTION_FIREBASE_PROJECT_ID)
                || actualDatabaseUrl.equals(normalizeUrl(
                BuildConfig.PRODUCTION_FIREBASE_DATABASE_URL)))) {
            throw new IllegalStateException(
                    "Development build resolved production Firebase credentials.");
        }
        if (!expectedProjectId.equals(actualProjectId)
                || !expectedDatabaseUrl.equals(actualDatabaseUrl)) {
            throw new IllegalStateException(
                    "Firebase environment mismatch for "
                            + BuildConfig.BUILD_ENVIRONMENT
                            + ": expected " + expectedProjectId + " / "
                            + expectedDatabaseUrl + " but resolved "
                            + actualProjectId + " / " + actualDatabaseUrl);
        }

        required("APPWRITE_ENDPOINT", BuildConfig.APPWRITE_ENDPOINT);
        String appwriteProjectId = required(
                "APPWRITE_PROJECT_ID",
                BuildConfig.APPWRITE_PROJECT_ID);

        Log.i(TAG, "Environment=" + BuildConfig.BUILD_ENVIRONMENT
                + ", FirebaseProject=" + actualProjectId
                + ", Database=" + actualDatabaseUrl
                + ", AppwriteProject=" + appwriteProjectId);
    }

    private static String required(String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(label + " is not configured.");
        }
        return value.trim();
    }

    private static String normalizeUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(java.util.Locale.US);
    }
}

