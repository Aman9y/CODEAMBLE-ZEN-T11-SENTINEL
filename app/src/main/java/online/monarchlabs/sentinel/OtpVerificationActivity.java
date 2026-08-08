package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.widget.Toast;
import android.util.Log;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import online.monarchlabs.sentinel.databinding.ActivityOtpVerificationBinding;
import online.monarchlabs.sentinel.services.OTPService;
import online.monarchlabs.sentinel.SessionManager;
import online.monarchlabs.sentinel.utils.LoadingDialogManager;
import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import java.util.HashMap;
import java.util.Map;

public class OtpVerificationActivity extends BaseActivity {
    private static final String TAG = "OtpVerification";
    private ActivityOtpVerificationBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private SessionManager sessionManager;
    private OTPService otpService;
    private CountDownTimer countDownTimer;
    private CountDownTimer resendCooldownTimer;
    private LoadingDialogManager loadingDialogManager;

    private String username, email, phone, userType;
    private static final long OTP_EXPIRY_TIME_MS = 5 * 60 * 1000L;
    private static final long RESEND_COOLDOWN_SECONDS = 60L;

    // 🆕 Signup flow fields
    private boolean isSignupFlow = false;
    private String signupName;
    private String signupPhone;
    private String signupPassword;
    private String termsVersion;
    private String privacyVersion;
    private long legalAcceptedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        binding = ActivityOtpVerificationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // Initialize services
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        sessionManager = new SessionManager(this);
        otpService = new OTPService(this);
        loadingDialogManager = new LoadingDialogManager(this);

        // Get data from intent
        email = getIntent().getStringExtra("email");
        username = getIntent().getStringExtra("username");
        phone = getIntent().getStringExtra("phone");
        userType = getIntent().getStringExtra("userType");

        if (email != null && !email.isEmpty()) {
            binding.tvOtpMessage.setText("Enter the 6-digit OTP sent to your email\n" + email);
        }

        // 🆕 Check if this is signup flow
        isSignupFlow = getIntent().getBooleanExtra("isSignup", false);
        if (isSignupFlow) {
            signupName = getIntent().getStringExtra("signupName");
            signupPhone = getIntent().getStringExtra("signupPhone");
            signupPassword = getIntent().getStringExtra("signupPassword");
            termsVersion = getIntent().getStringExtra("termsVersion");
            privacyVersion = getIntent().getStringExtra("privacyVersion");
            legalAcceptedAt = getIntent().getLongExtra("legalAcceptedAt", 0L);
            if (TextUtils.isEmpty(termsVersion) || TextUtils.isEmpty(privacyVersion) || legalAcceptedAt <= 0L) {
                Toast.makeText(this, "Please review and accept the Terms and Privacy Policy again.",
                        Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            userType = "parent"; // Signup is always for parent
            username = signupName; // Use signup name as username
            phone = signupPhone; // Use signup phone
            Log.d(TAG, "🆕 Signup flow detected for email: " + email);
        }

        // Set up click listeners
        binding.btnVerifyOtp.setOnClickListener(v -> verifyOTP());
        binding.tvResendOtp.setOnClickListener(v -> resendOTP());
        binding.btnBack.setOnClickListener(v -> onBackPressed());

        // Start countdown timer
        startCountdownTimer();

        // Setup keyboard handling
        setupKeyboardHandling();
    }

    private void verifyOTP() {
        String otp = binding.etOtp.getText().toString().trim();

        if (!validateOTP(otp)) {
            return;
        }

        // Show loading dialog - this prevents user from going back or interacting with
        // UI
        loadingDialogManager.show("Verifying OTP...", "Please wait while we validate your code");
        setLoadingState(true);

        // Verify OTP using Appwrite
        otpService.verifyOTP(email, otp)
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        if (result.isSuccess()) {
                            Log.d(TAG, "✅ OTP verified successfully");

                            // Update loading dialog to show authentication progress
                            loadingDialogManager.updateText("OTP Verified!", "Signing you in to your account...");

                            // Stop the timer
                            if (countDownTimer != null) {
                                countDownTimer.cancel();
                            }

                            // 🆕 Check if signup or login flow
                            if (isSignupFlow) {
                                // Signup flow: Create Firebase account with email/password
                                completeSignup();
                            } else {
                                // Login flow: Sign in anonymously and save user data
                                signInAndSaveUserData();
                            }
                        } else {
                            // Hide loading dialog and show error
                            loadingDialogManager.hide();
                            setLoadingState(false);
                            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        setLoadingState(false);
                        Toast.makeText(this, "Failed to verify OTP. Please try again.", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Error verifying OTP", throwable);
                    });
                    return null;
                });
    }

    private boolean validateOTP(String otp) {
        if (TextUtils.isEmpty(otp)) {
            binding.etOtp.setError("Enter OTP");
            binding.etOtp.requestFocus();
            return false;
        }

        if (otp.length() != 6) {
            binding.etOtp.setError("Enter valid 6-digit OTP");
            binding.etOtp.requestFocus();
            return false;
        }

        return true;
    }

    private void resendOTP() {
        // Show confirmation dialog before resending OTP
        new AlertDialog.Builder(this)
                .setTitle("🔄 Resend OTP")
                .setMessage("Are you sure you want to resend the OTP?\n\n" +
                        "✉️ A new verification code will be sent to your email\n" +
                        "⏰ The new code will be valid for 5 minutes\n" +
                        "📝 Current input will be cleared")
                .setPositiveButton("Yes, Resend", (dialog, which) -> {
                    // User confirmed - proceed with resending OTP
                    performResendOTP();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    // User cancelled - do nothing
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    private void performResendOTP() {
        // Show loading dialog for resend
        loadingDialogManager.show("Sending New OTP...", "Please wait while we send a new code to your email");
        setLoadingState(true);

        // Disable resend button temporarily
        binding.tvResendOtp.setEnabled(false);
        binding.tvResendOtp.setText("Sending...");

        otpService.resendOTP(email, userType)
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        setLoadingState(false);

                        if (result.isSuccess()) {
                            Toast.makeText(this, "🔄 New OTP sent to your email!\n⏰ Code valid for 5 minutes.",
                                    Toast.LENGTH_LONG).show();

                            // Add a brief visual feedback before restarting timer
                            binding.tvTimer.setText("🔄 Timer resetting...");

                            // Restart expiry and resend timers for the newly issued code.
                            new android.os.Handler().postDelayed(() -> {
                                startCountdownTimer();
                            }, 800); // Brief delay to show reset message

                            // Clear any existing OTP input
                            binding.etOtp.setText("");
                            binding.etOtp.requestFocus();
                        } else {
                            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                            if (result.getRetryAfterSeconds() > 0) {
                                startResendCooldown(result.getRetryAfterSeconds());
                            } else {
                                binding.tvResendOtp.setEnabled(true);
                                binding.tvResendOtp.setText("Resend OTP");
                            }
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        setLoadingState(false);
                        binding.tvResendOtp.setEnabled(true);
                        binding.tvResendOtp.setText("Resend OTP");
                        Toast.makeText(this, "Failed to resend OTP. Please try again.", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Error resending OTP", throwable);
                    });
                    return null;
                });
    }

    private void signInAndSaveUserData() {
        // Update loading dialog for authentication step
        loadingDialogManager.updateText("Authenticating...", "Creating your secure session...");

        // Sign in anonymously to get Firebase UID for compatibility
        mAuth.signInAnonymously()
                .addOnSuccessListener(authResult -> {
                    Log.d(TAG, "✅ Anonymous sign-in successful");
                    // Update loading dialog for data saving step
                    loadingDialogManager.updateText("Almost Done!", "Saving your account information...");
                    saveUserData();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Anonymous sign-in failed: " + e.getMessage());
                    // Still try to save user data without Firebase auth
                    loadingDialogManager.updateText("Finalizing Setup...", "Completing your account setup...");
                    saveUserDataWithoutAuth();
                });
    }

    /**
     * 🆕 Complete signup by creating Firebase account with email/password
     */
    private void completeSignup() {
        loadingDialogManager.updateText("Creating Account...", "Setting up your secure account...");

        mAuth.createUserWithEmailAndPassword(email, signupPassword)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "✅ Firebase account created successfully");
                        com.google.firebase.auth.FirebaseUser firebaseUser = mAuth.getCurrentUser();
                        if (firebaseUser != null) {
                            claimPhoneNumber(firebaseUser, signupName, email, signupPhone);
                        }
                    } else {
                        loadingDialogManager.hide();
                        setLoadingState(false);
                        Log.e(TAG, "❌ Signup failed", task.getException());

                        if (task.getException() != null) {
                            String msg = task.getException().getMessage();
                            if (msg != null && msg.contains("email address is already in use")) {
                                // Show dialog with option to login
                                new AlertDialog.Builder(this)
                                        .setTitle("Email Already Registered")
                                        .setMessage(
                                                "This email is already registered. Would you like to login instead?")
                                        .setPositiveButton("Go to Login", (dialog, which) -> {
                                            startActivity(new Intent(this, ParentEmailLoginActivity.class));
                                            finish();
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            } else {
                                Toast.makeText(this, msg != null ? msg : "Signup failed", Toast.LENGTH_LONG).show();
                            }
                        } else {
                            Toast.makeText(this, "Signup failed", Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * 🆕 Save parent profile after signup
     */
    private void saveParentProfile(String uid, String name,
            String email, String phone) {
        String deviceId = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        long createdAt = System.currentTimeMillis();

        FirebaseSchemaV2Repository.syncParentIdentity(
                        uid, name, email, phone, deviceId, createdAt)
                .addOnSuccessListener(ignored -> {
                    saveLegalAcceptance(uid);
                    savePhoneLoginIndex(uid, email, phone);
                    sessionManager.saveParentSession(
                            phone, uid, ParentUtils.getParentDeviceName(),
                            name);
                    loadingDialogManager.hide();
                    Toast.makeText(this,
                            "Welcome, " + name + "!",
                            Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(
                            this, ParentDashboardActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(error -> {
                    loadingDialogManager.hide();
                    setLoadingState(false);
                    Log.e(TAG, "V2 parent profile write failed", error);
                    Toast.makeText(this,
                            "Failed to create the parent profile.",
                            Toast.LENGTH_LONG).show();
                });
    }

    private void saveLegalAcceptance(String uid) {
        Map<String, Object> acceptance = new HashMap<>();
        acceptance.put("uid", uid);
        acceptance.put("termsVersion", termsVersion);
        acceptance.put("privacyVersion", privacyVersion);
        acceptance.put("acceptedAt", legalAcceptedAt);
        acceptance.put("recordedAt",
                com.google.firebase.database.ServerValue.TIMESTAMP);
        acceptance.put("source", "account_signup");

        FirebaseDatabase.getInstance().getReference("v2")
                .child("legal_acceptances").child(uid)
                .child("account_creation").setValue(acceptance)
                .addOnFailureListener(error ->
                        Log.w(TAG, "Legal acceptance write failed", error));
    }

    private void claimPhoneNumber(
            com.google.firebase.auth.FirebaseUser firebaseUser,
            String name, String email, String phone) {
        saveParentProfile(firebaseUser.getUid(), name, email, phone);
    }

    private java.util.concurrent.CompletableFuture<Boolean> savePhoneLoginIndex(String uid, String email, String phone) {
        return new online.monarchlabs.sentinel.services.ParentDirectoryService(this)
                .registerProfileIndex(uid, email, phone);
    }

    private void saveUserData() {
        com.google.firebase.auth.FirebaseUser user = mAuth.getCurrentUser();
        if (user == null || user.isAnonymous()) {
            saveUserDataWithoutAuth();
            return;
        }
        String deviceId = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        long createdAt = System.currentTimeMillis();
        FirebaseSchemaV2Repository.syncParentIdentity(
                        user.getUid(), username, email, phone,
                        deviceId, createdAt)
                .addOnSuccessListener(ignored -> {
                    savePhoneLoginIndex(user.getUid(), email, phone).thenAccept(success -> {
                        runOnUiThread(() -> {
                            sessionManager.saveParentSession(
                                    phone, user.getUid(),
                                    ParentUtils.getParentDeviceName(),
                                    username != null ? username : "Parent");
                            if (!success) {
                                Toast.makeText(OtpVerificationActivity.this, 
                                    "Signup successful, but phone number is already claimed. Please login with email.", 
                                    Toast.LENGTH_LONG).show();
                            }
                            proceedToMainActivity();
                        });
                    });
                })
                .addOnFailureListener(error -> {
                    loadingDialogManager.hide();
                    Log.e(TAG, "V2 parent profile write failed", error);
                    Toast.makeText(this,
                            "Could not save the parent profile.",
                            Toast.LENGTH_LONG).show();
                });
    }

    private void saveUserDataWithoutAuth() {
        loadingDialogManager.hide();
        setLoadingState(false);
        mAuth.signOut();
        Toast.makeText(this,
                "A verified parent login is required. Please sign in again.",
                Toast.LENGTH_LONG).show();
        Intent intent = new Intent(this, ParentEmailLoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoadingState(boolean isLoading) {
        binding.btnVerifyOtp.setEnabled(!isLoading);
        binding.btnVerifyOtp.setText(isLoading ? "Verifying..." : "Verify OTP");
        binding.etOtp.setEnabled(!isLoading);
    }

    private void startCountdownTimer() {
        if (resendCooldownTimer != null) {
            resendCooldownTimer.cancel();
            resendCooldownTimer = null;
        }

        // Cancel existing timer
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        // Disable resend button and update text
        binding.tvResendOtp.setEnabled(false);
        binding.tvResendOtp.setText("Wait to resend");

        countDownTimer = new CountDownTimer(OTP_EXPIRY_TIME_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long minutes = millisUntilFinished / 60000;
                long seconds = (millisUntilFinished % 60000) / 1000;

                String timeLeft = String.format("Code expires in %d:%02d", minutes, seconds);
                binding.tvTimer.setText(timeLeft);

            }

            @Override
            public void onFinish() {
                binding.tvTimer.setText("⏰ Code has expired");
                binding.tvResendOtp.setEnabled(true);
                binding.tvResendOtp.setText("Resend OTP");
            }
        }.start();

        startResendCooldown(RESEND_COOLDOWN_SECONDS);
    }

    private void startResendCooldown(long retryAfterSeconds) {
        if (resendCooldownTimer != null) {
            resendCooldownTimer.cancel();
        }

        binding.tvResendOtp.setEnabled(false);
        resendCooldownTimer = new CountDownTimer(Math.max(1L, retryAfterSeconds) * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                long seconds = Math.max(1L, (millisUntilFinished + 999L) / 1000L);
                binding.tvResendOtp.setText("Try again in " + seconds + "s");
            }

            @Override
            public void onFinish() {
                binding.tvResendOtp.setEnabled(true);
                binding.tvResendOtp.setText("Resend OTP");
            }
        }.start();
    }

    private void proceedWithLogin() {
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid()
                : "user_" + System.currentTimeMillis();

        // Save session based on user type
        if ("parent".equals(userType)) {
            String deviceName = ParentUtils.getParentDeviceName();
            sessionManager.saveParentSession(phone, userId, deviceName, username != null ? username : "Parent");
        } else if ("child".equals(userType)) {
            // For child type - this shouldn't normally happen in OTP verification
            // But we'll handle it gracefully
            sessionManager.saveChildSession(userId, username, "");
        }
        proceedToMainActivity();
    }

    private void proceedToMainActivity() {
        // Update loading dialog for final step
        loadingDialogManager.updateText("Welcome!", "Taking you to your dashboard...");

        // Add a brief delay to show success message before redirecting
        new android.os.Handler().postDelayed(() -> {
            // Hide loading dialog
            loadingDialogManager.hide();

            // Show welcome toast
            Toast.makeText(this, "✅ Login successful! Welcome " + username, Toast.LENGTH_SHORT).show();

            Intent intent;
            if ("parent".equals(userType)) {
                intent = new Intent(this, ParentDashboardActivity.class);
            } else {
                intent = new Intent(this, ChildDashboardActivity.class);
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }, 1500); // 1.5 second delay to show success message
    }

    @Override
    public void onBackPressed() {
        // Prevent going back when loading dialog is showing
        if (loadingDialogManager != null && loadingDialogManager.isShowing()) {
            Toast.makeText(this, "Please wait while we complete the authentication process", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    private void setupKeyboardHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.otpScrollView, (view, insets) -> {
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int bottomPadding = Math.max(imeBottom, systemBottom);
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottomPadding);
            return insets;
        });

        View.OnFocusChangeListener scrollIntoView = (view, hasFocus) -> {
            if (hasFocus) {
                view.post(() -> {
                    if (binding.otpScrollView != null) {
                        binding.otpScrollView.smoothScrollTo(0, Math.max(0, view.getBottom() - 64));
                    }
                });
            }
        };

        binding.etOtp.setOnFocusChangeListener(scrollIntoView);

        ViewCompat.requestApplyInsets(binding.otpScrollView);
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        if (resendCooldownTimer != null) {
            resendCooldownTimer.cancel();
        }

        // Clean up the loading dialog manager to prevent memory leaks
        if (loadingDialogManager != null) {
            loadingDialogManager.cleanup();
        }

        super.onDestroy();
        binding = null;
    }
}
