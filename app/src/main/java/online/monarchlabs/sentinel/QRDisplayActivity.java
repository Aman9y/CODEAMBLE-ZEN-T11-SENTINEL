package online.monarchlabs.sentinel;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import android.widget.FrameLayout;

public class QRDisplayActivity extends BaseActivity {
    private static final String TAG = "QRDisplayActivity";
    private static final long QR_VALIDITY_TIME = 120000; // 2 minutes in milliseconds
    private static final String CONSENT_VERSION = "guardian-monitoring-v1";
    private static final String POLICY_VERSION = "privacy-2026-06-12";

    // UI Components
    private ImageView ivQRCode;
    private ImageButton btnClose;
    private Button btnRefreshQR;
    private TextView tvTimer;
    // tvStatus removed per user request
    private TextView tvInstructions;
    private ProgressBar progressTimer;
    private FrameLayout llQRSection;
    private LinearLayout llConnectedDevices;
    private TextView tvConnectedCount;

    // QR Code Management
    private String baseQRKey;
    private String currentSessionId;
    private CountDownTimer qrTimer;
    private DatabaseReference qrSessionRef;
    private ValueEventListener connectionListener;
    private DatabaseReference parentLinksRef;
    private ValueEventListener parentLinksListener;
    private boolean isQRActive = false;
    private int connectedDevicesCount = 0;

    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_display_enhanced);

        sessionManager = new SessionManager(this);

        initViews();
        setupClickListeners();
        setupConnectionListener();

        baseQRKey = getIntent().getStringExtra("qr_key");
        if (baseQRKey == null) {
            finish();
            return;
        }

        updatePairingAvailability();
    }

    private void initViews() {
        ivQRCode = findViewById(R.id.ivQRCode);
        btnClose = findViewById(R.id.btnCloseNew);
        btnRefreshQR = findViewById(R.id.btnRefreshQR);
        tvTimer = findViewById(R.id.tvTimer);
        tvTimer.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_timer_status, 0, 0, 0);
        tvTimer.setCompoundDrawablePadding((int) (4 * getResources().getDisplayMetrics().density));

        tvInstructions = findViewById(R.id.tvInstructions);
        progressTimer = findViewById(R.id.progressTimer);
        llQRSection = findViewById(R.id.llQRSection);
        llConnectedDevices = findViewById(R.id.llConnectedDevices);
        tvConnectedCount = findViewById(R.id.tvConnectedCount);

        // Set initial instruction text
        tvInstructions.setText(
                "Scan this QR code from your child's device to connect it to Sentinel: The parental monitoring application.");
        updateConnectedDevicesDisplay();
    }

    private void setupClickListeners() {
        btnClose.setOnClickListener(v -> finish());
        btnRefreshQR.setOnClickListener(v -> {
            Log.d(TAG, "Manual QR refresh requested");
            generateNewQRSession();
        });
    }

    private void updatePairingAvailability() {
        btnRefreshQR.setEnabled(true);
        btnRefreshQR.setText("Generate New Code");
        if (!isQRActive) {
            generateNewQRSession();
        }
    }

    private void setupConnectionListener() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            return;
        }
        String parentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        parentLinksRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_device_links")
                .child(parentUid);
        parentLinksListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                connectedDevicesCount = (int) snapshot.getChildrenCount();
                updateConnectedDevicesDisplay();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to listen for v2 device links: " + error.getMessage());
            }
        };
        parentLinksRef.addValueEventListener(parentLinksListener);
    }
    private void generateNewQRSession() {
        // Stop existing timer
        if (qrTimer != null) {
            qrTimer.cancel();
        }

        // Remove old session listener
        if (connectionListener != null && qrSessionRef != null) {
            qrSessionRef.removeEventListener(connectionListener);
        }

        // Generate new session
        currentSessionId = UUID.randomUUID().toString();
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // ðŸ”§ FIX: Use actual parent name instead of device name
        String parentName = sessionManager.getParentProfileName();
        if (parentName == null || parentName.isEmpty()) {
            parentName = "Parent"; // Fallback
        }

        // We handle device name separately if needed, but for the child connection
        // display we want the parent's name
        String deviceName = ParentUtils.getParentDeviceName();

        Log.d(TAG, "ðŸ”„ Generating new QR session: " + currentSessionId);

        // Create session data in Firebase
        qrSessionRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("pairing_sessions")
                .child(currentSessionId);

        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("parentDeviceId", deviceId);
        // PASS PARENT NAME AS DEVICE NAME SO CHILD APP DISPLAYS IT
        // The child app reads this field and displays "Connected to: [this value]"
        sessionData.put("parentDeviceName", parentName);
        sessionData.put("realDeviceName", deviceName); // Store real device name for reference
        sessionData.put("baseQRKey", baseQRKey);
        sessionData.put("createdAt", System.currentTimeMillis());
        sessionData.put("expiresAt", System.currentTimeMillis() + QR_VALIDITY_TIME);
        sessionData.put("isActive", true);
        sessionData.put("status", "active");
        sessionData.put("schemaVersion", 2);
        sessionData.put("maxConnections", 5); // Allow up to 5 devices to connect with one QR
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Toast.makeText(this, "Please sign in again before pairing a device.", Toast.LENGTH_LONG).show();
            return;
        }

        String parentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        sessionData.put("parentUid", parentUid);
        sessionData.put("consentEventId", currentSessionId);
        sessionData.put("consentVersion", CONSENT_VERSION);
        sessionData.put("policyVersion", POLICY_VERSION);
        recordPendingConsent(parentUid, deviceId, sessionData);
    }

    private void createQrSession(Map<String, Object> sessionData) {
        qrSessionRef.setValue(sessionData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "âœ… QR session created successfully");
                    generateAndDisplayQR();
                    startQRTimer();
                    listenForConnections();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "âŒ Failed to create QR session: " + e.getMessage());
                    Log.e(TAG, "Failed to generate QR: " + e.getMessage());
                    Toast.makeText(this, "Could not create pairing code: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void recordPendingConsent(String parentUid, String parentDeviceId,
            Map<String, Object> sessionData) {
        Map<String, Object> consent = new HashMap<>();
        consent.put("parentUid", parentUid);
        consent.put("parentDeviceId", parentDeviceId);
        consent.put("consentVersion", CONSENT_VERSION);
        consent.put("policyVersion", POLICY_VERSION);
        consent.put("affirmedAt", com.google.firebase.database.ServerValue.TIMESTAMP);
        consent.put("expiresAt", System.currentTimeMillis() + QR_VALIDITY_TIME);
        consent.put("status", "pending_pairing");

        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("consent_events")
                .child(parentUid)
                .child(currentSessionId)
                .setValue(consent)
                .addOnSuccessListener(ignored -> createQrSession(sessionData))
                .addOnFailureListener(error -> {
                    Log.e(TAG, "Failed to record guardian consent", error);
                    Toast.makeText(this, "Could not record consent: " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    private void generateAndDisplayQR() {
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

            // ðŸ”§ FIX: Use actual parent name instead of device name
            String parentName = sessionManager.getParentProfileName();
            if (parentName == null || parentName.isEmpty()) {
                parentName = "Parent"; // Fallback
            }

            // Enhanced QR data with session ID for time-limited access
            // Third parameter is what child app displays as "Connected to"
            String qrData = String.format("%s|%s|%s|%s|%d",
                    baseQRKey, deviceId, parentName, currentSessionId, System.currentTimeMillis());

            Log.d(TAG, "ðŸ“± Generating QR with data: " + qrData);

            Bitmap qrBitmap = QRCodeManager.generateQRCodeBitmap(qrData, 600, 600);
            if (qrBitmap != null) {
                ivQRCode.setImageBitmap(qrBitmap);

                // Animate QR code appearance
                Animation fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
                ivQRCode.startAnimation(fadeIn);

                isQRActive = true;
                isQRActive = true;
            } else {

            }
        } catch (Exception e) {
            Log.e(TAG, "Error generating QR: " + e.getMessage());

        }
    }

    private void startQRTimer() {
        progressTimer.setMax((int) QR_VALIDITY_TIME);
        progressTimer.setProgress((int) QR_VALIDITY_TIME);

        qrTimer = new CountDownTimer(QR_VALIDITY_TIME, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long minutes = millisUntilFinished / 60000;
                long seconds = (millisUntilFinished % 60000) / 1000;

                String timeText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
                tvTimer.setText(timeText);

                progressTimer.setProgress((int) millisUntilFinished);

                // Change color as time runs out
                if (millisUntilFinished < 30000) { // Last 30 seconds
                    tvTimer.setTextColor(ContextCompat.getColor(QRDisplayActivity.this, android.R.color.holo_red_dark));
                } else if (millisUntilFinished < 60000) { // Last minute
                    tvTimer.setTextColor(
                            ContextCompat.getColor(QRDisplayActivity.this, android.R.color.holo_orange_dark));
                } else {
                    tvTimer.setTextColor(
                            ContextCompat.getColor(QRDisplayActivity.this, android.R.color.holo_green_dark));
                }
            }

            @Override
            public void onFinish() {
                Log.d(TAG, "â° QR code expired, generating new one");
                expireCurrentQR();

                // Auto-generate new QR after 2 seconds
                new Handler().postDelayed(() -> generateNewQRSession(), 2000);
            }
        };

        qrTimer.start();
    }

    private void expireCurrentQR() {
        isQRActive = false;

        // Update session as expired
        if (qrSessionRef != null) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("isActive", false);
            updates.put("status", "expired");
            updates.put("expiredAt", System.currentTimeMillis());
            qrSessionRef.updateChildren(updates);
        }

        // Update UI
        // Update UI
        tvTimer.setText("Generating new QR...");

        // Fade out current QR
        Animation fadeOut = AnimationUtils.loadAnimation(this, android.R.anim.fade_out);
        ivQRCode.startAnimation(fadeOut);
    }

    private void listenForConnections() {
        if (qrSessionRef == null)
            return;

        connectionListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists())
                    return;

                // Check for new child device connections
                DataSnapshot connectionsSnapshot = dataSnapshot.child("connections");
                if (connectionsSnapshot.exists()) {
                    long connectionCount = connectionsSnapshot.getChildrenCount();

                    if (connectionCount > 0) {
                        Log.d(TAG, "ðŸŽ‰ New device connected! Total connections: " + connectionCount);

                        // Show success animation
                        showConnectionSuccess();

                        // Process each connection
                        for (DataSnapshot connectionSnapshot : connectionsSnapshot.getChildren()) {
                            processNewConnection(connectionSnapshot);
                        }
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Connection listener cancelled: " + databaseError.getMessage());
            }
        };

        qrSessionRef.addValueEventListener(connectionListener);
    }

    private void processNewConnection(DataSnapshot connectionSnapshot) {
        String childDeviceId = connectionSnapshot.child("childDeviceId")
                .getValue(String.class);
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            childDeviceId = connectionSnapshot.getKey();
        }
        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            removePermanentRemoval(childDeviceId);
            Log.d(TAG, "Canonical v2 connection confirmed for " + childDeviceId);
        }
    }
    private void removePermanentRemoval(String childDeviceId) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;

        String parentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        getSharedPreferences(parentUserId + "_permanently_removed_devices", MODE_PRIVATE)
                .edit()
                .remove(childDeviceId)
                .apply();
        getSharedPreferences("permanently_removed_devices", MODE_PRIVATE)
                .edit()
                .remove(childDeviceId)
                .apply();
    }

    private void showConnectionSuccess() {
        runOnUiThread(() -> {
            // Flash green background
            llQRSection.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light));

            // Reset background after animation
            new Handler().postDelayed(() -> {
                llQRSection.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent));
            }, 1000);

            // Update status temporarily
            // Update status temporarily

            Toast.makeText(this, "Child device connected successfully!", Toast.LENGTH_SHORT).show();

            // ðŸ”§ AUTO-DISMISS: Close QR screen after 2 seconds
            new Handler().postDelayed(() -> {
                finish(); // Auto-close QR display
            }, 2000);
        });
    }

    private void updateConnectedDevicesDisplay() {
        runOnUiThread(() -> {
            tvConnectedCount.setText(String.format(Locale.getDefault(),
                    "Connected devices: %d", connectedDevicesCount));

            if (connectedDevicesCount > 0) {
                llConnectedDevices.setVisibility(View.VISIBLE);
            } else {
                llConnectedDevices.setVisibility(View.GONE);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Clean up
        if (qrTimer != null) {
            qrTimer.cancel();
        }

        if (parentLinksRef != null && parentLinksListener != null) {
            parentLinksRef.removeEventListener(parentLinksListener);
        }

        if (connectionListener != null && qrSessionRef != null) {
            qrSessionRef.removeEventListener(connectionListener);
        }

        // Mark current session as inactive
        if (qrSessionRef != null) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("isActive", false);
            updates.put("status", "expired");
            updates.put("closedAt", System.currentTimeMillis());
            qrSessionRef.updateChildren(updates);
        }
    }
}
