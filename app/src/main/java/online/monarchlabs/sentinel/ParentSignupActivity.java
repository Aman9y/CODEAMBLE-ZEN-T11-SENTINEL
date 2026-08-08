package online.monarchlabs.sentinel;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.ScrollView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import online.monarchlabs.sentinel.utils.PhoneUtils;



/** First step of parent signup: collect contact details. */
public class ParentSignupActivity extends BaseActivity {
    private static final String TAG = "ParentSignup";

    private ScrollView signupScrollView;
    private EditText etName, etEmail, etPhone;
    private TextInputLayout tilName, tilEmail, tilPhone;
    private Button btnSignup;
    private TextView tvLoginLink;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_signup);

        mAuth = FirebaseAuth.getInstance();
        initViews();
        setupListeners();
        setupKeyboardHandling();
    }

    private void initViews() {
        signupScrollView = findViewById(R.id.signupScrollView);
        etName = findViewById(R.id.etName);
        etEmail = findViewById(R.id.etEmail);
        etPhone = findViewById(R.id.etPhone);
        tilName = findViewById(R.id.tilName);
        tilEmail = findViewById(R.id.tilEmail);
        tilPhone = findViewById(R.id.tilPhone);
        btnSignup = findViewById(R.id.btnSignup);
        tvLoginLink = findViewById(R.id.tvLoginLink);
        setupLoginLink();
    }

    private void setupLoginLink() {
        String text = "Already have an account? Login";
        SpannableString spannableString = new SpannableString(text);
        int startIndex = text.indexOf("Login");
        if (startIndex != -1) {
            spannableString.setSpan(
                new ForegroundColorSpan(Color.parseColor("#2563EB")),
                startIndex,
                startIndex + "Login".length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            spannableString.setSpan(
                new StyleSpan(Typeface.BOLD),
                startIndex,
                startIndex + "Login".length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        tvLoginLink.setText(spannableString);
    }

    private void setupListeners() {
        btnSignup.setOnClickListener(v -> continueToPassword());
        tvLoginLink.setOnClickListener(v -> {
            startActivity(new Intent(this, ParentEmailLoginActivity.class));
            finish();
        });

        clearErrorOnChange(etName, tilName);
        clearErrorOnChange(etEmail, tilEmail);
        clearErrorOnChange(etPhone, tilPhone);
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

    private void continueToPassword() {
        tilName.setError(null);
        tilEmail.setError(null);
        tilPhone.setError(null);

        String name = etName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            tilName.setError("Name is required");
            etName.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(email)
                || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Please enter a valid email");
            etEmail.requestFocus();
            return;
        }
        if (!PhoneUtils.isValid(phone)) {
            tilPhone.setError("Enter a valid phone number");
            etPhone.requestFocus();
            return;
        }

        setLoading(true);
        checkEmailAvailability(name, email, phone);
    }

    private void checkEmailAvailability(String name, String email, String phone) {
        mAuth.fetchSignInMethodsForEmail(email).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                boolean emailExists = task.getResult() != null
                        && task.getResult().getSignInMethods() != null
                        && !task.getResult().getSignInMethods().isEmpty();
                if (emailExists) {
                    showEmailAlreadyRegistered(email);
                    setLoading(false);
                    return;
                }
            } else {
                Log.w(TAG, "Email availability check failed; continuing to phone check", task.getException());
            }

            checkPhoneAvailability(name, email, phone);
        });
    }

    private void checkPhoneAvailability(String name, String email, String phone) {
        String normalizedPhone = PhoneUtils.normalize(phone);
        DatabaseReference phoneRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("directory")
                .child("phone_to_email")
                .child(normalizedPhone);
                
        phoneRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    showPhoneAlreadyRegistered();
                    setLoading(false);
                } else {
                    setLoading(false);
                    Intent intent = new Intent(ParentSignupActivity.this, ParentSignupPasswordActivity.class);
                    intent.putExtra("signupName", name);
                    intent.putExtra("email", email);
                    intent.putExtra("signupPhone", phone);
                    startActivity(intent);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Phone availability check failed", error.toException());
                // In case of network failure, allow them to proceed.
                // The transactional write in registerProfileIndex will catch it anyway.
                setLoading(false);
                Intent intent = new Intent(ParentSignupActivity.this, ParentSignupPasswordActivity.class);
                intent.putExtra("signupName", name);
                intent.putExtra("email", email);
                intent.putExtra("signupPhone", phone);
                startActivity(intent);
            }
        });
    }

    private void showPhoneAlreadyRegistered() {
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle("Phone Number Registered")
                .setMessage("This phone number is already registered. Would you like to login instead?")
                .setPositiveButton("Go to Login", (dialog, which) -> {
                    Intent intent = new Intent(this, ParentEmailLoginActivity.class);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEmailAlreadyRegistered(String email) {
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle("Email Already Registered")
                .setMessage("This email is already registered. Would you like to login instead?")
                .setPositiveButton("Go to Login", (dialog, which) -> {
                    Intent intent = new Intent(this, ParentEmailLoginActivity.class);
                    intent.putExtra("email", email);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setLoading(boolean loading) {
        btnSignup.setEnabled(!loading);
        btnSignup.setText(loading ? "Checking Email..." : "Next");
        etName.setEnabled(!loading);
        etEmail.setEnabled(!loading);
        etPhone.setEnabled(!loading);
    }

    private void setupKeyboardHandling() {
        ViewCompat.setOnApplyWindowInsetsListener(signupScrollView, (view, insets) -> {
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int bottomPadding = Math.max(imeBottom, systemBottom);
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), bottomPadding);
            return insets;
        });

        View.OnFocusChangeListener scrollIntoView = (view, hasFocus) -> {
            if (hasFocus) {
                view.post(() -> {
                    if (signupScrollView != null) {
                        android.graphics.Rect rect = new android.graphics.Rect();
                        view.getDrawingRect(rect);
                        signupScrollView.offsetDescendantRectToMyCoords(view, rect);
                        signupScrollView.smoothScrollTo(0, Math.max(0, rect.top - 48));
                    }
                });
            }
        };

        etName.setOnFocusChangeListener(scrollIntoView);
        etEmail.setOnFocusChangeListener(scrollIntoView);
        etPhone.setOnFocusChangeListener(scrollIntoView);

        ViewCompat.requestApplyInsets(signupScrollView);
    }
}
