package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.text.TextWatcher;
import android.text.Editable;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.widget.ScrollView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import online.monarchlabs.sentinel.services.OTPService;
import online.monarchlabs.sentinel.services.ParentOtpLoginService;
import online.monarchlabs.sentinel.utils.LoadingDialogManager;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.Map;

public class ForgotPasswordActivity extends BaseActivity {
    private static final String TAG = "ForgotPassword";

    // Views
    private ScrollView forgotPasswordScrollView;
    private ImageView btnBack;
    private TextView tvTitle, tvSubtitle;

    // State 1: Email
    private LinearLayout layoutEmailState;
    private TextInputLayout tilEmail;
    private EditText etEmail;
    private Button btnSendOTP;

    // State 2: OTP
    private LinearLayout layoutOtpState;
    private TextView tvOtpSentTo, tvTimer, tvResendOtp;
    private TextInputLayout tilOtp;
    private EditText etOtp;
    private Button btnVerifyOtp;

    // State 3: Password
    private LinearLayout layoutPasswordState;
    private TextInputLayout tilNewPassword, tilConfirmPassword;
    private EditText etNewPassword, etConfirmPassword;
    private Button btnResetPassword;

    private FirebaseAuth mAuth;
    private SessionManager sessionManager;
    private OTPService otpService;
    private ParentOtpLoginService parentOtpLoginService;
    private LoadingDialogManager loadingDialogManager;
    private CountDownTimer countDownTimer;
    private CountDownTimer resendCooldownTimer;

    private String email;
    private String userFirebaseUid;
    private static final long OTP_EXPIRY_TIME_MS = 5 * 60 * 1000L;
    private static final long RESEND_COOLDOWN_SECONDS = 60L;

    // Only EMAIL and OTP states - password reset is done via email link
    private enum State {
        EMAIL, OTP
    }

    private State currentState = State.EMAIL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        initViews();
        mAuth = FirebaseAuth.getInstance();
        sessionManager = new SessionManager(this);
        otpService = new OTPService(this);
        parentOtpLoginService = new ParentOtpLoginService(this);
        loadingDialogManager = new LoadingDialogManager(this);

        setupClickListeners();
        setupKeyboardHandling();
        showState(State.EMAIL);
    }

    private void initViews() {
        forgotPasswordScrollView = findViewById(R.id.forgotPasswordScrollView);
        btnBack = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);

        // Email state
        layoutEmailState = findViewById(R.id.layoutEmailState);
        tilEmail = findViewById(R.id.tilEmail);
        etEmail = findViewById(R.id.etEmail);
        btnSendOTP = findViewById(R.id.btnSendOTP);

        // OTP state
        layoutOtpState = findViewById(R.id.layoutOtpState);
        tvOtpSentTo = findViewById(R.id.tvOtpSentTo);
        tvTimer = findViewById(R.id.tvTimer);
        tvResendOtp = findViewById(R.id.tvResendOtp);
        tilOtp = findViewById(R.id.tilOtp);
        etOtp = findViewById(R.id.etOtp);
        btnVerifyOtp = findViewById(R.id.btnVerifyOtp);

        // Password state
        layoutPasswordState = findViewById(R.id.layoutPasswordState);
        tilNewPassword = findViewById(R.id.tilNewPassword);
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword);
        etNewPassword = findViewById(R.id.etNewPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnResetPassword = findViewById(R.id.btnResetPassword);
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> onBackPressed());
        btnSendOTP.setOnClickListener(v -> sendOTP());
        btnVerifyOtp.setOnClickListener(v -> verifyOTP());
        tvResendOtp.setOnClickListener(v -> resendOTP());
        btnResetPassword.setOnClickListener(v -> resetPassword());

        clearErrorOnChange(etEmail, tilEmail);
        clearErrorOnChange(etOtp, tilOtp);
        clearErrorOnChange(etNewPassword, tilNewPassword);
        clearErrorOnChange(etConfirmPassword, tilConfirmPassword);
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

    private void setupKeyboardHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(forgotPasswordScrollView, (view, insets) -> {
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int bottomPadding = Math.max(imeBottom, systemBottom);
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottomPadding);
            return insets;
        });

        View.OnFocusChangeListener scrollIntoView = (view, hasFocus) -> {
            if (hasFocus) {
                view.post(() -> {
                    if (forgotPasswordScrollView != null) {
                        android.graphics.Rect rect = new android.graphics.Rect();
                        view.getDrawingRect(rect);
                        forgotPasswordScrollView.offsetDescendantRectToMyCoords(view, rect);
                        forgotPasswordScrollView.smoothScrollTo(0, Math.max(0, rect.top - 48));
                    }
                });
            }
        };

        etEmail.setOnFocusChangeListener(scrollIntoView);
        etOtp.setOnFocusChangeListener(scrollIntoView);

        ViewCompat.requestApplyInsets(forgotPasswordScrollView);
    }

    private void showState(State state) {
        currentState = state;

        layoutEmailState.setVisibility(View.GONE);
        layoutOtpState.setVisibility(View.GONE);
        layoutPasswordState.setVisibility(View.GONE); // Keep hidden, not used

        switch (state) {
            case EMAIL:
                layoutEmailState.setVisibility(View.VISIBLE);
                tvTitle.setText("Login With Email Code");
                tvSubtitle.setText("Enter your parent email and we'll send a login code");
                break;

            case OTP:
                layoutOtpState.setVisibility(View.VISIBLE);
                tvTitle.setText("Verify Code");
                tvSubtitle.setText("Enter the code to login");
                tvOtpSentTo.setText("We've sent a 6-digit code to\n" + email);
                startCountdownTimer();
                break;
        }
    }

    private void sendOTP() {
        email = etEmail.getText().toString().trim();

        if (!validateEmail(email)) {
            return;
        }

        loadingDialogManager.show("Sending Code...", "Sending verification code to your email");

        parentOtpLoginService.sendLoginOtp(email)
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        if (result.success) {
                            Toast.makeText(this, "Verification code sent to your email!", Toast.LENGTH_SHORT).show();
                            showState(State.OTP);
                        } else if (isRateLimitResponse(result.message, result.retryAfterSeconds)) {
                            long retryAfterSeconds = effectiveRetryAfterSeconds(result.retryAfterSeconds);
                            startRequestCooldown(retryAfterSeconds);
                            showRateLimitDialog(result.message, retryAfterSeconds);
                        } else {
                            showOtpSendFailureDialog(result.message);
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        Log.e(TAG, "Error sending parent login OTP", throwable);
                        showOtpSendFailureDialog(throwable.getMessage());
                    });
                    return null;
                });
    }



    private void verifyOTP() {
        String otp = etOtp.getText().toString().trim();

        if (!validateOTP(otp)) {
            return;
        }

        loadingDialogManager.show("Verifying Code...", "Please wait");

        parentOtpLoginService.verifyLoginOtp(email, otp)
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        if (!result.success || TextUtils.isEmpty(result.customToken)) {
                            loadingDialogManager.hide();
                            Toast.makeText(this,
                                    !TextUtils.isEmpty(result.message) ? result.message
                                            : "Unable to login with this code. Please try again.",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }

                        if (countDownTimer != null) {
                            countDownTimer.cancel();
                        }

                        loadingDialogManager.updateText("Signing In...", "Opening your parent dashboard");
                        signInWithOtpCustomToken(result.customToken);
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        Log.e(TAG, "Error verifying parent login OTP", throwable);
                        Toast.makeText(this, throwable.getMessage() != null ? throwable.getMessage()
                                : "Failed to verify code. Please try again.", Toast.LENGTH_LONG).show();
                    });
                    return null;
                });
    }

    private void resendOTP() {
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle("🔄 Resend Code")
                .setMessage("Are you sure you want to resend the verification code?\n\n" +
                        "✉️ A new code will be sent to your email\n" +
                        "⏰ The new code will be valid for 5 minutes")
                .setPositiveButton("Yes, Resend", (dialog, which) -> resendBackendLoginOtp())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resendBackendLoginOtp() {
        loadingDialogManager.show("Sending New Code...", "Please wait");
        tvResendOtp.setEnabled(false);
        tvResendOtp.setText("Sending...");

        parentOtpLoginService.sendLoginOtp(email)
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        if (result.success) {
                            Toast.makeText(this, "New code sent to your email!", Toast.LENGTH_SHORT).show();
                            etOtp.setText("");
                            startCountdownTimer();
                        } else {
                            if (isRateLimitResponse(result.message, result.retryAfterSeconds)) {
                                long retryAfterSeconds = effectiveRetryAfterSeconds(result.retryAfterSeconds);
                                startRequestCooldown(retryAfterSeconds);
                                showRateLimitDialog(result.message, retryAfterSeconds);
                            } else {
                                tvResendOtp.setEnabled(true);
                                tvResendOtp.setText("Resend OTP");
                                showOtpSendFailureDialog(result.message);
                            }
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        tvResendOtp.setEnabled(true);
                        tvResendOtp.setText("Resend OTP");
                        Log.e(TAG, "Error resending parent login OTP", throwable);
                        showOtpSendFailureDialog(throwable.getMessage());
                    });
                    return null;
                });
    }

    private void performResendOTP() {
        loadingDialogManager.show("Sending New Code...", "Please wait");
        tvResendOtp.setEnabled(false);
        tvResendOtp.setText("Sending...");

        otpService.resendOTP(email, "parent")
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        if (result.isSuccess()) {
                            Toast.makeText(this, "🔄 New code sent to your email!", Toast.LENGTH_SHORT).show();
                            etOtp.setText("");
                            startCountdownTimer();
                        } else {
                            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                            tvResendOtp.setEnabled(true);
                            tvResendOtp.setText("Resend OTP");
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        tvResendOtp.setEnabled(true);
                        tvResendOtp.setText("Resend OTP");
                        Toast.makeText(this, "Failed to resend code. Please try again.", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Error resending OTP", throwable);
                    });
                    return null;
                });
    }

    private void signInWithOtpCustomToken(String customToken) {
        mAuth.signInWithCustomToken(customToken)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            checkDeviceSession(user.getUid());
                        } else {
                            loadingDialogManager.hide();
                            Toast.makeText(this, "Login failed. Please try again.", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        loadingDialogManager.hide();
                        Log.e(TAG, "OTP custom-token sign-in failed", task.getException());
                        Toast.makeText(this, task.getException() != null
                                ? task.getException().getMessage()
                                : "Login failed. Please try again.", Toast.LENGTH_LONG).show();
                    }
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
                long STALE_THRESHOLD = 90 * 1000;
                int MAX_DEVICES = 3;

                java.util.List<String> liveDeviceIds = new java.util.ArrayList<>();
                java.util.Map<String, Long> deviceHeartbeats = new java.util.HashMap<>();

                for (DataSnapshot child : snapshot.getChildren()) {
                    String deviceId = child.getKey();
                    Long lastHeartbeat = child.child("lastHeartbeat").getValue(Long.class);

                    if (deviceId == null) continue;
                    if (deviceId.equals(currentDeviceId)) continue;

                    boolean isAlive = lastHeartbeat != null && (currentTime - lastHeartbeat) < STALE_THRESHOLD;

                    if (!isAlive) {
                        Log.d(TAG, "🔓 Removing stale device session: " + deviceId);
                        activeDevicesRef.child(deviceId).removeValue();
                    } else {
                        liveDeviceIds.add(deviceId);
                        deviceHeartbeats.put(deviceId, lastHeartbeat);
                    }
                }

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

                Log.d(TAG, "✅ Multi-device session check passed — proceeding with login");
                loadParentDataAndProceed(uid);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                loadingDialogManager.hide();
                Log.e(TAG, "Device session check failed: " + error.getMessage());
                Toast.makeText(ForgotPasswordActivity.this,
                        "Error checking device session", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadParentDataAndProceed(String uid) {
        DatabaseReference profileRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_profiles")
                .child(uid);

        profileRef.get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) {
                        loadingDialogManager.hide();
                        Toast.makeText(this,
                                "Parent profile not found.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String name = snapshot.child("displayName")
                            .getValue(String.class);
                    String phone = snapshot.child("phone")
                            .getValue(String.class);
                    String profileEmail = snapshot.child("email")
                            .getValue(String.class);

                    sessionManager.saveParentSession(
                            phone, uid, ParentUtils.getParentDeviceName(),
                            TextUtils.isEmpty(name) ? "Parent" : name);
                    ensurePhoneLoginIndex(uid, profileEmail, phone);
                    saveActiveDeviceToFirebase(uid);

                    loadingDialogManager.hide();
                    Intent intent = new Intent(
                            this, ParentDashboardActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(error -> {
                    loadingDialogManager.hide();
                    Log.e(TAG, "V2 profile read failed", error);
                    Toast.makeText(this,
                            "Could not load the parent profile.",
                            Toast.LENGTH_LONG).show();
                });
    }

    /**
     * Save active device information to Firebase.
     * Writes to activeDevices/{deviceId} for multi-device support.
     */
    private void saveActiveDeviceToFirebase(String uid) {
        String deviceId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        Map<String, Object> data = new HashMap<>();
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

    private String normalizePhoneForLookup(String phone) {
        if (phone == null) {
            return "";
        }
        return phone.replaceAll("[^0-9]", "");
    }

    private void showDeviceBlockedDialog(String deviceModel) {
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle("Already Logged In")
                .setMessage("This parent account is still active on " + deviceModel
                        + ". Please logout there first, or wait for the old session to expire.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showOtpSendFailureDialog(String message) {
        String displayMessage = message != null ? message : "The OTP login service is not available right now.";
        if (displayMessage.toLowerCase().contains("no parent account found")) {
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                    .setTitle("Account Not Found")
                    .setMessage(displayMessage + "\n\nPlease check the email address or create a parent account.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle("Could Not Send Code")
                .setMessage(displayMessage + "\n\nYou can still send a password reset link to your email.")
                .setPositiveButton("Send Reset Link", (dialog, which) -> {
                    loadingDialogManager.show("Sending Reset Link...", "Preparing secure password reset");
                    sendPasswordResetEmail();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean isRateLimitResponse(String message, long retryAfterSeconds) {
        if (retryAfterSeconds > 0) {
            return true;
        }
        String normalized = message == null ? "" : message.toLowerCase();
        return normalized.contains("please wait")
                || normalized.contains("too many otp")
                || normalized.contains("too many code");
    }

    private long effectiveRetryAfterSeconds(long retryAfterSeconds) {
        return retryAfterSeconds > 0 ? retryAfterSeconds : 60L;
    }

    private void showRateLimitDialog(String message, long retryAfterSeconds) {
        String detail = !TextUtils.isEmpty(message)
                ? message
                : "Please wait before requesting another code.";
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle("Please Wait")
                .setMessage(detail + "\n\nYou can request another code in about "
                        + retryAfterSeconds + " seconds.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void resetPassword() {
        String newPassword = etNewPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        if (!validatePasswords(newPassword, confirmPassword)) {
            return;
        }

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle("Reset Password")
                .setMessage(
                        "Since you've verified your identity with OTP, we'll now send you a secure password reset link.\n\n"
                                +
                                "This is the most secure way to reset your password.\n\n" +
                                "Click the link in your email to set your new password.")
                .setPositiveButton("Send Reset Link", (dialog, which) -> {
                    sendPasswordResetEmail();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendPasswordResetEmail() {
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    loadingDialogManager.hide();
                    if (task.isSuccessful()) {
                        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                                .setTitle("✅ Password Reset Link Sent!")
                                .setMessage("We've sent a password reset link to:\n\n📧 " + email + "\n\n" +
                                        "Please check your inbox for the password reset email.\n\n" +
                                        "⚠️ If you don't see it in your primary inbox, please check your SPAM/JUNK folder.\n\n"
                                        +
                                        "🔗 Click the link in the email to set your new password\n" +
                                        "⏰ Link expires in 1 hour")
                                .setPositiveButton("OK, Got it!", (dialog, which) -> {
                                    Intent intent = new Intent(this, ParentEmailLoginActivity.class);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    startActivity(intent);
                                    finish();
                                })
                                .setCancelable(false)
                                .show();
                    } else {
                        String errorMsg = "Failed to send reset link";
                        if (task.getException() != null) {
                            errorMsg += ": " + task.getException().getMessage();
                        }
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private boolean validateEmail(String email) {
        tilEmail.setError(null);
        if (TextUtils.isEmpty(email)) {
            tilEmail.setError("Email is required");
            etEmail.requestFocus();
            return false;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Enter a valid email");
            etEmail.requestFocus();
            return false;
        }

        return true;
    }

    private boolean validateOTP(String otp) {
        tilOtp.setError(null);
        if (TextUtils.isEmpty(otp)) {
            tilOtp.setError("Enter OTP");
            etOtp.requestFocus();
            return false;
        }

        if (otp.length() != 6) {
            tilOtp.setError("Enter valid 6-digit OTP");
            etOtp.requestFocus();
            return false;
        }

        return true;
    }

    private boolean validatePasswords(String newPassword, String confirmPassword) {
        tilNewPassword.setError(null);
        tilConfirmPassword.setError(null);
        if (TextUtils.isEmpty(newPassword)) {
            tilNewPassword.setError("Password is required");
            etNewPassword.requestFocus();
            return false;
        }

        if (newPassword.length() < 6) {
            tilNewPassword.setError("Password must be at least 6 characters");
            etNewPassword.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(confirmPassword)) {
            tilConfirmPassword.setError("Confirm your password");
            etConfirmPassword.requestFocus();
            return false;
        }

        if (!newPassword.equals(confirmPassword)) {
            tilConfirmPassword.setError("Passwords do not match");
            etConfirmPassword.requestFocus();
            return false;
        }

        return true;
    }

    private void startCountdownTimer() {
        if (resendCooldownTimer != null) {
            resendCooldownTimer.cancel();
            resendCooldownTimer = null;
        }

        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        tvResendOtp.setEnabled(false);
        tvResendOtp.setText("Wait to resend");

        countDownTimer = new CountDownTimer(OTP_EXPIRY_TIME_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long minutes = millisUntilFinished / 60000;
                long seconds = (millisUntilFinished % 60000) / 1000;

                tvTimer.setText(String.format("Code expires in %d:%02d", minutes, seconds));
            }

            @Override
            public void onFinish() {
                tvTimer.setText("⏰ Code has expired");
                tvResendOtp.setEnabled(true);
                tvResendOtp.setText("Resend OTP");
            }
        }.start();

        startRequestCooldown(RESEND_COOLDOWN_SECONDS);
    }

    private void startRequestCooldown(long retryAfterSeconds) {
        if (resendCooldownTimer != null) {
            resendCooldownTimer.cancel();
        }

        boolean emailScreen = currentState == State.EMAIL;
        if (emailScreen) {
            btnSendOTP.setEnabled(false);
        } else {
            tvResendOtp.setEnabled(false);
        }
        resendCooldownTimer = new CountDownTimer(Math.max(1L, retryAfterSeconds) * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                long seconds = Math.max(1L, (millisUntilFinished + 999L) / 1000L);
                if (emailScreen) {
                    btnSendOTP.setText("Try again in " + seconds + "s");
                } else {
                    tvResendOtp.setText("Try again in " + seconds + "s");
                }
            }

            @Override
            public void onFinish() {
                if (emailScreen) {
                    btnSendOTP.setEnabled(true);
                    btnSendOTP.setText("Send Login Code");
                } else {
                    tvResendOtp.setEnabled(true);
                    tvResendOtp.setText("Resend OTP");
                }
            }
        }.start();
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        if (resendCooldownTimer != null) {
            resendCooldownTimer.cancel();
        }
        if (loadingDialogManager != null) {
            loadingDialogManager.cleanup();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (loadingDialogManager != null && loadingDialogManager.isShowing()) {
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    interface EmailExistsCallback {
        void onResult(boolean exists);
    }
}
