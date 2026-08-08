package online.monarchlabs.sentinel.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.Executor;

public final class ParentAccessGate {
    private static final long ACCESS_WINDOW_MS = 10 * 60 * 1000L;
    private static final String PREF_NAME = "parent_access_lock";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static long lastVerifiedAt = 0L;
    private static boolean promptShowing = false;

    private ParentAccessGate() {
    }

    public static void requireVerifiedParent(AppCompatActivity activity) {
        if (activity == null || activity.isFinishing() || hasFreshVerification() || promptShowing) {
            return;
        }
        if (!hasPin(activity)) {
            showCreatePinDialog(activity);
            return;
        }

        int authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK;
        int canAuthenticate = BiometricManager.from(activity).canAuthenticate(authenticators);
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            showVerifyPinDialog(activity);
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
                            showVerifyPinDialog(activity);
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
                .setNegativeButtonText("Use Sentinel PIN")
                .setAllowedAuthenticators(authenticators)
                .build();
        prompt.authenticate(promptInfo);
    }

    private static void showCreatePinDialog(AppCompatActivity activity) {
        promptShowing = true;
        LinearLayout container = pinContainer(activity);
        EditText pin = pinInput(activity, "Create 4-digit PIN");
        EditText confirmPin = pinInput(activity, "Confirm PIN");
        container.addView(pin);
        container.addView(confirmPin);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Protect Parent Controls")
                .setMessage("Create a Sentinel PIN so only the parent can change controls.")
                .setView(container)
                .setPositiveButton("Create PIN", null)
                .setNegativeButton("Close", (d, which) -> {
                    promptShowing = false;
                    activity.finish();
                })
                .setOnCancelListener(d -> {
                    promptShowing = false;
                    activity.finish();
                })
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pinValue = pin.getText().toString();
            String confirmValue = confirmPin.getText().toString();
            if (!isValidPin(pinValue)) {
                pin.setError("Use 4 digits");
                return;
            }
            if (!pinValue.equals(confirmValue)) {
                confirmPin.setError("PINs do not match");
                return;
            }
            savePin(activity, pinValue);
            markVerified();
            promptShowing = false;
            dialog.dismiss();
            Toast.makeText(activity, "Sentinel PIN created", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private static void showVerifyPinDialog(AppCompatActivity activity) {
        promptShowing = true;
        EditText pin = pinInput(activity, "Sentinel PIN");
        LinearLayout container = pinContainer(activity);
        container.addView(pin);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Verify Parent")
                .setMessage("Enter your Sentinel PIN to continue.")
                .setView(container)
                .setPositiveButton("Unlock", null)
                .setNegativeButton("Close", (d, which) -> {
                    promptShowing = false;
                    activity.finish();
                })
                .setOnCancelListener(d -> {
                    promptShowing = false;
                    activity.finish();
                })
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = pin.getText().toString();
            if (!verifyPin(activity, value)) {
                pin.setError("Incorrect PIN");
                return;
            }
            markVerified();
            promptShowing = false;
            dialog.dismiss();
        }));
        dialog.show();
    }

    private static LinearLayout pinContainer(Context context) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 20);
        container.setPadding(padding, 6, padding, 0);
        return container;
    }

    private static EditText pinInput(Context context, String hint) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setMaxEms(4);
        input.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
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

    private static boolean hasPin(Context context) {
        return prefs(context).contains(KEY_PIN_HASH) && prefs(context).contains(KEY_PIN_SALT);
    }

    private static boolean isValidPin(String pin) {
        return pin != null && pin.matches("\\d{4}");
    }

    private static void savePin(Context context, String pin) {
        String salt = generateSalt();
        prefs(context).edit()
                .putString(KEY_PIN_SALT, salt)
                .putString(KEY_PIN_HASH, hashPin(pin, salt))
                .apply();
    }

    private static boolean verifyPin(Context context, String pin) {
        if (!isValidPin(pin)) {
            return false;
        }
        String salt = prefs(context).getString(KEY_PIN_SALT, "");
        String storedHash = prefs(context).getString(KEY_PIN_HASH, "");
        return storedHash.equals(hashPin(pin, salt));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String generateSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return toHex(bytes);
    }

    private static String hashPin(String pin, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest((salt + ":" + pin).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }
}
