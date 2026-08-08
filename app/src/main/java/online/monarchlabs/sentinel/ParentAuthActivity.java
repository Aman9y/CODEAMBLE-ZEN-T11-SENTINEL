package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.android.material.checkbox.MaterialCheckBox;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.utils.InfoContentRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * Parent authentication choice screen.
 * Allows parents to sign in or sign up via Google.
 */
public class ParentAuthActivity extends BaseActivity {
    private static final String TAG = "ParentAuthActivity";

    private MaterialCheckBox cbLegalAgreement;
    private TextView tvLegalError;
    private CardView cardGoogleLogin;
    private CardView cardLogin;
    private CardView cardSignup;
    private ProgressBar progressBar;

    private FirebaseAuth mAuth;
    private GoogleSignInClient mGoogleSignInClient;
    private SessionManager sessionManager;

    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
                    handleGoogleSignInResult(task);
                } else {
                    setLoading(false);
                    Log.w(TAG, "Google Sign-In activity result not OK: " + result.getResultCode());
                    Toast.makeText(this, "Google Sign-In canceled or failed.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_auth);

        mAuth = FirebaseAuth.getInstance();
        sessionManager = new SessionManager(this);

        // Configure Google Sign-In
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        initViews();
        setupListeners();
    }

    private void initViews() {
        cbLegalAgreement = findViewById(R.id.cbLegalAgreement);
        tvLegalError = findViewById(R.id.tvLegalError);
        cardGoogleLogin = findViewById(R.id.cardGoogleLogin);
        cardLogin = findViewById(R.id.cardLogin);
        cardSignup = findViewById(R.id.cardSignup);
        progressBar = findViewById(R.id.progressBar);

        setupLegalAgreementText();
    }

    private void setupListeners() {
        cardGoogleLogin.setOnClickListener(v -> {
            tvLegalError.setVisibility(View.GONE);

            if (!cbLegalAgreement.isChecked()) {
                tvLegalError.setVisibility(View.VISIBLE);
                cbLegalAgreement.requestFocus();
                return;
            }

            setLoading(true);
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            googleSignInLauncher.launch(signInIntent);
        });

        cardLogin.setOnClickListener(v -> {
            startActivity(new Intent(this, ParentEmailLoginActivity.class));
        });

        cardSignup.setOnClickListener(v -> {
            startActivity(new Intent(this, ParentSignupActivity.class));
        });

        cbLegalAgreement.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                tvLegalError.setVisibility(View.GONE);
            }
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
            public void onClick(@NonNull View widget) {
                Intent intent = new Intent(ParentAuthActivity.this, InfoDetailActivity.class);
                intent.putExtra(InfoDetailActivity.EXTRA_CONTENT_KEY, contentKey);
                startActivity(intent);
            }
        }, start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void handleGoogleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            if (account != null) {
                String idToken = account.getIdToken();
                firebaseAuthWithGoogle(idToken, account.getDisplayName());
            } else {
                setLoading(false);
                Toast.makeText(this, "Failed to retrieve Google Account details.", Toast.LENGTH_SHORT).show();
            }
        } catch (ApiException e) {
            setLoading(false);
            Log.e(TAG, "Google sign in failed code=" + e.getStatusCode(), e);
            Toast.makeText(this, "Google Sign-In failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void firebaseAuthWithGoogle(String idToken, final String displayName) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            boolean isNewUser = task.getResult().getAdditionalUserInfo() != null
                                    && task.getResult().getAdditionalUserInfo().isNewUser();
                            
                            if (isNewUser) {
                                createNewParentProfile(user.getUid(), displayName, user.getEmail());
                            } else {
                                checkAndLoadParentProfile(user.getUid(), displayName, user.getEmail());
                            }
                        } else {
                            setLoading(false);
                            Toast.makeText(this, "Firebase Auth failed: user is null.", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        setLoading(false);
                        Log.e(TAG, "Firebase Auth with Google failed", task.getException());
                        Toast.makeText(this, "Authentication failed. Make sure your Web Client ID is configured.", Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void createNewParentProfile(String uid, String displayName, String email) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        long createdAt = System.currentTimeMillis();
        
        final String resolvedName = TextUtils.isEmpty(displayName) ? "Parent" : displayName;
        
        FirebaseSchemaV2Repository.syncParentIdentity(uid, resolvedName, email, "", deviceId, createdAt)
                .addOnSuccessListener(ignored -> {
                    saveLegalAcceptance(uid);
                    sessionManager.saveParentSessionWithoutPhone(uid, ParentUtils.getParentDeviceName(), resolvedName);
                    proceedToDashboard();
                })
                .addOnFailureListener(error -> {
                    setLoading(false);
                    Log.e(TAG, "Failed to create parent profile in RTDB", error);
                    Toast.makeText(this, "Failed to initialize parent profile: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void checkAndLoadParentProfile(String uid, String googleName, String email) {
        DatabaseReference profileRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_profiles")
                .child(uid);
                
        profileRef.get().addOnSuccessListener(snapshot -> {
            if (snapshot.exists()) {
                String name = snapshot.child("displayName").getValue(String.class);
                String phone = snapshot.child("phone").getValue(String.class);
                
                String resolvedName = TextUtils.isEmpty(name) ? (TextUtils.isEmpty(googleName) ? "Parent" : googleName) : name;
                sessionManager.saveParentSession(phone, uid, ParentUtils.getParentDeviceName(), resolvedName);
                proceedToDashboard();
            } else {
                createNewParentProfile(uid, googleName, email);
            }
        }).addOnFailureListener(error -> {
            setLoading(false);
            Log.e(TAG, "Failed to load parent profile", error);
            Toast.makeText(this, "Failed to load profile. Please try again.", Toast.LENGTH_LONG).show();
        });
    }

    private void saveLegalAcceptance(String uid) {
        Map<String, Object> acceptance = new HashMap<>();
        acceptance.put("uid", uid);
        acceptance.put("termsVersion", LegalPolicyVersions.TERMS);
        acceptance.put("privacyVersion", LegalPolicyVersions.PRIVACY);
        acceptance.put("acceptedAt", System.currentTimeMillis());
        acceptance.put("recordedAt", ServerValue.TIMESTAMP);
        acceptance.put("source", "google_signup");

        FirebaseDatabase.getInstance().getReference("v2")
                .child("legal_acceptances").child(uid)
                .child("account_creation").setValue(acceptance)
                .addOnFailureListener(error ->
                        Log.w(TAG, "Legal acceptance write failed", error));
    }

    private void proceedToDashboard() {
        setLoading(false);
        Intent intent = new Intent(this, ParentDashboardActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        cardGoogleLogin.setEnabled(!loading);
        cardLogin.setEnabled(!loading);
        cardSignup.setEnabled(!loading);
        cbLegalAgreement.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
