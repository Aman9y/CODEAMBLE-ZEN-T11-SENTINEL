package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import online.monarchlabs.sentinel.services.OTPService;
import online.monarchlabs.sentinel.utils.InfoContentRepository;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputLayout;

/** Second step of parent signup: create a password and start email verification. */
public class ParentSignupPasswordActivity extends BaseActivity {
    private static final String TAG = "ParentSignupPassword";

    private EditText etPassword, etConfirmPassword;
    private TextInputLayout tilPassword, tilConfirmPassword;
    private Button btnCreateAccount;
    private MaterialCheckBox cbLegalAgreement;
    private TextView tvLegalError;
    private String name, email, phone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_signup_password);

        name = getIntent().getStringExtra("signupName");
        email = getIntent().getStringExtra("email");
        phone = getIntent().getStringExtra("signupPhone");
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(email) || TextUtils.isEmpty(phone)) {
            Toast.makeText(this, "Signup details are missing. Please try again.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        tilPassword = findViewById(R.id.tilPassword);
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword);
        btnCreateAccount = findViewById(R.id.btnCreateAccount);
        cbLegalAgreement = findViewById(R.id.cbLegalAgreement);
        tvLegalError = findViewById(R.id.tvLegalError);
        TextView tvSignupEmail = findViewById(R.id.tvSignupEmail);
        tvSignupEmail.setText(email);

        setupLegalAgreementText();

        findViewById(R.id.btnBackToDetails).setOnClickListener(v -> finish());
        btnCreateAccount.setOnClickListener(v -> validateAndSendOtp());
        clearErrorOnChange(etPassword, tilPassword);
        clearErrorOnChange(etConfirmPassword, tilConfirmPassword);
        cbLegalAgreement.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) tvLegalError.setVisibility(View.GONE);
        });
    }

    private void setupLegalAgreementText() {
        String text = "I agree to the Terms of Service and acknowledge the Privacy Policy.";
        SpannableString spannable = new SpannableString(text);
        addLegalLink(spannable, text, "Terms of Service", InfoContentRepository.KEY_TERMS);
        addLegalLink(spannable, text, "Privacy Policy", InfoContentRepository.KEY_PRIVACY);
        cbLegalAgreement.setText(spannable);
        cbLegalAgreement.setMovementMethod(LinkMovementMethod.getInstance());
        cbLegalAgreement.setHighlightColor(android.graphics.Color.TRANSPARENT);
    }

    private void addLegalLink(SpannableString spannable, String fullText, String label, String contentKey) {
        int start = fullText.indexOf(label);
        if (start < 0) return;
        spannable.setSpan(new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                Intent intent = new Intent(ParentSignupPasswordActivity.this, InfoDetailActivity.class);
                intent.putExtra(InfoDetailActivity.EXTRA_CONTENT_KEY, contentKey);
                startActivity(intent);
            }
        }, start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    private void validateAndSendOtp() {
        tilPassword.setError(null);
        tilConfirmPassword.setError(null);

        String password = etPassword.getText().toString();
        String confirmPassword = etConfirmPassword.getText().toString();
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
        if (!password.equals(confirmPassword)) {
            tilConfirmPassword.setError("Passwords do not match");
            etConfirmPassword.requestFocus();
            return;
        }
        if (!cbLegalAgreement.isChecked()) {
            tvLegalError.setVisibility(View.VISIBLE);
            cbLegalAgreement.requestFocus();
            return;
        }

        setLoading(true);
        new OTPService(this).sendOTP(email, "parent")
                .thenAccept(result -> runOnUiThread(() -> {
                    setLoading(false);
                    if (!result.isSuccess()) {
                        Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }

                    Toast.makeText(this, "OTP sent to " + email, Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(this, OtpVerificationActivity.class);
                    intent.putExtra("email", email);
                    intent.putExtra("isSignup", true);
                    intent.putExtra("signupName", name);
                    intent.putExtra("signupPhone", phone);
                    intent.putExtra("signupPassword", password);
                    intent.putExtra("termsVersion", LegalPolicyVersions.TERMS);
                    intent.putExtra("privacyVersion", LegalPolicyVersions.PRIVACY);
                    intent.putExtra("legalAcceptedAt", System.currentTimeMillis());
                    startActivity(intent);
                    finish();
                }))
                .exceptionally(error -> {
                    runOnUiThread(() -> {
                        setLoading(false);
                        Log.e(TAG, "Failed to send signup OTP", error);
                        Toast.makeText(this, "Could not send OTP. Please try again.", Toast.LENGTH_LONG).show();
                    });
                    return null;
                });
    }

    private void setLoading(boolean loading) {
        btnCreateAccount.setEnabled(!loading);
        btnCreateAccount.setText(loading ? "Sending Code..." : "Create Account");
        etPassword.setEnabled(!loading);
        etConfirmPassword.setEnabled(!loading);
        cbLegalAgreement.setEnabled(!loading);
    }
}
