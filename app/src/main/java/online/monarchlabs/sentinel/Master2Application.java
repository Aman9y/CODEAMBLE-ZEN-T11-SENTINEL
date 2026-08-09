package online.monarchlabs.sentinel;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import online.monarchlabs.sentinel.config.BuildEnvironmentGuard;
import online.monarchlabs.sentinel.security.ParentAccessGate;
import online.monarchlabs.sentinel.utils.CrashlyticsLogger;

public class Master2Application extends Application {
    private static final String TAG = "Master2Application";
    private static int startedActivityCount;

    public static boolean isAppUiVisible() {
        return startedActivityCount > 0;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                startedActivityCount++;
            }

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivityCount = Math.max(0, startedActivityCount - 1);
                if (startedActivityCount == 0) {
                    ParentAccessGate.onAppUiHidden();
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });

        // Keep the entire app in light mode regardless of the device theme.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);

        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this);
            }

            if (!FirebaseApp.getApps(this).isEmpty()) {
                BuildEnvironmentGuard.verify(this);
                FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(true);

                FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
                crashlytics.setCrashlyticsCollectionEnabled(true);
                Log.d(TAG, "Firebase initialized in Application");
                CrashlyticsLogger.log(TAG, "app_start");

                SessionManager sessionManager = new SessionManager(this);
                String role = sessionManager.getUserType();

                crashlytics.setCustomKey(
                        "role",
                        role != null && !role.isEmpty() ? role : "unknown"
                );
                crashlytics.setUserId("");

                if (role == null || role.isEmpty()) {
                    Log.w(TAG, "Crashlytics: unknown or empty user role");
                    crashlytics.log("Unknown or empty user role during app startup");
                } else if ("parent".equals(role)) {
                    online.monarchlabs.sentinel.services.PermissionEventListener.start(this);
                }

            } else {
                Log.w(TAG, "Firebase initialization returned null");
            }
        } catch (IllegalStateException environmentError) {
            Log.e(TAG, "Build environment validation failed", environmentError);
            throw environmentError;
        } catch (Throwable throwable) {
            Log.e(TAG, "Firebase initialization failed in Application", throwable);
            CrashlyticsLogger.recordNonFatal(TAG, "firebase_initialization_failed", throwable);
        }
    }
}
