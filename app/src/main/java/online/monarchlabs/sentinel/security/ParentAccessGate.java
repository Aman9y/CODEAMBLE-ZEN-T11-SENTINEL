package online.monarchlabs.sentinel.security;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

public final class ParentAccessGate {
    private static final long ACCESS_WINDOW_MS = 10 * 60 * 1000L;
    private static long lastVerifiedAt = 0L;
    private static boolean promptShowing = false;

    private ParentAccessGate() {
    }

    public static void requireVerifiedParent(AppCompatActivity activity) {
        if (activity == null || activity.isFinishing() || hasFreshVerification() || promptShowing) {
            return;
        }

        int authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        int canAuthenticate = BiometricManager.from(activity).canAuthenticate(authenticators);
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            showScreenLockRequired(activity);
            return;
        }

        promptShowing = true;
        Executor executor = ContextCompat.getMainExecutor(activity);
        BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        promptShowing = false;
                        markVerified();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                            @NonNull CharSequence errString) {
                        promptShowing = false;
                        if (!activity.isFinishing()) {
                            Toast.makeText(activity, "Parent verification required.",
                                    Toast.LENGTH_SHORT).show();
                            new Handler(Looper.getMainLooper()).postDelayed(activity::finish, 250L);
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        Toast.makeText(activity, "Try again.", Toast.LENGTH_SHORT).show();
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify Parent")
                .setSubtitle("Unlock to manage Sentinel controls")
                .setAllowedAuthenticators(authenticators)
                .build();
        prompt.authenticate(promptInfo);
    }

    private static void showScreenLockRequired(AppCompatActivity activity) {
        promptShowing = true;
        new AlertDialog.Builder(activity)
                .setTitle("Parent Lock Required")
                .setMessage("Set a phone screen lock to protect Sentinel parent controls from unauthorized changes.")
                .setPositiveButton("Open Security Settings", (dialog, which) -> {
                    promptShowing = false;
                    openSecuritySettings(activity);
                    activity.finish();
                })
                .setNegativeButton("Close", (dialog, which) -> {
                    promptShowing = false;
                    activity.finish();
                })
                .setOnCancelListener(dialog -> {
                    promptShowing = false;
                    activity.finish();
                })
                .show();
    }

    private static void openSecuritySettings(AppCompatActivity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        } catch (Exception ignored) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    public static boolean hasFreshVerification() {
        return System.currentTimeMillis() - lastVerifiedAt < ACCESS_WINDOW_MS;
    }

    public static void clearVerification() {
        lastVerifiedAt = 0L;
    }

    private static void markVerified() {
        lastVerifiedAt = System.currentTimeMillis();
    }
}
