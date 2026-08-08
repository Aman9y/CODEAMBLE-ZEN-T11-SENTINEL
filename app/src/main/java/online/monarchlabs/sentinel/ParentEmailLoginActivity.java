package online.monarchlabs.sentinel;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextWatcher;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;

/**
 * Parent email/password login activity.
 * Authenticates existing parents via Firebase Auth.
 */
public class ParentEmailLoginActivity extends BaseActivity {
    private static final String TAG = "ParentEmailLogin";
    private static final String INVALID_LOGIN_MESSAGE =
            "Sign-in failed. Check your details or create an account.";

    private ScrollView loginScrollView;
    private TextInputLayout tilEmail, tilPassword;
    private EditText etEmail, etPassword;
    private Button btnLogin;
    private TextView tvSignupLink;
    private TextView tvForgotPassword;

    private FirebaseAuth mAuth;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_email_login);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        mAuth = FirebaseAuth.getInstance();
        sessionManager = new SessionManager(this);

        initViews();
        setupKeyboardHandling();
        setupListeners();
    }

    private void initViews() {
        loginScrollView = findViewById(R.id.loginScrollView);
        tilEmail = findViewById(R.id.tilEmail);
        tilPassword = findViewById(R.id.tilPassword);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvSignupLink = findViewById(R.id.tvSignupLink);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
        setupSignupLink();
    }

    private void setupSignupLink() {
        String text = "Don't have an account? Sign Up";
        SpannableString spannableString = new SpannableString(text);
        int startIndex = text.indexOf("Sign Up");
        if (startIndex != -1) {
            spannableString.setSpan(
                new ForegroundColorSpan(Color.parseColor("#2563EB")),
                startIndex,
                startIndex + "Sign Up".length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            spannableString.setSpan(
                new StyleSpan(Typeface.BOLD),
                startIndex,
                startIndex + "Sign Up".length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        tvSignupLink.setText(spannableString);
    }

    private void setupKeyboardHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(loginScrollView, (view, insets) -> {
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int bottomPadding = Math.max(imeBottom, systemBottom);
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottomPadding);
            return insets;
        });

        View.OnFocusChangeListener scrollIntoView = (view, hasFocus) -> {
            if (hasFocus) {
                view.post(() -> {
                    if (loginScrollView != null) {
                        loginScrollView.smoothScrollTo(0, Math.max(0, view.getBottom() - 64));
                    }
                });
            }
        };

        etEmail.setOnFocusChangeListener(scrollIntoView);
        etPassword.setOnFocusChangeListener(scrollIntoView);

        ViewCompat.requestApplyInsets(loginScrollView);
    }

    private void setupListeners() {
        btnLogin.setOnClickListener(v -> attemptLogin());

        tvSignupLink.setOnClickListener(v -> {
            startActivity(new Intent(this, ParentSignupActivity.class));
            finish();
        });

        tvForgotPassword.setOnClickListener(v -> {
            showAccountHelpOptions();
        });

        clearErrorOnChange(etEmail, tilEmail);
        clearErrorOnChange(etPassword, tilPassword);
    }

    private void clearErrorOnChange(EditText input, TextInputLayout layout) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void showAccountHelpOptions() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_parent_account_help, null);
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setView(dialogView);

        dialogView.findViewById(R.id.optionEmailCode).setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(new Intent(this, ForgotPasswordActivity.class));
        });
        dialogView.findViewById(R.id.optionResetPassword).setOnClickListener(v -> {
            dialog.dismiss();
            showResetPasswordEmailDialog();
        });
        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());

        showCustomDialog(dialog);
    }

    private void showResetPasswordEmailDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_parent_reset_password, null);
        EditText emailInput = dialogView.findViewById(R.id.etResetEmail);

        String currentInput = etEmail.getText().toString().trim();
        if (Patterns.EMAIL_ADDRESS.matcher(currentInput).matches()) {
            emailInput.setText(currentInput);
            emailInput.setSelection(currentInput.length());
        }

        AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setView(dialogView);
        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnSendResetLink).setOnClickListener(v -> {
            String resetEmail = emailInput.getText().toString().trim();
            if (TextUtils.isEmpty(resetEmail) || !Patterns.EMAIL_ADDRESS.matcher(resetEmail).matches()) {
                emailInput.setError("Enter a valid email address");
                emailInput.requestFocus();
                return;
            }

            dialog.dismiss();
            sendPasswordResetLink(resetEmail);
        });

        showCustomDialog(dialog);
    }

    private void showCustomDialog(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    private void sendPasswordResetLink(String resetEmail) {
        if (TextUtils.isEmpty(resetEmail) || !Patterns.EMAIL_ADDRESS.matcher(resetEmail).matches()) {
            Toast.makeText(this, "Enter a valid email address.", Toast.LENGTH_LONG).show();
            return;
        }

        setLoading(true);
        mAuth.sendPasswordResetEmail(resetEmail)
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                                .setTitle("Password Reset Link Sent")
                                .setMessage("We've sent a password reset link to:\n\n" + resetEmail
                                        + "\n\nPlease check your inbox and spam folder.")
                                .setPositiveButton("OK", null)
                                .show();
                    } else {
                        String message = task.getException() != null && task.getException().getMessage() != null
                                ? task.getException().getMessage()
                                : "Could not send password reset link. Please try again.";
                        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                                .setTitle("Could Not Send Reset Link")
                                .setMessage(message)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
    }

    private void attemptLogin() {
        tilEmail.setError(null);
        tilPassword.setError(null);

        String accountId = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        // Validate
        if (TextUtils.isEmpty(accountId)) {
            tilEmail.setError("Email or phone is required");
            etEmail.requestFocus();
            return;
        }

        boolean isEmailLogin = Patterns.EMAIL_ADDRESS.matcher(accountId).matches();
        if (!isEmailLogin && !online.monarchlabs.sentinel.utils.PhoneUtils.isValid(accountId)) {
            tilEmail.setError("Enter a valid email or phone number");
            etEmail.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("Password is required");
            etPassword.requestFocus();
            return;
        }

        if (password.length() < 6) {
            tilPassword.setError("Password must be at least 6 characters");
            etPassword.requestFocus();
            return;
        }

        setLoading(true);
        if (isEmailLogin) {
            loginWithEmailAndPassword(accountId, password);
        } else {
            resolvePhoneAndLogin(accountId, password);
        }
    }

    private void loginWithEmailAndPassword(String email, String password) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Firebase Auth successful");
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            // Check for existing device session before proceeding
                            checkDeviceSession(user.getUid());
                        }
                    } else {
                        setLoading(false);
                        Log.e(TAG, "Login failed", task.getException());
                        Toast.makeText(this, getLoginFailureMessage(task.getException()), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private String getLoginFailureMessage(Exception error) {
        if (error instanceof FirebaseNetworkException) {
            return "Unable to connect. Check your internet connection and try again.";
        }
        if (error instanceof FirebaseTooManyRequestsException) {
            return "Too many login attempts. Please wait a few minutes and try again.";
        }

        // Keep unknown-account and wrong-password failures identical to avoid account enumeration.
        return INVALID_LOGIN_MESSAGE;
    }

    private void resolvePhoneAndLogin(String phoneInput, String password) {
        String normalizedPhone = online.monarchlabs.sentinel.utils.PhoneUtils.normalize(phoneInput);
        
        DatabaseReference indexRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("directory")
                .child("phone_to_email")
                .child(normalizedPhone);
                
        final boolean[] completed = {false};
        android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeout = () -> {
            if (!completed[0]) {
                completed[0] = true;
                setLoading(false);
                Toast.makeText(this, "Connection timeout. Please try again.", Toast.LENGTH_LONG).show();
            }
        };
        timeoutHandler.postDelayed(timeout, 10_000L);
                
        indexRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (completed[0]) return;
                completed[0] = true;
                timeoutHandler.removeCallbacks(timeout);
                
                if (snapshot.exists()) {
                    String email = snapshot.getValue(String.class);
                    if (email != null && !email.isEmpty()) {
                        Log.d(TAG, "Phone resolved to email, proceeding with login.");
                        loginWithEmailAndPassword(email, password);
                    } else {
                        handlePhoneResolutionFailed();
                    }
                } else {
                    handlePhoneResolutionFailed();
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (completed[0]) return;
                completed[0] = true;
                timeoutHandler.removeCallbacks(timeout);
                
                setLoading(false);
                Log.e(TAG, "Phone-to-email resolution failed", error.toException());
                Toast.makeText(ParentEmailLoginActivity.this, 
                        "Network error. Please try again.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handlePhoneResolutionFailed() {
        setLoading(false);
        // Do not leak that the account doesn't exist to prevent enumeration.
        tilEmail.setError(INVALID_LOGIN_MESSAGE);
        etEmail.requestFocus();
    }

    private void loadParentDataAndProceed(String uid) {
        DatabaseReference profileRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_profiles")
                .child(uid);

        final boolean[] completed = {false};
        Handler timeoutHandler = new Handler(Looper.getMainLooper());
        Runnable timeout = () -> {
            if (!completed[0]) {
                setLoading(false);
                Toast.makeText(this,
                        "Connection timeout. Please try again.",
                        Toast.LENGTH_LONG).show();
            }
        };
        timeoutHandler.postDelayed(timeout, 15_000L);

        profileRef.get()
                .addOnSuccessListener(snapshot -> {
                    completed[0] = true;
                    timeoutHandler.removeCallbacks(timeout);
                    if (!snapshot.exists()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null && user.getEmail() != null) {
                            recreateParentProfile(uid, user.getEmail());
                        } else {
                            setLoading(false);
                            Toast.makeText(this,
                                    "Parent profile is unavailable.",
                                    Toast.LENGTH_LONG).show();
                        }
                        return;
                    }

                    String name = snapshot.child("displayName")
                            .getValue(String.class);
                    String phone = snapshot.child("phone")
                            .getValue(String.class);
                    String email = snapshot.child("email")
                            .getValue(String.class);
                    sessionManager.saveParentSession(
                            phone, uid, ParentUtils.getParentDeviceName(),
                            TextUtils.isEmpty(name) ? "Parent" : name);
                    ensurePhoneLoginIndex(uid, email, phone);
                    saveActiveDeviceToFirebase(uid);

                    setLoading(false);
                    Intent intent = new Intent(
                            this, ParentDashboardActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(error -> {
                    completed[0] = true;
                    timeoutHandler.removeCallbacks(timeout);
                    setLoading(false);
                    Log.e(TAG, "V2 parent profile read failed", error);
                    Toast.makeText(this,
                            "Could not load the parent profile.",
                            Toast.LENGTH_LONG).show();
                });
    }

    private void recreateParentProfile(String uid, String email) {
        String name = "Parent";
        if (!TextUtils.isEmpty(email) && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@'))
                    .replaceAll("[^A-Za-z]", "");
            if (!local.isEmpty()) {
                name = Character.toUpperCase(local.charAt(0))
                        + local.substring(1);
            }
        }
        String deviceId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        final String resolvedName = name;
        FirebaseSchemaV2Repository.syncParentIdentity(
                        uid, resolvedName, email, "", deviceId,
                        System.currentTimeMillis())
                .addOnSuccessListener(ignored -> {
                    sessionManager.saveParentSession(
                            "", uid, ParentUtils.getParentDeviceName(),
                            resolvedName);
                    ensurePhoneLoginIndex(uid, email, "");
                    saveActiveDeviceToFirebase(uid);
                    setLoading(false);
                    Intent intent = new Intent(
                            this, ParentDashboardActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(error -> {
                    setLoading(false);
                    Log.e(TAG, "Could not recreate v2 parent profile", error);
                    Toast.makeText(this,
                            "Unable to restore the parent profile.",
                            Toast.LENGTH_LONG).show();
                });
    }

    /**
     * Check active device sessions — multi-device login (max 3 devices).
     * Cleans up stale sessions and enforces the device limit.
     */
    private void checkDeviceSession(String uid) {
        DatabaseReference activeDevicesRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_clients")
                .child(uid);

        String currentDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        activeDevicesRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                long currentTime = System.currentTimeMillis();
                long STALE_THRESHOLD = 90 * 1000; // 90 seconds
                int MAX_DEVICES = 3;

                // Collect live (non-stale) sessions, excluding current device
                java.util.List<String> liveDeviceIds = new java.util.ArrayList<>();
                java.util.Map<String, Long> deviceHeartbeats = new java.util.HashMap<>();

                for (DataSnapshot child : snapshot.getChildren()) {
                    String deviceId = child.getKey();
                    Long lastHeartbeat = child.child("lastHeartbeat").getValue(Long.class);

                    if (deviceId == null) continue;

                    // Current device always gets a slot — skip it
                    if (deviceId.equals(currentDeviceId)) continue;

                    boolean isAlive = lastHeartbeat != null && (currentTime - lastHeartbeat) < STALE_THRESHOLD;

                    if (!isAlive) {
                        // Stale session — remove it
                        Log.d(TAG, "🔓 Removing stale device session: " + deviceId);
                        activeDevicesRef.child(deviceId).removeValue();
                    } else {
                        liveDeviceIds.add(deviceId);
                        deviceHeartbeats.put(deviceId, lastHeartbeat);
                    }
                }

                // If live devices already at max (excluding current), evict the oldest
                if (liveDeviceIds.size() >= MAX_DEVICES) {
                    String oldestDeviceId = null;
                    long oldestHeartbeat = Long.MAX_VALUE;
                    for (String id : liveDeviceIds) {
                        Long hb = deviceHeartbeats.get(id);
                        if (hb != null && hb < oldestHeartbeat) {
                            oldestHeartbeat = hb;
                            oldestDeviceId = id;
                        }
                    }
                    if (oldestDeviceId != null) {
                        Log.d(TAG, "🔓 Device limit reached (" + MAX_DEVICES + "). Evicting oldest: " + oldestDeviceId);
                        activeDevicesRef.child(oldestDeviceId).removeValue();
                    }
                }

                // Always proceed with login
                Log.d(TAG, "✅ Multi-device session check passed — proceeding with login");
                loadParentDataAndProceed(uid);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                setLoading(false);
                Log.e(TAG, "Device session check failed: " + error.getMessage());
                Toast.makeText(ParentEmailLoginActivity.this,
                        "Error checking device session", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Save active device information to Firebase to track this login session.
     * Writes to activeDevices/{deviceId} for multi-device support.
     */
    private void saveActiveDeviceToFirebase(String uid) {
        String deviceId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("deviceModel", Build.MODEL);
        data.put("loginTimestamp", System.currentTimeMillis());
        data.put("lastHeartbeat", System.currentTimeMillis());
        data.put("environment", BuildConfig.BUILD_ENVIRONMENT);

        FirebaseDatabase.getInstance().getReference("v2")
                .child("parent_clients").child(uid).child(deviceId)
                .setValue(data)
                .addOnSuccessListener(ignored ->
                        sessionManager.saveDeviceModel(Build.MODEL))
                .addOnFailureListener(error ->
                        Log.w(TAG, "Parent client registration failed", error));
    }

    private void ensurePhoneLoginIndex(String uid, String email, String phone) {
        new online.monarchlabs.sentinel.services.ParentDirectoryService(this)
                .registerProfileIndex(uid, email, phone);
    }



    /**
     * Show floating dialog when login is blocked due to another device being logged
     * in
     */
    private void showDeviceBlockedDialog(String deviceModel) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_already_logged_in, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        AlertDialog dialog = builder.create();

        // Set device model text
        TextView tvDeviceModel = dialogView.findViewById(R.id.tvDeviceModel);
        tvDeviceModel.setText(deviceModel);

        // OK button to dismiss
        Button btnOk = dialogView.findViewById(R.id.btnOk);
        btnOk.setOnClickListener(v -> dialog.dismiss());

        // Make dialog background transparent for floating effect
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialog.show();
    }

    private void setLoading(boolean loading) {
        btnLogin.setEnabled(!loading);
        btnLogin.setText(loading ? "Logging in..." : "Login");
        etEmail.setEnabled(!loading);
        etPassword.setEnabled(!loading);
    }
}
