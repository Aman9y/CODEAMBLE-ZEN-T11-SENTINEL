package online.monarchlabs.sentinel;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import online.monarchlabs.sentinel.utils.LoadingDialogManager;
import online.monarchlabs.sentinel.utils.InfoContentRepository;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ParentSettingsActivity extends BaseActivity {

    private SessionManager sessionManager;
    private ConnectedDevicesManager connectedDevicesManager;
    private LoadingDialogManager loadingDialogManager;
    private FirebaseAuth mAuth;
    private String selectedChildDeviceId;

    private TextView tvParentName;
    private TextView tvParentEmail;
    private TextView tvParentPhone;
    private TextView tvAccountDate;

    // Uninstall protection managed elsewhere; not shown here.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_settings);

        // Initialize managers
        sessionManager = new SessionManager(this);
        connectedDevicesManager = new ConnectedDevicesManager(this);
        loadingDialogManager = new LoadingDialogManager(this);
        mAuth = FirebaseAuth.getInstance();

        selectedChildDeviceId = getIntent().getStringExtra("selected_child_device_id");

        initializeViews();
        setupToolbar();
        loadProfileData();
        setupClickListeners();
        setupBottomNavigation();
    }

    private void initializeViews() {
        tvParentName = findViewById(R.id.tvParentName);
        tvParentEmail = findViewById(R.id.tvParentEmail);
        tvParentPhone = findViewById(R.id.tvParentPhone);
        tvAccountDate = findViewById(R.id.tvAccountDate);

    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void loadProfileData() {
        // 1. Get User from Firebase (Source of Truth)
        if (mAuth.getCurrentUser() != null) {
            String email = mAuth.getCurrentUser().getEmail();
            String displayName = mAuth.getCurrentUser().getDisplayName();
            String phoneNumber = mAuth.getCurrentUser().getPhoneNumber(); // Might be null if email login

            // Fallback to SessionManager if Firebase phone is null (common in email auth)
            if (phoneNumber == null || phoneNumber.isEmpty()) {
                phoneNumber = sessionManager.getPhoneNumber();
            }

            // Set Name - Load from Firebase Database
            String uid = mAuth.getCurrentUser().getUid();
            final String fallbackEmail = email;

            // Set temporary while loading
            tvParentName.setText("Loading...");

            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("parent_profiles")
                    .child(uid)
                    .child("displayName")
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        String name = snapshot.getValue(String.class);
                        if (name != null && !name.trim().isEmpty()) {
                            tvParentName.setText(name);
                        } else if (displayName != null && !displayName.isEmpty()) {
                            tvParentName.setText(displayName);
                        } else {
                            // Extract from email as fallback
                            String extractedName = null;
                            if (fallbackEmail != null) {
                                try {
                                    String namePart = fallbackEmail.split("@")[0].replaceAll("\\d+", "")
                                            .replaceAll("[^a-zA-Z]", "");
                                    if (!namePart.isEmpty()) {
                                        extractedName = namePart.substring(0, 1).toUpperCase() + namePart.substring(1);
                                    }
                                } catch (Exception e) {
                                    // Ignore
                                }
                            }
                            tvParentName.setText(extractedName != null ? extractedName : "Parent Account");
                        }
                    })
                    .addOnFailureListener(e -> {
                        tvParentName.setText("Parent Account");
                    });

            // Set Email
            if (email != null && !email.isEmpty()) {
                tvParentEmail.setText(email);
            } else {
                tvParentEmail.setText("No Email Linked");
            }

            // Set Phone (Formatted)
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                tvParentPhone.setText(formatPhoneNumber(phoneNumber));
            } else {
                tvParentPhone.setText("No Phone Linked");
            }

            // Load account creation date
            long creationTimestamp = mAuth.getCurrentUser().getMetadata().getCreationTimestamp();
            if (creationTimestamp > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
                String dateStr = sdf.format(new Date(creationTimestamp));
                if (tvAccountDate != null) {
                    tvAccountDate.setText("Joined: " + dateStr);
                    tvAccountDate.setVisibility(View.VISIBLE);
                }
            }
        } else {
            // Fallback if auth is weirdly null but session exists
            tvParentName.setText("Parent");
            tvParentEmail.setText(sessionManager.getUserId()); // Shows UID unfortunately if we are here
            tvParentPhone.setText(sessionManager.getPhoneNumber());
        }
    }

    private String formatPhoneNumber(String phone) {
        if (phone == null)
            return "";
        // Simple formatting: +919876543210 -> +91 98765 43210
        // Assumes 10 digit + country code usually
        if (phone.length() > 10) {
            // Try to add spaces? keeping it simple for now to avoid breaking irregular
            // numbers
            return phone.replaceAll("(\\d{2})(\\d{5})(\\d{5})", "$1 $2 $3"); // Example for +91...
        }
        return phone;
    }

    private void setupClickListeners() {
        // Disconnect All Devices UI removed (managed per-child from Dashboard)

        // Terms of Service
        findViewById(R.id.btnTerms).setOnClickListener(v -> openInfoPage(InfoContentRepository.KEY_TERMS));

        // Privacy Policy
        findViewById(R.id.btnPrivacy).setOnClickListener(v -> openInfoPage(InfoContentRepository.KEY_PRIVACY));

        // Help & Support
        findViewById(R.id.btnHelpSupport).setOnClickListener(v -> openInfoPage(InfoContentRepository.KEY_HELP));

        // Logout
        MaterialButton btnLogout = findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> showLogoutConfirmation());
        }

        MaterialButton btnDeleteAccount = findViewById(R.id.btnDeleteAccount);
        if (btnDeleteAccount != null) {
            btnDeleteAccount.setOnClickListener(v -> showDeleteAccountConfirmation());
        }
    }

    private void openInfoPage(String contentKey) {
        Intent intent = new Intent(this, InfoDetailActivity.class);
        intent.putExtra(InfoDetailActivity.EXTRA_CONTENT_KEY, contentKey);
        startActivity(intent);
    }

    // Disconnect methods removed — managed from Dashboard per-device

    private void showLogoutConfirmation() {
        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                .setTitle("Confirm Logout")
                .setMessage(
                        "Are you sure you want to logout?\n\nThis will sign you out and you will need to login again.")
                .setPositiveButton("Logout", (dialog, which) -> {
                    performLogout();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogout() {
        loadingDialogManager.show("Logging Out", "Please wait...");

        // Clear this device's session from Firebase (multi-device: only removes own entry)
        if (mAuth != null && mAuth.getCurrentUser() != null) {
            String uid = mAuth.getCurrentUser().getUid();
            String currentDeviceId = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("parent_clients")
                    .child(uid)
                    .child(currentDeviceId)
                    .removeValue()
                    .addOnSuccessListener(aVoid -> {
                        android.util.Log.d("ParentSettings", "Active device cleared from Firebase (multi-device)");
                    })
                    .addOnFailureListener(e -> {
                        android.util.Log.e("ParentSettings", "Failed to clear active device: " + e.getMessage());
                    });
        }

        // Remove parent-only usage history and icon cache on logout.
        online.monarchlabs.sentinel.utils.ParentUsageCacheManager.getInstance(this).clearAll();

        // Logout logic
        sessionManager.logoutUser();
        if (mAuth != null) {
            mAuth.signOut();
        }
        try {
            GoogleSignIn.getClient(this, new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()).signOut();
        } catch (Exception e) {
            android.util.Log.e("ParentSettings", "Failed to sign out Google client", e);
        }
        connectedDevicesManager.clearAllDevices();

        new android.os.Handler().postDelayed(() -> {
            loadingDialogManager.hide();

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }, 1000);
    }

    private void showDeleteAccountConfirmation() {
        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                .setTitle("Permanently Delete Account?")
                .setMessage("This permanently deletes your parent account, consent records, connected child-device data, usage history, locations, app lists, timers, and control settings. This cannot be undone.")
                .setPositiveButton("Continue", (dialog, which) -> showFinalDeleteConfirmation())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFinalDeleteConfirmation() {
        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                .setTitle("Final Confirmation")
                .setMessage("Delete the account and all associated cloud data now?")
                .setPositiveButton("Delete Permanently", (dialog, which) -> performAccountDeletion())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performAccountDeletion() {
        if (mAuth == null || mAuth.getCurrentUser() == null) {
            Toast.makeText(this, "Please sign in again before deleting your account.", Toast.LENGTH_LONG).show();
            return;
        }

        loadingDialogManager.show("Deleting Account", "Removing account and connected device data...");
        new DataPrivacyService(this).deleteCurrentAccount()
                .thenAccept(result -> runOnUiThread(() -> {
                    if (!result.success) {
                        loadingDialogManager.hide();
                        String message = result.message.isEmpty() ? "Account deletion failed." : result.message;
                        if (message.toLowerCase(Locale.US).contains("recent")) {
                            message = "For security, sign out and sign in again before deleting your account.";
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                        return;
                    }
                    online.monarchlabs.sentinel.utils.ParentUsageCacheManager.getInstance(this).clearAll();
                    sessionManager.logoutUser();
                    connectedDevicesManager.performNuclearStorageCleanup();
                    mAuth.signOut();
                    loadingDialogManager.hide();

                    Intent intent = new Intent(this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                }));
    }

    private void setupBottomNavigation() {
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(
                R.id.bottomNavigation);
        if (bottomNav != null) {
            // Set Settings as selected
            bottomNav.setSelectedItemId(R.id.nav_settings);

            bottomNav.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.nav_home) {
                    // Go back to dashboard
                    finish();
                    return true;
                } else if (itemId == R.id.nav_timer_status) {
                    String currentChildDeviceId = selectedChildDeviceId;
                    if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
                        currentChildDeviceId = connectedDevicesManager.getCurrentDeviceId();
                    }
                    if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
                        Intent intent = new Intent(this, TimerStatusActivity.class);
                        intent.putExtra(TimerStatusActivity.EXTRA_DEVICE_ID, currentChildDeviceId);
                        intent.putExtra(TimerStatusActivity.EXTRA_IS_PARENT, true);
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                } else if (itemId == R.id.nav_assistant) {
                    String childId = selectedChildDeviceId;
                    if (childId == null || childId.isEmpty()) {
                        childId = connectedDevicesManager.getCurrentDeviceId();
                    }
                    if (childId != null && !childId.isEmpty()) {
                        Intent intent = new Intent(this, AssistantActivity.class);
                        intent.putExtra(AssistantActivity.EXTRA_SELECTED_CHILD_ID, childId);
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                } else if (itemId == R.id.nav_settings) {
                    // Already on settings
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loadingDialogManager != null) {
            loadingDialogManager.cleanup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Force bottom navigation selection back to Settings when returning to this activity
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(
                R.id.bottomNavigation);
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_settings);
        }
    }

    /**
     * 🛡️ Setup Uninstall Protection Toggle with confirmation dialog
     */
    // Uninstall protection settings removed from this page.
}
