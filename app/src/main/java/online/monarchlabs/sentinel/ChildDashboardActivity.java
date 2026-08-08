
package online.monarchlabs.sentinel;

import android.Manifest;
import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Date;
import java.util.Set;
import java.util.Arrays;
import android.content.SharedPreferences;
import android.widget.ImageView;
import android.app.DatePickerDialog;
import java.util.Calendar;
import androidx.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import android.os.Build;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;
import android.graphics.Color;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import android.provider.Settings;
import android.text.TextUtils;
import android.content.ComponentName;
import androidx.appcompat.app.AlertDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import online.monarchlabs.sentinel.SessionManager;
import com.google.firebase.auth.FirebaseAuth;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import java.util.Collections;
import java.util.Comparator;
import android.content.DialogInterface;
import androidx.annotation.NonNull;
import online.monarchlabs.sentinel.ChildLoginActivity;
import online.monarchlabs.sentinel.ChildDevice;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;

public class ChildDashboardActivity extends BaseActivity {
    private static final String TAG = "ChildDashboard";
    private TextView tvParentName, tvConnectionStatus, tvTodayScreenTime;
    private BottomNavigationView bottomNavigation;
    private View homeContent;
    private View settingsContent;
    private View blockedAppsCard;
    private LinearLayout blockedAppsContainer;
    private BroadcastReceiver blockedAppsUiReceiver;
    private String parentName, shareKey;
    private SessionManager sessionManager;
    private DeviceStatusManager deviceStatusManager;
    private BatteryOptimizationManager batteryOptimizationManager;

    // Timer components
    private androidx.cardview.widget.CardView timerCard;
    private TextView tvTimerDisplay, tvTimerStatus;
    private String childDeviceId;
    private DatabaseReference dashboardTimerRef;
    private ValueEventListener dashboardTimerListener;

    // Usage Limiter components
    private androidx.cardview.widget.CardView usageLimiterCard;
    private TextView tvTimerDisplayLimiter, tvTimerStatusLimiter, tvSelectedApps;
    private LinearLayout selectedAppsInfo;
    private DatabaseReference usageLimiterRef;
    private ValueEventListener usageLimiterListener;
    private Handler limiterUpdateHandler;
    private Runnable limiterUpdateRunnable;
    private String currentForegroundApp = "";
    private boolean isLimiterActive = false;

    // Active Timers components
    private androidx.cardview.widget.CardView activeTimersCard;
    private RecyclerView rvActiveTimers;
    private ActiveTimerAdapter activeTimerAdapter;
    private List<ActiveTimerItem> activeTimerList = new ArrayList<>();
    private DatabaseReference activeTimersRef;
    private ValueEventListener activeTimersListener;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener timerCachePrefsListener;
    private long remainingTimeMs = 0;
    private List<String> limitedApps = new ArrayList<>();
    private BroadcastReceiver foregroundAppReceiver;
    private boolean isTimerCountingDown = false;

    // Direct foreground app monitoring
    private Handler foregroundAppHandler;
    private Runnable foregroundAppRunnable;

    // Flag to prevent recursive Firebase listener processing
    private boolean isUpdatingFirebase = false;

    // High-precision timer tracking
    private long lastTimerUpdateMs = 0;
    private long lastFirebaseUpdateMs = 0;

    /**
     * Format time with higher precision (shows seconds more accurately)
     */
    private String formatTimePrecise(long timeMs) {
        if (timeMs <= 0)
            return "0:00";

        long totalSeconds = (timeMs + 500) / 1000; // Round to nearest second
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        if (minutes > 0) {
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "0:%02d", seconds);
        }
    }



    private DatabaseReference snapshotRef;

    private String lastDisplayedTime = "";
    private String lastDisplayedStatus = "";

    // NEW: Broadcast receiver for logout
    private BroadcastReceiver logoutReceiver;
    private boolean isLogoutInProgress = false;

    // 🎯 SINGLE USAGE TRACKER - BulletproofUsageTracker only
    // OLD TRACKERS REMOVED: AccurateUsageTracker, DateAwareUsageDataManager,
    // RollingUsageDataManager
    // These were causing conflicts and duplicate Firebase writes
    private FreshConnectionManager freshConnectionManager;
    private BulletproofUsageTracker bulletproofUsageTracker; // ONLY usage tracker now

    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!ChildMonitoringDisclosureActivity.hasAcceptedDisclosure(this)) {
            Intent disclosureIntent = new Intent(this, ChildMonitoringDisclosureActivity.class);
            disclosureIntent.putExtra(ChildMonitoringDisclosureActivity.EXTRA_RETURN_TO_DASHBOARD, true);
            startActivity(disclosureIntent);
            finish();
            return;
        }

        // 🛡️ BULLETPROOF: Wrap EVERYTHING in try-catch to prevent crashes
        try {
            setContentView(R.layout.activity_child_dashboard);
        } catch (Exception e) {
            Log.e(TAG, "❌ CRITICAL: Failed to set content view: " + e.getMessage());
            finish();
            return;
        }

        // Initialize core components first (these should never fail)
        try {
            mAuth = FirebaseAuth.getInstance();
            sessionManager = new SessionManager(this);
        } catch (Exception e) {
            Log.e(TAG, "❌ CRITICAL: Failed to initialize core components: " + e.getMessage());
            finish();
            return;
        }

        // 🛡️ BULLETPROOF SESSION RECOVERY: Never logout, always try to recover
        Log.d(TAG, "🛡️ BULLETPROOF MODE: Child app will NEVER logout automatically");

        // Get the current child's device ID from session manager
        childDeviceId = sessionManager.getChildDeviceId();
        if (isParentRemovalStateActive()) {
            Log.w(TAG, "Parent removal state active during dashboard launch - redirecting to QR scanner");
            redirectToQrAfterParentRemoval("dashboard_on_create");
            return;
        }
        ChildDisconnectionCoordinator.validateCurrentOwnership(this, "dashboard_on_create");

        // Initialize Fresh Connection Manager (non-critical)
        try {
            freshConnectionManager = new FreshConnectionManager(this, childDeviceId);

            // Handle fresh connection if this is a new QR pairing
            boolean isFreshConnection = getIntent().getBooleanExtra("fresh_connection", false);
            if (isFreshConnection) {
                Log.d(TAG, "🧹 Fresh connection detected - clearing all previous data");
                freshConnectionManager.handleFreshConnection();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing FreshConnectionManager: " + e.getMessage());
        }

        // 🎯 BULLETPROOF USAGE TRACKING - Uses reliable UsageStatsManager
        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Log.d(TAG, "🎯 Starting BULLETPROOF usage tracking...");

                    // Create the new bulletproof tracker
                    bulletproofUsageTracker = new BulletproofUsageTracker(ChildDashboardActivity.this);

                    // Check permission first
                    if (bulletproofUsageTracker.hasUsagePermission()) {
                        bulletproofUsageTracker.start();
                        Log.d(TAG, "✅ Bulletproof usage tracking started!");
                        Log.d(TAG, bulletproofUsageTracker.getStatus());
                    } else {
                        Log.w(TAG, "⚠️ No usage stats permission - requesting...");
                        bulletproofUsageTracker.requestUsagePermission();
                    }

                    // 🆕 IMMEDIATE SUSAGE UPLOAD - Force upload on app start
                    Log.d(TAG, "🚀 Triggering immediate SUSAGE data upload...");
                    final String deviceIdForUpload = childDeviceId;
                    new Thread(() -> {
                        try {
                            online.monarchlabs.sentinel.utils.SUsageDataManager susageManager = online.monarchlabs.sentinel.utils.SUsageDataManager
                                    .getInstance(ChildDashboardActivity.this);

                            susageManager.uploadToFirebase(deviceIdForUpload,
                                    new online.monarchlabs.sentinel.utils.SUsageDataManager.OnUploadCompleteListener() {
                                        @Override
                                        public void onSuccess() {
                                            Log.d(TAG, "✅✅✅ IMMEDIATE SUSAGE UPLOAD SUCCESSFUL!");
                                        }

                                        @Override
                                        public void onError(String error) {
                                            Log.e(TAG, "❌ Immediate SUSAGE upload failed: " + error);
                                        }
                                    });
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Exception in immediate SUSAGE upload: " + e.getMessage());
                        }
                    }).start();

                } catch (Exception e) {
                    Log.e(TAG, "❌ Error starting usage tracking: " + e.getMessage());
                }
            }, 5000); // 5 second delay
        } else {
            Log.w(TAG, "⚠️ Cannot initialize usage tracking - no device ID");
        }

        // 🔧 LOGOUT FIX: Check if logout was intentional before attempting recovery
        SharedPreferences logoutPrefs = getSharedPreferences("logout_state", MODE_PRIVATE);
        boolean intentionalLogout = logoutPrefs.getBoolean("intentional_logout", false);
        long logoutTimestamp = logoutPrefs.getLong("logout_timestamp", 0);
        String logoutReason = logoutPrefs.getString("logout_reason", "");

        // Check if logout was recent (within last 10 seconds)
        boolean recentLogout = (System.currentTimeMillis() - logoutTimestamp) < 10000;

        // 🔧 LOGOUT FIX: Clear old logout flags (older than 30 seconds) to allow normal
        // operation
        if (intentionalLogout && (System.currentTimeMillis() - logoutTimestamp) > 30000) {
            Log.d(TAG, "🧹 Clearing old intentional logout flag (older than 30s)");
            logoutPrefs.edit().clear().apply();
            intentionalLogout = false;
            recentLogout = false;
        }

        if (intentionalLogout && recentLogout) {
            Log.d(TAG, "🚪 INTENTIONAL LOGOUT DETECTED - Preventing emergency recovery");
            Log.d(TAG, "   Logout reason: " + logoutReason);
            Log.d(TAG, "   Logout was " + (System.currentTimeMillis() - logoutTimestamp) + "ms ago");

            // Navigate back to MainActivity/login screen
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra("logout_message", "Device was removed by parent");
            intent.putExtra("force_login_screen", true);
            startActivity(intent);
            finish();
            return;
        }

        if (!sessionManager.isLoggedIn()
                || !"child".equals(sessionManager.getUserType())
                || childDeviceId == null || childDeviceId.isEmpty()
                || sessionManager.getParentUserId() == null
                || sessionManager.getParentUserId().isEmpty()
                || sessionManager.getConnectionId() == null
                || sessionManager.getConnectionId().isEmpty()) {
            ChildDisconnectionCoordinator.disconnectCurrentSession(
                    this, "invalid_local_session");
            return;
        }
        Log.d(TAG, "onCreate: ChildDashboardActivity started with session protection");
        Log.d(TAG, "Child Device ID: " + childDeviceId);

        // 🛡️ BULLETPROOF: Setup listeners with error handling
        try {
            // NEW: Setup broadcast receiver for logout
            setupLogoutBroadcastReceiver();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to setup logout broadcast receiver: " + e.getMessage());
        }

        // Delay Firebase listeners to prevent initialization race conditions
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // Add ENHANCED logout monitoring (monitors multiple Firebase paths)
                listenForRemoteLogoutCommand();
                Log.d(TAG, "✅ Logout monitoring started");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to setup logout monitoring: " + e.getMessage());
            }

            try {
                // Add upload trigger listener for parent data refresh requests
                listenForUploadTriggers();
                Log.d(TAG, "✅ Upload trigger listener started");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to setup upload trigger listener: " + e.getMessage());
            }

        }, 1500); // 1.5 second delay for Firebase listeners

        // Update session activity
        try {
            sessionManager.updateLastActivity();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to update session activity: " + e.getMessage());
        }

        // Get data from intent or session
        parentName = getIntent().getStringExtra("parentName");
        shareKey = getIntent().getStringExtra("shareKey");
        String intentChildDeviceId = getIntent().getStringExtra("deviceId");
        String intentConnectionId = getIntent().getStringExtra("connectionId");
        long intentConnectionLinkedAt = getIntent().getLongExtra("connectionLinkedAt", 0L);

        // Save parentUserId from intent if available
        String intentParentUserId = getIntent().getStringExtra("parentUserId");
        if (intentParentUserId != null) {
            sessionManager.saveParentUserId(intentParentUserId);
        }

        // If not from intent, try to get from session
        if (parentName == null && sessionManager.isLoggedIn()) {
            Log.d(TAG, "🔄 No intent data - loading from session");
            parentName = sessionManager.getParentName();
            shareKey = sessionManager.getQRShareKey();
        }

        // Debug session information
        Log.d(TAG, "📊 Session Debug Info:");
        Log.d(TAG, "   Intent parentName: " + getIntent().getStringExtra("parentName"));
        Log.d(TAG, "   Session parentName: " + sessionManager.getParentName());
        Log.d(TAG, "   Session userType: " + sessionManager.getUserType());
        Log.d(TAG, "   Session isLoggedIn: " + sessionManager.isLoggedIn());
        Log.d(TAG, "   Final parentName: " + parentName);

        // CRITICAL: If we have intent data, re-save session to ensure persistence
        if (getIntent().hasExtra("parentName") && getIntent().hasExtra("deviceId")) {
            String intentParentName = getIntent().getStringExtra("parentName");
            String intentDeviceId = getIntent().getStringExtra("deviceId");
            String intentShareKey = getIntent().getStringExtra("shareKey");

            if (intentParentName != null && intentDeviceId != null) {
                Log.d(TAG, "💾 Re-saving session data from intent to ensure persistence");
                sessionManager.saveChildSession(intentDeviceId, intentParentName, intentShareKey,
                        intentConnectionId, intentConnectionLinkedAt);

                // Update our local variables
                parentName = intentParentName;
                shareKey = intentShareKey;
                childDeviceId = intentDeviceId;
            }
        }

        // Use intent device ID if available, otherwise use session device ID
        if (intentChildDeviceId != null) {
            childDeviceId = intentChildDeviceId;
        }

        // 🛡️ BULLETPROOF: Always ensure we have working connection data
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            ChildDisconnectionCoordinator.disconnectCurrentSession(
                    this, "missing_device_id");
            return;
        }
        if (parentName == null || parentName.trim().isEmpty()) {
            parentName = "Parent";
        }

        Log.d(TAG, "✅ Connection data secured - proceeding with dashboard initialization");

        // 🛡️ BULLETPROOF: Initialize managers with error handling
        try {
            // Initialize device status manager
            deviceStatusManager = new DeviceStatusManager(this);

            // Initialize battery optimization manager for timer service reliability
            batteryOptimizationManager = new BatteryOptimizationManager(this);
            batteryOptimizationManager.checkAndRequestAllPermissions(this);

            online.monarchlabs.sentinel.services.PersistentConnectionService.startService(this);
            Log.d(TAG, "Persistent connection service owns child device status publishing");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize managers: " + e.getMessage());
            // Continue anyway - these are not critical for basic functionality
        }

        // Permissions are now handled in ChildPermissionsActivity

        // 🛡️ BULLETPROOF: Start CRITICAL services only, with error handling
        try {
            // CRITICAL: Start RemoteBlockService to listen for focus mode commands
            ChildServiceCoordinator.start(this, "dashboard_created");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start RemoteBlockService: " + e.getMessage());
        }

        // Initialize the views and setup navigation (CRITICAL - must succeed)
        try {
            initViews();
            setupBottomNavigation();
            setupSettingsClickListeners();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize views: " + e.getMessage());
        }

        // CRITICAL: Restore parent connection if app was restarted
        try {
            restoreParentConnection();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to restore parent connection: " + e.getMessage());
        }

        // SAFETY: Ensure session is always saved with current connection data
        if (parentName != null && childDeviceId != null) {
            Log.d(TAG, "💾 Safety session save - ensuring data persistence");
            sessionManager.saveChildSession(childDeviceId, parentName, shareKey);

            // 🛡️ BULLETPROOF: Create emergency backup of session data

            // Also save parent user ID if available
            if (sessionManager.getParentUserId() == null && getIntent().hasExtra("parentUserId")) {
                String parentUserId = getIntent().getStringExtra("parentUserId");
                if (parentUserId != null) {
                    sessionManager.saveParentUserId(parentUserId);
                }
            }
        }

        // Add logout button to settings
        addLogoutButton();

        // Show home content by default
        showMainContent();



        // Update UI with session data
        updateUI();

        // Start periodic UI refresh to keep connection status accurate
        startPeriodicUIRefresh();

        // Start timer monitoring
        setupTimerMonitoring();

        // 🎯 USAGE TRACKING: Using BulletproofUsageTracker for reliable 7-day data
        // This tracker uses UsageStatsManager.queryUsageStats() for accurate data
        // Updates every 2 minutes and stores in Firebase under usage_7day/{deviceId}
        Log.d(TAG, "🎯 Using BulletproofUsageTracker for reliable usage tracking");

        // 🛡️ BULLETPROOF: Start NON-CRITICAL services with DELAY to prevent crash
        // This staggers service initialization to reduce memory pressure
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Log.d(TAG, "Starting delayed services (Phase 1 - 2s delay)...");
                Log.d(TAG, "Phase 1 services started successfully");
            } catch (Exception e) {
                Log.e(TAG, "❌ Error starting Phase 1 services: " + e.getMessage());
            }
        }, 2000); // 2 second delay

        // Phase 2: Start remaining services after 5 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (ChildServiceCoordinator.isManagingStartup(ChildDashboardActivity.this)) {
                    Log.d(TAG, "Legacy phase 2 skipped; coordinator owns service startup");
                    return;
                }
                Log.d(TAG, "Starting delayed services (Phase 2 - 5s delay)...");

                // Start Permission Monitor Service for real-time permission change detection
                Intent permissionMonitorIntent = new Intent(ChildDashboardActivity.this,
                        online.monarchlabs.sentinel.services.PermissionMonitorService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(permissionMonitorIntent);
                } else {
                    startService(permissionMonitorIntent);
                }
                Log.d(TAG, "🔔 PermissionMonitorService started");

                // Start DailyTimerResetService
                startDailyTimerResetService();

                // Start permanent notification service (only once here)

                // Start Per-App Timer Service
                if (childDeviceId != null) {
                    online.monarchlabs.sentinel.services.AppTimerService.start(ChildDashboardActivity.this, childDeviceId);
                    Log.d(TAG, "⏱️ AppTimerService started");
                }

                // 🆕 Schedule WorkManager for reliable background usage uploads
                // This survives app kills and device reboots
                online.monarchlabs.sentinel.workers.UsageUploadScheduler.schedulePeriodicUpload(ChildDashboardActivity.this);
                Log.d(TAG, "📅 WorkManager usage upload scheduled");

                // 🆕 Sync installed apps list to Firebase for parent viewing
                if (childDeviceId != null) {
                    online.monarchlabs.sentinel.utils.InstalledAppsManager.getInstance(ChildDashboardActivity.this)
                            .syncInstalledApps(childDeviceId,
                                    new online.monarchlabs.sentinel.utils.InstalledAppsManager.OnSyncCompleteListener() {
                                        @Override
                                        public void onSuccess(int appCount) {
                                            Log.d(TAG, "📱 Synced " + appCount + " installed apps to Firebase");
                                        }

                                        @Override
                                        public void onError(String error) {
                                            Log.e(TAG, "❌ Failed to sync installed apps: " + error);
                                        }
                                    });
                }

                Log.d(TAG, "✅ Phase 2 services started successfully");
            } catch (Exception e) {
                Log.e(TAG, "❌ Error starting Phase 2 services: " + e.getMessage());
            }
        }, 5000); // 5 second delay

        // Phase 3: Start OEM checks and remaining items after 8 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Log.d(TAG, "🚀 Starting delayed services (Phase 3 - 8s delay)...");

                // Check timer permissions
                checkTimerPermissions();

                // OEM compatibility check (shows dialog if needed)
                checkOEMCompatibility();

                Log.d(TAG, "✅ Phase 3 completed - all services initialized");
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in Phase 3: " + e.getMessage());
            }
        }, 8000); // 8 second delay



        // Request optional location permission after a small delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                checkAndRequestLocationPermission();
            } catch (Exception e) {
                Log.e(TAG, "Error checking location permission: " + e.getMessage());
            }
        }, 1500);

        Log.d(TAG, "✅ ChildDashboardActivity onCreate completed - services will start in phases");
    }

    /**
     * 🛡️ Initialize automatic service protection system
     */
    private void checkOEMCompatibility() {
        try {
            Log.d(TAG, "🛡️ Initializing service watchdog...");

            // Initialize the ServiceWatchdog
            ServiceWatchdog.schedulePeriodicChecks(this);
            Log.d(TAG, "✅ Service protection initialized");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing protection: " + e.getMessage());
        }
    }

    private void listenForRemoteLogoutCommand() {
        String childDeviceId = sessionManager.getChildDeviceId();
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.e(TAG, "Cannot listen for logout command: childDeviceId is null/empty");
            return;
        }

        Log.d(TAG, "Starting logout monitoring for device: " + childDeviceId);
        try {
            online.monarchlabs.sentinel.services.PersistentConnectionService.startService(this);
        } catch (Exception e) {
            Log.w(TAG, "Could not start persistent logout monitor: " + e.getMessage());
        }

        setupV2DeviceRemovalListener(childDeviceId);
        Log.d(TAG, "v2 removal monitoring started with service backup");
    }

    private void setupV2DeviceRemovalListener(String childDeviceId) {
        DatabaseReference removalRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_removals")
                .child(childDeviceId);
        removalRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean trigger = snapshot.child("trigger").getValue(Boolean.class);
                Boolean removedByParent = snapshot.child("removed_by_parent").getValue(Boolean.class);
                if (Boolean.TRUE.equals(trigger) || Boolean.TRUE.equals(removedByParent)) {
                    ChildDisconnectionCoordinator.processRemovalMarker(
                            ChildDashboardActivity.this, snapshot, removalRef);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "v2 removal marker listener cancelled: " + error.getMessage());
            }
        });
    }



    /**
     * 🚨 ENHANCED: Listen for automatic logout signals from parent device path
     */



    /**
     * 🚨 ENHANCED: Listen for logout signals from device_status path (backup path)
     */


    /**
     * Listen for upload triggers from parent device to refresh usage data
     */
    private void listenForUploadTriggers() {
        String childDeviceId = sessionManager.getChildDeviceId();
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.w(TAG, "No child device ID available for upload trigger listener");
            return;
        }

        DatabaseReference uploadTriggerRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("commands")
                .child(childDeviceId)
                .child("usage_refresh");

        uploadTriggerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String command = snapshot.child("command").getValue(String.class);
                String status = snapshot.child("status").getValue(String.class);
                String requestedBy = snapshot.child("requestedBy").getValue(String.class);
                String reason = snapshot.child("reason").getValue(String.class);
                Long timestamp = snapshot.child("timestamp").getValue(Long.class);

                if ("refresh_usage_data".equals(command)
                        && !"processed".equals(status)
                        && "parent".equals(requestedBy)
                        && timestamp != null
                        && System.currentTimeMillis() - timestamp < 300000L) {
                    Log.d(TAG, "🔄 Received upload trigger from parent - reason: " + reason);

                    // Trigger immediate data upload via RemoteBlockService
                    triggerImmediateDataUpload();

                    // Mark the command complete so it does not repeat.
                    uploadTriggerRef.child("status").setValue("processed")
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "✅ Upload trigger command processed");
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "❌ Failed to remove upload trigger: " + e.getMessage());
                            });
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Upload trigger listener cancelled: " + error.getMessage());
            }
        });

        Log.d(TAG, "📡 Upload trigger listener initialized for device: " + childDeviceId);
    }

    /**
     * Trigger immediate data upload by sending intent to RemoteBlockService
     */
    private void triggerImmediateDataUpload() {
        Log.d(TAG, "🚀 Triggering immediate data upload...");

        try {
            // Send broadcast to RemoteBlockService to trigger immediate upload
            Intent uploadIntent = new Intent("online.monarchlabs.sentinel.TRIGGER_UPLOAD");
            uploadIntent.putExtra("reason", "parent_request");
            uploadIntent.putExtra("timestamp", System.currentTimeMillis());
            sendBroadcast(uploadIntent);

            Log.d(TAG, "✅ Upload trigger broadcast sent to RemoteBlockService");

            // Also try direct service call as backup
            Intent serviceIntent = new Intent(this, RemoteBlockService.class);
            serviceIntent.setAction("UPLOAD_USAGE_DATA");
            startService(serviceIntent);

            Log.d(TAG, "✅ Direct service upload request sent to RemoteBlockService");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error triggering data upload: " + e.getMessage());
        }
    }

    // NEW: Setup broadcast receiver for logout from RemoteBlockService
    private void setupLogoutBroadcastReceiver() {
        logoutReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("online.monarchlabs.sentinel.CHILD_LOGOUT".equals(intent.getAction())) {
                    if (intent.getBooleanExtra("go_to_get_started", false)) {
                        Intent mainIntent = new Intent(ChildDashboardActivity.this, MainActivity.class);
                        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        mainIntent.putExtra("force_login_screen", true);
                        mainIntent.putExtra("logout_message", "Device was removed by parent");
                        mainIntent.putExtra("logout_reason",
                                intent.getStringExtra("logout_reason"));
                        startActivity(mainIntent);
                    }
                    finish();
                }
            }
        };
        IntentFilter filter = new IntentFilter("online.monarchlabs.sentinel.CHILD_LOGOUT");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(logoutReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logoutReceiver, filter);
        }
    }

    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Device Removed")
                .setMessage(
                        "Your device has been removed from parental monitoring by the parent.\n\nYou will be redirected to the login screen.")
                .setPositiveButton("OK", (dialog, which) -> {
                    // Redirect to login screen
                    Intent intent = new Intent(this, ChildLoginActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void initViews() {
        tvParentName = findViewById(R.id.tvParentName);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvTodayScreenTime = findViewById(R.id.tvTodayScreenTime);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        homeContent = findViewById(R.id.homeContent);
        settingsContent = findViewById(R.id.settingsContent);
        blockedAppsCard = findViewById(R.id.blockedAppsCard);
        blockedAppsContainer = findViewById(R.id.blockedAppsContainer);
        setupBlockedAppsUi();



        // Initialize timer views
        usageLimiterCard = findViewById(R.id.usageLimiterCard);
        timerCard = usageLimiterCard;
        tvTimerDisplay = findViewById(R.id.tvTimerDisplay);
        tvTimerStatus = findViewById(R.id.tvTimerStatus);

        // Initialize usage limiter views
        usageLimiterCard = findViewById(R.id.usageLimiterCard);
        timerCard = usageLimiterCard;
        tvTimerDisplayLimiter = findViewById(R.id.tvTimerDisplay);
        tvTimerStatusLimiter = findViewById(R.id.tvTimerStatus);
        tvSelectedApps = findViewById(R.id.tvSelectedApps);
        selectedAppsInfo = findViewById(R.id.selectedAppsInfo);

        // Initialize debug buttons
        Button btnDebugSession = findViewById(R.id.btnDebugSession);
        Button btnRefreshLogout = findViewById(R.id.btnRefreshLogout);
        Button btnTestLogout = findViewById(R.id.btnTestLogout);

        // Set up debug button listeners
        if (btnDebugSession != null) {
            btnDebugSession.setOnClickListener(v -> showDebugSessionInfo());
        }

        if (btnRefreshLogout != null) {
            btnRefreshLogout.setOnClickListener(v -> refreshLogoutListener());
        }

        if (btnTestLogout != null) {
            btnTestLogout.setOnClickListener(v -> testLogoutFunction());
        }

        // Setup View Usage Data card click handler
        View cardViewUsageData = findViewById(R.id.cardViewUsageData);
        if (cardViewUsageData != null) {
            cardViewUsageData.setOnClickListener(v -> {
                Log.d(TAG, "📊 View Usage Data card clicked");
                Intent intent = new Intent(ChildDashboardActivity.this, ChildDeviceUsageActivity.class);
                startActivity(intent);
            });
            Log.d(TAG, "✅ View Usage Data card click listener set");
        } else {
            Log.w(TAG, "⚠️ cardViewUsageData not found in layout");
        }

        updateTodayScreenTime();



        if (homeContent == null) {
            Log.e(TAG, "homeContent view is null!");
        } else {
            Log.d(TAG, "homeContent view found successfully");
        }

        if (timerCard == null || tvTimerDisplay == null || tvTimerStatus == null) {
            Log.e(TAG, "Timer views not found! Check if they exist in layout");
        } else {
            Log.d(TAG, "Timer views initialized successfully");
        }

        if (usageLimiterCard == null) {
            Log.e(TAG, "Usage limiter card not found! Check if usageLimiterCard exists in layout");
        } else {
            Log.d(TAG, "Usage limiter views initialized successfully");
        }
    }

    private void setupBlockedAppsUi() {
        updateBlockedAppsSection();

        if (blockedAppsUiReceiver != null) {
            return;
        }

        blockedAppsUiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateBlockedAppsSection();
            }
        };

        IntentFilter filter = new IntentFilter("online.monarchlabs.sentinel.BLOCKED_APPS_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(blockedAppsUiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(blockedAppsUiReceiver, filter);
        }
    }

    private void updateBlockedAppsSection() {
        if (blockedAppsCard == null || blockedAppsContainer == null) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
        PackageManager packageManager = getPackageManager();
        List<BlockedAppItem> blockedApps = new ArrayList<>();

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!(entry.getValue() instanceof Boolean) || !((Boolean) entry.getValue())) {
                continue;
            }

            String packageName = entry.getKey();
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                String appName = packageManager.getApplicationLabel(appInfo).toString();
                Drawable appIcon = packageManager.getApplicationIcon(appInfo);
                blockedApps.add(new BlockedAppItem(appName, appIcon));
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Blocked package is no longer installed: " + packageName);
            }
        }

        Collections.sort(blockedApps,
                (first, second) -> first.appName.compareToIgnoreCase(second.appName));

        blockedAppsContainer.removeAllViews();
        for (BlockedAppItem blockedApp : blockedApps) {
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_child_blocked_app, blockedAppsContainer, false);
            ImageView icon = row.findViewById(R.id.imgBlockedAppIcon);
            TextView name = row.findViewById(R.id.tvBlockedAppName);
            icon.setImageDrawable(blockedApp.appIcon);
            name.setText(blockedApp.appName);
            blockedAppsContainer.addView(row);
        }

        blockedAppsCard.setVisibility(blockedApps.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static class BlockedAppItem {
        final String appName;
        final Drawable appIcon;

        BlockedAppItem(String appName, Drawable appIcon) {
            this.appName = appName;
            this.appIcon = appIcon;
        }
    }

    // Debug methods
    private void showDebugSessionInfo() {
        if (sessionManager != null) {
            String sessionInfo = sessionManager.getDetailedSessionInfo();
            Log.d(TAG, sessionInfo);

            // Show in a dialog
            new AlertDialog.Builder(this)
                    .setTitle("🔍 Session Debug Info")
                    .setMessage(sessionInfo)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy to Clipboard", (dialog, which) -> {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(
                                Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("Session Info",
                                sessionInfo);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, "Session info copied to clipboard", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } else {
            Toast.makeText(this, "SessionManager is null!", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshLogoutListener() {
        Toast.makeText(this, "🔄 Refreshing logout listener...", Toast.LENGTH_SHORT).show();

        // Send intent to RemoteBlockService to refresh logout listener
        Intent refreshIntent = new Intent(this, RemoteBlockService.class);
        refreshIntent.putExtra("action", "refresh_logout_listener");
        startService(refreshIntent);

        Toast.makeText(this, "✅ Logout listener refresh requested", Toast.LENGTH_SHORT).show();
    }

    // CRITICAL FIX: Validate connection with parent to prevent auto-reconnect after
    // removal


    private void testLogoutFunction() {
        new AlertDialog.Builder(this)
                .setTitle("🚨 Test Logout")
                .setMessage("This will test the logout functionality directly. Are you sure?")
                .setPositiveButton("Yes, Test Logout", (dialog, which) -> {
                    Log.d(TAG, "Testing logout function directly");

                    // Send broadcast to test logout
                    Intent testLogoutIntent = new Intent("online.monarchlabs.sentinel.CHILD_LOGOUT");
                    sendBroadcast(testLogoutIntent);

                    Toast.makeText(this, "🚨 Test logout broadcast sent", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 🔧 TIMER FIX: Handle Usage Access permission request result
        if (requestCode == 1001) {
            Log.d(TAG, "🔧 User returned from Usage Access settings");
            // Check if permission was granted
            new android.os.Handler().postDelayed(() -> {
                checkUsageAccessPermission();
            }, 1000); // Delay to ensure settings have been applied
            return;
        }


    }

    private void startRemoteBlockService() {
        try {
            Intent serviceIntent = new Intent(this, RemoteBlockService.class);
            startForegroundService(serviceIntent);
            Log.d(TAG, "Started RemoteBlockService for focus mode commands");
        } catch (Exception e) {
            Log.e(TAG, "Error starting RemoteBlockService: " + e.getMessage());
            Toast.makeText(this, "Error starting blocking service", Toast.LENGTH_SHORT).show();
        }
    }



    /**
     * Start the DailyTimerResetService to ensure automatic midnight resets
     * for both timers and daily usage limits work even when app is closed
     */
    private void startDailyTimerResetService() {
        try {
            Log.d(TAG, "🕛 Starting DailyTimerResetService for automatic midnight resets");
            Intent serviceIntent = new Intent(this, DailyTimerResetService.class);
            startService(serviceIntent);
            Log.d(TAG, "✅ DailyTimerResetService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start DailyTimerResetService: " + e.getMessage());
        }
    }

    /**
     * Check and request permissions needed for reliable timer functionality on
     * CHILD device
     */
    private void checkTimerPermissions() {
        try {
            Log.d(TAG, "🔋 Checking battery optimization permissions for timer service");

            // Log current permission status
            batteryOptimizationManager.logPermissionStatus();

            // Check if we need to request permissions
            batteryOptimizationManager.checkAndRequestAllPermissions(this);

            // 🔧 TIMER FIX: Check Usage Access permission - CRITICAL for timer
            // functionality
            checkUsageAccessPermission();

        } catch (Exception e) {
            Log.e(TAG, "Error checking timer permissions: " + e.getMessage());
        }
    }

    /**
     * 🔧 TIMER FIX: Check and request Usage Access permission
     * This is CRITICAL for the timer to detect which app is running
     */
    private void checkUsageAccessPermission() {
        Log.d(TAG, "🔍 Checking Usage Access permission...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(
                        Context.USAGE_STATS_SERVICE);

                boolean hasPermission = false;
                if (usm != null) {
                    long now = System.currentTimeMillis();
                    long oneMinuteAgo = now - java.util.concurrent.TimeUnit.MINUTES.toMillis(1);

                    try {
                        android.app.usage.UsageEvents events = usm.queryEvents(oneMinuteAgo, now);
                        hasPermission = (events != null && events.hasNextEvent());
                    } catch (SecurityException se) {
                        hasPermission = false;
                        Log.w(TAG, "⚠️ Usage Access permission is denied");
                    }
                }

                if (hasPermission) {
                    Log.d(TAG, "✅ Usage Access permission is GRANTED - Timer will work properly");
                } else {
                    Log.w(TAG, "❌ Usage Access permission is DENIED - Timer cannot detect running apps!");
                    showUsageAccessPermissionDialog();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error checking Usage Access permission: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "📱 Android version < 5.1 - Usage Access not required");
        }
    }

    /**
     * 🔧 TIMER FIX: Show dialog to request Usage Access permission
     */
    private void showUsageAccessPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Timer Setup Required")
                .setMessage("For the timer to work properly, this app needs access to see which apps are running.\n\n" +
                        "Please follow these steps:\n" +
                        "1. Tap 'Open Settings'\n" +
                        "2. Find and select Sentinel\n" +
                        "3. Toggle the switch to ON\n" +
                        "4. Come back to this app\n\n" +
                        "Without this permission, the timer cannot start when monitored apps are opened.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        startActivityForResult(intent, 1001);
                        Log.d(TAG, "🔧 Opened Usage Access settings");
                    } catch (Exception e) {
                        Log.e(TAG, "Error opening Usage Access settings: " + e.getMessage());
                        Toast.makeText(this, "Please go to Settings > Apps > Special access > Usage access",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    Log.w(TAG, "⚠️ User skipped Usage Access permission - Timer may not work");
                    Toast.makeText(this, "Timer may not work properly without this permission",
                            Toast.LENGTH_LONG).show();
                })
                .setCancelable(false)
                .show();
    }

    private void addLogoutButton() {
        // Logout button removed from settings layout - now shows educational content
        // for children
        // The settings page now displays helpful information about the app instead of
        // controls
        Log.d(TAG, "Settings page now shows educational content for children");
    }

    private void showManualLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect")
                .setMessage("Are you sure you want to disconnect from " + parentName
                        + "?\n\nYou will need to scan the QR code again to reconnect.")
                .setPositiveButton("Disconnect", (dialog, which) -> {
                    performLogout();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogout() {
        if (isLogoutInProgress) {
            return;
        }
        isLogoutInProgress = true;
        ChildDisconnectionCoordinator.disconnectCurrentSession(
                this, "child_requested_disconnect");
    }





    private boolean isParentRemovalStateActive() {
        Intent intent = getIntent();
        if (intent != null && (intent.getBooleanExtra("require_qr_reconnection", false)
                || intent.getBooleanExtra("device_was_removed", false))) {
            return true;
        }
        SharedPreferences state = getSharedPreferences("disconnection_state", MODE_PRIVATE);
        return state.getBoolean("device_was_removed", false)
                && state.getBoolean("require_qr_reconnection", false);
    }

    private void redirectToQrAfterParentRemoval(String source) {
        ChildDisconnectionCoordinator.disconnectCurrentSession(this, source);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChildDisconnectionCoordinator.validateCurrentOwnership(this, "dashboard_on_resume");
        Log.d(TAG, "ChildDashboardActivity resumed");
        if (isParentRemovalStateActive()) {
            Log.w(TAG, "Parent removal state active during dashboard resume - redirecting to QR scanner");
            redirectToQrAfterParentRemoval("dashboard_on_resume");
            return;
        }
        online.monarchlabs.sentinel.services.PermissionMonitorService
                .requestImmediateCheck(this);

        updateTodayScreenTime();
        updateBlockedAppsSection();

        // CRITICAL: Restore connection if it was lost during app pause/background
        restoreParentConnection();

        // Notify device status manager that app is back in foreground
        if (deviceStatusManager != null) {
            deviceStatusManager.setAppActive(true);
            Log.d(TAG, "Device marked as active");
        } else {
            Log.w(TAG, "Device status manager is null after connection restore");
        }

        // 🔧 TIMER PERSISTENCE FIX: Restore timer monitoring when app resumes
        Log.d(TAG, "🔄 App resumed - restoring timer monitoring");
        ensureBackgroundServicesRunning(); // Ensure all background services are running
        setupTimerMonitoring(); // Re-establish timer monitoring and display
        if (activeTimersListener == null) {
            setupActiveTimersListener();
        }

        // Permissions are handled in ChildPermissionsActivity - no need to ask again
        // But we should ensure RemoteBlockService is collecting usage data
        ensureUsageDataCollection();

        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            android.content.SharedPreferences timerPrefs = getSharedPreferences("app_timers_local_" + childDeviceId, MODE_PRIVATE);
            timerCachePrefsListener = (sharedPrefs, key) -> {
                runOnUiThread(this::showCachedActiveTimers);
            };
            timerPrefs.registerOnSharedPreferenceChangeListener(timerCachePrefsListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "ChildDashboardActivity paused");

        // CRITICAL: Save session data on pause to ensure persistence, except during parent removal.
        if (isLogoutInProgress || isParentRemovalStateActive()) {
            Log.d(TAG, "Skipping session save on pause because parent removal/logout is active");
        } else if (parentName != null && childDeviceId != null) {
            Log.d(TAG, "Saving session data on pause");
            sessionManager.saveChildSession(childDeviceId, parentName, shareKey);
            sessionManager.updateLastActivity();
        }

        // Notify device status manager that app is going to background
        if (deviceStatusManager != null) {
            deviceStatusManager.setAppActive(false);
        }

        if (timerCachePrefsListener != null && childDeviceId != null && !childDeviceId.isEmpty()) {
            android.content.SharedPreferences timerPrefs = getSharedPreferences("app_timers_local_" + childDeviceId, MODE_PRIVATE);
            timerPrefs.unregisterOnSharedPreferenceChangeListener(timerCachePrefsListener);
            timerCachePrefsListener = null;
        }
    }

    @Override
    protected void onStop() {
        if (activeTimersListener != null && activeTimersRef != null) {
            activeTimersRef.removeEventListener(activeTimersListener);
            activeTimersListener = null;
            activeTimersRef = null;
        }
        stopTimerMonitoring();
        super.onStop();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ChildDashboardActivity destroyed - preserving connection data");

        try {
            // Stop bulletproof usage tracking
            if (bulletproofUsageTracker != null) {
                bulletproofUsageTracker.stop();
                Log.d(TAG, "🛑 Stopped bulletproof usage tracking");
            }
            // IMPORTANT: Only stop status tracking, but DON'T clear connection data
            // This preserves the parent-child connection across app restarts
            if (deviceStatusManager != null) {
                deviceStatusManager.setAppActive(false); // Mark as inactive, but keep connection
                // DO NOT call stopStatusTracking() - this would clear the connection
                Log.d(TAG, "Device marked as inactive but connection preserved");
            }

            // OLD TRACKERS REMOVED - only BulletproofUsageTracker is used now

            // NEW: Unregister logout broadcast receiver
            if (logoutReceiver != null) {
                try {
                    unregisterReceiver(logoutReceiver);
                    Log.d(TAG, "Logout broadcast receiver unregistered");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to unregister logout receiver: " + e.getMessage());
                }
                logoutReceiver = null;
            }

            if (blockedAppsUiReceiver != null) {
                try {
                    unregisterReceiver(blockedAppsUiReceiver);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to unregister blocked apps UI receiver: " + e.getMessage());
                }
                blockedAppsUiReceiver = null;
            }

            // Clear Firebase listeners but keep connection data intact
            if (snapshotRef != null) {
                snapshotRef.removeEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                    }
                });
            }

            // Clean up usage limiter resources
            stopTimerMonitoring();
            stopLimiterCountdown();

            if (usageLimiterListener != null && usageLimiterRef != null) {
                usageLimiterRef.removeEventListener(usageLimiterListener);
            }

            if (activeTimersListener != null && activeTimersRef != null) {
                activeTimersRef.removeEventListener(activeTimersListener);
                activeTimersListener = null;
                activeTimersRef = null;
            }

            if (foregroundAppReceiver != null) {
                try {
                    unregisterReceiver(foregroundAppReceiver);
                    Log.d(TAG, "Foreground app receiver unregistered");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to unregister foreground app receiver: " + e.getMessage());
                }
            }

            Log.d(TAG, "🧹 Usage limiter resources cleaned up");
            Log.d(TAG, "✅ App destroyed but parent connection preserved for next launch");

        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage());
        }
    }



    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            String title = item.getTitle() != null ? item.getTitle().toString() : "";

            // Use title-based navigation to avoid R.id issues
            if ("Home".equals(title)) {
                showMainContent();
                return true;
            } else if ("Settings".equals(title)) {
                showSettingsContent();
                return true;
            }
            return false;
        });
    }

    private void setupSettingsClickListeners() {
        // Settings buttons removed - settings page now shows educational content for
        // children
        // No interactive elements needed as it's now informational only
        Log.d(TAG, "Settings page is now informational - no button handlers needed");
    }

    private void showMainContent() {
        // Show simple home screen
        if (homeContent != null)
            homeContent.setVisibility(View.VISIBLE);
        if (settingsContent != null)
            settingsContent.setVisibility(View.GONE);
    }

    private void showSettingsContent() {
        if (homeContent != null)
            homeContent.setVisibility(View.GONE);
        if (settingsContent != null)
            settingsContent.setVisibility(View.VISIBLE);
    }

    private void updateUI() {
        // Enhanced connection status check - verify actual connection state
        if (parentName != null) {
            // UPDATED: Show ONLY the parent name as requested by user
            // "Connected to: " prefix removed
            tvParentName.setText(parentName);
            if (tvConnectionStatus != null) {
                tvConnectionStatus.setVisibility(View.VISIBLE);
                tvConnectionStatus.setText("CONNECTED");
            }
        } else {
            // No connection data at all
            tvParentName.setText("Not Connected");
            if (tvConnectionStatus != null) {
                tvConnectionStatus.setVisibility(View.GONE);
            }
        }
    }

    private void updateTodayScreenTime() {
        if (tvTodayScreenTime == null) {
            return;
        }

        new Thread(() -> {
            try {
                online.monarchlabs.sentinel.models.SUsageDailyData todayUsage =
                        online.monarchlabs.sentinel.utils.SUsageDataManager
                                .getInstance(ChildDashboardActivity.this)
                                .getTodayUsage();
                String formattedTime = todayUsage != null
                        ? todayUsage.getShortFormattedTime()
                        : "0m";

                runOnUiThread(() -> {
                    if (!isFinishing() && tvTodayScreenTime != null) {
                        tvTodayScreenTime.setText(formattedTime);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to update today's screen time: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Check if the child device is actually connected to parent
     * This prevents false disconnection display when connection is still active
     */
    private boolean isActuallyConnected() {
        try {
            // Check multiple indicators of active connection

            // 1. Check if session data is valid and not emergency
            String deviceId = sessionManager.getChildDeviceId();
            String parentUserId = sessionManager.getParentUserId();

            if (deviceId != null && deviceId.startsWith("emergency_")) {
                Log.d(TAG, "🚨 Emergency session detected - not actually connected");
                return false;
            }

            // 2. Check if we have valid parent information
            if (parentUserId == null || parentName == null) {
                Log.d(TAG, "⚠️ Missing parent info - connection may be stale");
                return false;
            }

            // 3. Check connection state in shared preferences
            SharedPreferences connectionPrefs = getSharedPreferences("device_connection", MODE_PRIVATE);
            boolean connectionActive = connectionPrefs.getBoolean("is_connected", false);
            boolean isRealConnection = connectionPrefs.getBoolean("is_real_connection", false);
            long lastConnectionTime = connectionPrefs.getLong("connection_time", 0);
            String connectionStatus = connectionPrefs.getString("connection_status", "");

            // Consider connection stale if older than 24 hours
            long staleThreshold = 24 * 60 * 60 * 1000L; // 24 hours
            boolean isRecentConnection = (System.currentTimeMillis() - lastConnectionTime) < staleThreshold;

            if (!connectionActive || !isRealConnection || !isRecentConnection || !"active".equals(connectionStatus)) {
                Log.d(TAG, "⚠️ Connection state indicates stale or invalid connection");
                Log.d(TAG, "   Active: " + connectionActive + ", Real: " + isRealConnection +
                        ", Recent: " + isRecentConnection + ", Status: " + connectionStatus);
                return false;
            }

            // 4. Additional check: verify we're not showing emergency parent name
            if (parentName.contains("Emergency") || parentName.contains("Reconnection Needed") ||
                    parentName.contains("Data Recovery")) {
                Log.d(TAG, "⚠️ Emergency/recovery parent name detected");
                return false;
            }

            Log.d(TAG, "✅ Connection appears to be active and valid");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error checking connection status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Start periodic UI refresh to keep connection status accurate
     * This prevents the interface from showing stale disconnection status
     */
    private void startPeriodicUIRefresh() {
        Handler uiRefreshHandler = new Handler(Looper.getMainLooper());

        Runnable uiRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                // Refresh UI every 30 seconds to keep status accurate
                updateUI();

                // Schedule next refresh
                uiRefreshHandler.postDelayed(this, 30000); // 30 seconds
            }
        };

        // Start the periodic refresh
        uiRefreshHandler.postDelayed(uiRefreshRunnable, 30000);

        Log.d(TAG, "🔄 Started periodic UI refresh (every 30 seconds) to maintain accurate connection status");
    }

    /**
     * Verify and restore connection when status appears stale
     */




    private void setupTimerMonitoring() {
        Log.d(TAG, "Dashboard active_timers listener disabled; v2 app timers drive the UI");
    }

    private boolean isTimerHiddenByParent(java.util.Map<String, Object> timerData) {
        long now = System.currentTimeMillis();
        Long hiddenUntil = getTimerPayloadLong(timerData, "parentClearedUntil");
        if (hiddenUntil == null) {
            hiddenUntil = getTimerPayloadLong(timerData, "hiddenUntil");
        }
        if (hiddenUntil != null && hiddenUntil > now) {
            return true;
        }

        Long clearedAt = getTimerPayloadLong(timerData, "parentClearedAt");
        if (clearedAt == null) {
            clearedAt = getTimerPayloadLong(timerData, "clearedAt");
        }
        return clearedAt != null && now - clearedAt < 5 * 60 * 1000L;
    }

    private Long getTimerPayloadLong(java.util.Map<String, Object> timerData, String key) {
        if (timerData == null || key == null) {
            return null;
        }
        Object value = timerData.get(key);
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        if (value instanceof Double) {
            return ((Double) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void stopTimerMonitoring() {
        if (dashboardTimerRef != null && dashboardTimerListener != null) {
            dashboardTimerRef.removeEventListener(dashboardTimerListener);
            Log.d(TAG, "Dashboard timer listener removed");
        }
        dashboardTimerRef = null;
        dashboardTimerListener = null;
    }



    private void updateTimerDisplay(java.util.Map<String, Object> timerData) {
        try {
            Long remainingTime = (Long) timerData.get("remainingTime");
            Boolean isTimerRunning = (Boolean) timerData.get("isTimerRunning");
            Boolean isTimerActive = (Boolean) timerData.get("isTimerActive");
            String currentMonitoredApp = (String) timerData.get("currentMonitoredApp");

            if (remainingTime == null || isTimerRunning == null || isTimerActive == null) {
                Log.e(TAG, "Invalid timer data received from Firebase. Hiding timer.");
                hideTimerDisplay();
                return;
            }

            boolean shouldShowTimerCard = Boolean.TRUE.equals(isTimerActive) && remainingTime > 0;

            // Update timer card visibility only if it changes
            if (timerCard != null) {
                int newVisibility = shouldShowTimerCard ? android.view.View.VISIBLE : android.view.View.GONE;
                if (timerCard.getVisibility() != newVisibility) {
                    timerCard.setVisibility(newVisibility);
                    Log.d(TAG, "Timer card visibility changed to: " + (shouldShowTimerCard ? "VISIBLE" : "GONE"));
                }
            }

            if (!shouldShowTimerCard) {
                // Timer is not active or has expired
                String expiredTimeText = "00:00:00";
                if (!lastDisplayedTime.equals(expiredTimeText)) {
                    tvTimerDisplay.setText(expiredTimeText);
                    tvTimerDisplay.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
                    lastDisplayedTime = expiredTimeText;
                }
                String noTimerStatus = "No timer active";
                if (!lastDisplayedStatus.equals(noTimerStatus)) {
                    tvTimerStatus.setText(noTimerStatus);
                    tvTimerStatus.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
                    lastDisplayedStatus = noTimerStatus;
                }
                return;
            }

            // Update timer display
            String timeText = formatTime(remainingTime);
            if (!lastDisplayedTime.equals(timeText)) {
                tvTimerDisplay.setText(timeText);
                lastDisplayedTime = timeText;

                // Change color based on time left
                if (remainingTime <= 300000) { // 5 minutes
                    tvTimerDisplay.setTextColor(android.graphics.Color.parseColor("#FF9800")); // Orange
                } else {
                    tvTimerDisplay.setTextColor(android.graphics.Color.parseColor("#4CAF50")); // Green
                }
            }

            // Update status
            String newStatusText;
            int newStatusColor;

            if (Boolean.TRUE.equals(isTimerRunning)) {
                newStatusText = "Monitoring " + (currentMonitoredApp != null ? currentMonitoredApp : "...");
                newStatusColor = android.graphics.Color.parseColor("#4CAF50");
            } else { // Timer active but paused
                newStatusText = "Paused: " + (currentMonitoredApp != null ? currentMonitoredApp : "...")
                        + " not in foreground";
                newStatusColor = android.graphics.Color.parseColor("#FF9800");
            }

            if (!lastDisplayedStatus.equals(newStatusText)) {
                tvTimerStatus.setText(newStatusText);
                tvTimerStatus.setTextColor(newStatusColor);
                lastDisplayedStatus = newStatusText;
            }

            Log.d(TAG, "Timer updated: " + timeText + " - Active: " + isTimerActive + " - Running: " + isTimerRunning);

        } catch (Exception e) {
            Log.e(TAG, "Error updating timer display: " + e.getMessage());
            hideTimerDisplay(); // Hide on error
        }
    }

    private void hideTimerDisplay() {
        if (timerCard != null) {
            if (timerCard.getVisibility() != android.view.View.GONE) {
                timerCard.setVisibility(android.view.View.GONE);
                Log.d(TAG, "Timer display hidden - no active timer");
            }
        }
        // Reset last displayed values when hidden
        lastDisplayedTime = "";
        lastDisplayedStatus = "";
    }

    private String formatTime(long milliseconds) {
        if (milliseconds <= 0)
            return "00:00:00";

        long seconds = milliseconds / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        return String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs);
    }

    private void ensureUsageDataCollection() {
        // Check if usage access permission is granted
        android.app.AppOpsManager appOps = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), getPackageName());
        boolean hasUsageAccess = (mode == android.app.AppOpsManager.MODE_ALLOWED);

        if (hasUsageAccess) {
            Log.d(TAG, "✅ Usage access permission granted - RemoteBlockService should be collecting data");
            // Send a refresh signal to RemoteBlockService to ensure it's collecting data
            Intent refreshIntent = new Intent(this, RemoteBlockService.class);
            refreshIntent.putExtra("action", "refresh_usage_collection");
            startService(refreshIntent);
        } else {
            Log.w(TAG, "❌ Usage access permission not granted - no usage data will be collected");
            Log.w(TAG, "Please grant Usage Access permission in Settings to see usage data on parent dashboard");
        }
    }


    /**
     * Start Real-Time Data Sync Service for QR device pairing system
     */


    /**
     * Restore persistent connection to parent device
     * This method ensures connection survives app restarts
     */
    private void restoreParentConnection() {
        ChildDisconnectionCoordinator.validateCurrentOwnership(
                this, "dashboard_restore");
    }

    /**
     * 🛡️ BULLETPROOF RECOVERY METHODS
     * These methods ensure the child app NEVER logs out automatically
     */

    /**
     * Create emergency session backup to prevent data loss
     */


    /**
     * Try to recover session from intent data or shared preferences
     */


    /**
     * Create an emergency session to prevent logout - IMPROVED VERSION
     * This creates a minimal session that won't interfere with real connections
     */


    /**
     * Check if there's a recent real (non-emergency) connection
     */


    /**
     * Recover missing connection data without logging out
     */


    /**
     * 🔧 PRODUCTION STABILITY: Ensures all background services are running properly
     * This method is called when the app resumes to guarantee persistence
     */
    private void ensureBackgroundServicesRunning() {
        try {
            Log.d(TAG, "🔧 Ensuring all background services are running...");



            // DISABLED: Don't automatically start SmartTimerService - let parent control
            // timers
            // try {
            // Intent smartTimerIntent = new Intent(this, SmartTimerService.class);
            // startService(smartTimerIntent);
            // Log.d(TAG, "✅ SmartTimerService started/verified");
            // } catch (Exception e) {
            // Log.d(TAG, "SmartTimerService start attempt: " + e.getMessage());
            // }

            // Ensure RemoteBlockService is running
            try {
                Intent blockServiceIntent = new Intent(this, RemoteBlockService.class);
                ChildServiceCoordinator.ensureCriticalService(this, "dashboard_resume");
                Log.d(TAG, "✅ RemoteBlockService started/verified");
            } catch (Exception e) {
                Log.d(TAG, "RemoteBlockService start attempt: " + e.getMessage());
            }

            Log.d(TAG, "✅ All background services verification completed");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error ensuring background services: " + e.getMessage());
        }
    }

    // ================================
    // USAGE LIMITER FUNCTIONALITY
    // ================================

    /**
     * Initialize usage limiter functionality with UI setup and Firebase listeners
     */
    private void initializeUsageLimiter() {
        // App limits are rendered from canonical v2 timer policies.
    }

    /**
     * Start monitoring usage limiter data from Firebase for UI updates only
     * (Timer logic is handled by EnhancedUsageLimiterService)
     */
    private void startLimiterUIMonitoring() {
        try {
            Log.d(TAG, "📡 Starting usage limiter Firebase monitoring...");

            if (usageLimiterRef == null) {
                Log.e(TAG, "❌ Cannot start monitoring: usageLimiterRef is null");
                return;
            }

            // Remove any existing listener
            if (usageLimiterListener != null) {
                usageLimiterRef.removeEventListener(usageLimiterListener);
            }

            usageLimiterListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    try {
                        Log.d(TAG, "📊 Usage limiter data received from Firebase for device: " + childDeviceId);
                        Log.d(TAG, "📊 DataSnapshot exists: " + dataSnapshot.exists());

                        if (dataSnapshot.exists()) {
                            // Parse limiter data
                            Map<String, Object> limiterData = (Map<String, Object>) dataSnapshot.getValue();
                            if (limiterData != null) {
                                Log.d(TAG, "📊 Limiter data keys: " + limiterData.keySet());
                                Log.d(TAG, "📊 isActive: " + limiterData.get("isActive"));
                                Log.d(TAG, "📊 remainingTimeMs: " + limiterData.get("remainingTimeMs"));
                                Log.d(TAG, "📊 selectedApps: " + limiterData.get("selectedApps"));
                                processLimiterData(limiterData);
                            } else {
                                Log.w(TAG, "📭 Limiter data is null even though snapshot exists");
                                hideUsageLimiterCard();
                            }
                        } else {
                            Log.d(TAG, "📭 No usage limiter data found for device " + childDeviceId
                                    + " - hiding limiter card");
                            hideUsageLimiterCard();
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error processing usage limiter data: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "❌ Usage limiter monitoring cancelled: " + databaseError.getMessage());
                }
            };

            // Attach listener
            usageLimiterRef.addValueEventListener(usageLimiterListener);
            Log.d(TAG, "✅ Usage limiter Firebase monitoring started successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting limiter monitoring: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Process usage limiter data received from Firebase
     */
    private void processLimiterData(Map<String, Object> limiterData) {
        try {
            // CRITICAL: Prevent recursive processing during our own Firebase updates
            if (isUpdatingFirebase) {
                Log.d(TAG, "🔄 Skipping Firebase data processing - we're updating Firebase ourselves");
                return;
            }

            Log.d(TAG, "🔄 Processing usage limiter data...");

            // Extract data fields
            Boolean isActive = (Boolean) limiterData.get("isActive");
            Long remainingTimeMsData = (Long) limiterData.get("remainingTimeMs");
            List<String> selectedApps = (List<String>) limiterData.get("selectedApps");

            Log.d(TAG, "🔄 Timer data - isActive: " + isActive + ", remainingTimeMs: " + remainingTimeMsData);
            Log.d(TAG, "🔄 Selected apps: " + selectedApps);

            // Validate required fields
            if (isActive == null || remainingTimeMsData == null) {
                Log.w(TAG, "⚠️ Invalid limiter data - missing required fields (isActive: " + isActive
                        + ", remainingTimeMs: " + remainingTimeMsData + ")");
                hideUsageLimiterCard();
                return;
            }

            // For testing: ALWAYS show timer if data exists, ignore date/day restrictions
            Log.d(TAG, "� Timer data is valid - showing timer regardless of date restrictions for testing");

            // Update local state
            isLimiterActive = Boolean.TRUE.equals(isActive);
            remainingTimeMs = remainingTimeMsData;
            limitedApps = selectedApps != null ? selectedApps : new ArrayList<>();

            Log.d(TAG, "📋 Limiter state - Active: " + isLimiterActive +
                    ", Remaining: " + formatTime(remainingTimeMs) +
                    ", Limited apps count: " + limitedApps.size());

            if (limitedApps != null && !limitedApps.isEmpty()) {
                Log.d(TAG, "📱 Limited apps: " + limitedApps.toString());
            } else {
                Log.w(TAG, "⚠️ No limited apps found! selectedApps from Firebase was: " + selectedApps);
            }

            // Update UI - timer logic is handled by EnhancedUsageLimiterService
            updateLimiterUI();

            Log.d(TAG, "✅ UI updated - timer logic handled by EnhancedUsageLimiterService");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error processing limiter data: " + e.getMessage());
            e.printStackTrace();
            hideUsageLimiterCard();
        }
    }

    /**
     * Check if usage limiter is active for today (old day-based system)
     */
    private boolean isLimiterActiveForToday(List<String> activeDays) {
        if (activeDays == null || activeDays.isEmpty()) {
            return false;
        }

        // Get current day of week
        Calendar calendar = Calendar.getInstance();
        String[] daysOfWeek = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
        String today = daysOfWeek[calendar.get(Calendar.DAY_OF_WEEK) - 1];

        boolean isActiveToday = activeDays.contains(today);
        Log.d(TAG, "📅 Today: " + today + ", Active days: " + activeDays + ", Active today: " + isActiveToday);

        return isActiveToday;
    }

    /**
     * Check if usage limiter is active for today (new date-based system)
     */
    private boolean isLimiterActiveForDateRange(String startDateStr, String endDateStr) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Date startDate = dateFormat.parse(startDateStr);
            Date endDate = dateFormat.parse(endDateStr);
            Date today = new Date();

            // Normalize today's date to compare only date part (not time)
            Calendar todayCalendar = Calendar.getInstance();
            todayCalendar.setTime(today);
            todayCalendar.set(Calendar.HOUR_OF_DAY, 0);
            todayCalendar.set(Calendar.MINUTE, 0);
            todayCalendar.set(Calendar.SECOND, 0);
            todayCalendar.set(Calendar.MILLISECOND, 0);
            Date todayNormalized = todayCalendar.getTime();

            // Check if today is within the date range (inclusive)
            boolean isInRange = (todayNormalized.equals(startDate) || todayNormalized.after(startDate)) &&
                    (todayNormalized.equals(endDate) || todayNormalized.before(endDate));

            Log.d(TAG, "📅 Date range check - Start: " + startDateStr + ", End: " + endDateStr +
                    ", Today: " + dateFormat.format(todayNormalized) + ", In range: " + isInRange);

            return isInRange;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error parsing date range: " + e.getMessage());
            return false;
        }
    }

    /**
     * Update the usage limiter UI with current status and time
     */
    private void updateLimiterUI() {
        // Daily Limit card permanently hidden on child dashboard —
        // per-app timers (activeTimersCard) cover this more accurately.
        hideUsageLimiterCard();
    }

    /**
     * Update the selected apps display section
     */
    private void updateSelectedAppsDisplay() {
        try {
            if (selectedAppsInfo == null || tvSelectedApps == null)
                return;

            if (limitedApps != null && !limitedApps.isEmpty()) {
                selectedAppsInfo.setVisibility(View.VISIBLE);

                StringBuilder appsText = new StringBuilder();
                for (int i = 0; i < limitedApps.size() && i < 5; i++) { // Show max 5 apps
                    String appName = getCurrentAppName(limitedApps.get(i));
                    if (i > 0)
                        appsText.append(", ");
                    appsText.append(appName);
                }

                if (limitedApps.size() > 5) {
                    appsText.append(" and ").append(limitedApps.size() - 5).append(" more");
                }

                tvSelectedApps.setText(appsText.toString());

            } else {
                selectedAppsInfo.setVisibility(View.GONE);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating selected apps display: " + e.getMessage());
        }
    }

    /**
     * Hide the usage limiter card
     */
    private void hideUsageLimiterCard() {
        runOnUiThread(() -> {
            if (usageLimiterCard != null) {
                usageLimiterCard.setVisibility(View.GONE);
                Log.d(TAG, "📱 Usage limiter card hidden");
            }

            // Stop countdown if running
            stopLimiterCountdown();

            // Hide timer notification
            hideTimerNotification();

            // Reset state
            isLimiterActive = false;
            remainingTimeMs = 0;
            isTimerCountingDown = false;
        });
    }

    /**
     * Start the limiter countdown timer - Only counts down when selected apps are
     * in foreground
     */
    private void startLimiterCountdown() {
        try {
            Log.d(TAG, "🔄 startLimiterCountdown() called - checking if timer already running...");

            // CRITICAL: Always stop existing countdown first to prevent multiple timers
            stopLimiterCountdown();

            if (!isLimiterActive || remainingTimeMs <= 0) {
                Log.d(TAG, "⏹️ Not starting countdown - limiter inactive or no time remaining");
                return;
            }

            // Double-check no timer is already running
            if (isTimerCountingDown) {
                Log.w(TAG, "⚠️ Timer already counting down! Stopping existing timer first...");
                stopLimiterCountdown();
            }

            Log.d(TAG, "⏱️ Starting HIGH-PRECISION usage limiter countdown (real-time accuracy)...");
            isTimerCountingDown = true;
            lastTimerUpdateMs = System.currentTimeMillis(); // Initialize precise timing
            lastFirebaseUpdateMs = System.currentTimeMillis();

            // Initialize handler if needed
            if (limiterUpdateHandler == null) {
                limiterUpdateHandler = new Handler(Looper.getMainLooper());
            }

            limiterUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isTimerCountingDown || !isLimiterActive) {
                        Log.d(TAG, "⏹️ Timer stopped - isTimerCountingDown: " + isTimerCountingDown
                                + ", isLimiterActive: " + isLimiterActive);
                        return;
                    }

                    try {
                        long currentTimeMs = System.currentTimeMillis();
                        long elapsedMs = currentTimeMs - lastTimerUpdateMs;

                        // Get current foreground app directly (integrated monitoring)
                        String detectedApp = getCurrentForegroundApp();
                        if (detectedApp != null && !detectedApp.equals(currentForegroundApp)) {
                            String previousApp = currentForegroundApp;
                            currentForegroundApp = detectedApp;
                            Log.d(TAG, "📱 App change detected: " + previousApp + " → " + currentForegroundApp);
                        }

                        // Check if current app is in the limited apps list
                        boolean isLimited = isCurrentAppLimited();

                        // Only count down when a limited app is active
                        if (isLimited) {
                            long beforeTime = remainingTimeMs;

                            // HIGH-PRECISION countdown: subtract the EXACT elapsed time
                            remainingTimeMs -= elapsedMs;

                            long afterTime = remainingTimeMs;

                            Log.d(TAG,
                                    "⏳ PRECISION TIMING: " + formatTimePrecise(beforeTime) + " → "
                                            + formatTimePrecise(afterTime) +
                                            " (elapsed: " + elapsedMs + "ms, app: " + currentForegroundApp + ")");

                            // Update Firebase periodically (not every 100ms to avoid spam)
                            if (currentTimeMs - lastFirebaseUpdateMs >= 1000) { // Update Firebase every 1 second
                                updateFirebaseRemainingTime();
                                lastFirebaseUpdateMs = currentTimeMs;
                            }

                            // Check if time is up
                            if (remainingTimeMs <= 0) {
                                Log.d(TAG, "⏰ PRECISION TIMER COMPLETED!");
                                remainingTimeMs = 0; // Don't go negative
                                handleLimiterTimeUp();
                                return; // Stop the timer
                            }
                        } else {
                            Log.v(TAG, "⏸️ TIMER PAUSED - App not limited: " + currentForegroundApp + " (elapsed: "
                                    + elapsedMs + "ms)");
                        }

                        // Update timestamp for next calculation
                        lastTimerUpdateMs = currentTimeMs;

                        // Update UI and notification (less frequently to save battery)
                        updateLimiterUI();
                        updateTimerNotification();

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error in precision timer: " + e.getMessage());
                    }

                    // Schedule next check in 100ms for high precision (10x more accurate)
                    if (isTimerCountingDown && isLimiterActive) {
                        limiterUpdateHandler.postDelayed(this, 100);
                    } else {
                        Log.d(TAG, "⏹️ Precision timer loop ended");
                    }
                }
            };

            // Start the single integrated timer
            limiterUpdateHandler.post(limiterUpdateRunnable);
            Log.d(TAG, "✅ HIGH-PRECISION timer started (100ms intervals for real-time accuracy)");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting precision limiter countdown: " + e.getMessage());
        }
    }

    /**
     * Stop ALL timer-related activities to prevent multiple countdown issues
     */
    private void stopLimiterCountdown() {
        try {
            Log.d(TAG, "🛑 STOPPING ALL TIMERS to prevent double-counting...");

            // Stop main countdown timer
            if (limiterUpdateRunnable != null && limiterUpdateHandler != null) {
                limiterUpdateHandler.removeCallbacks(limiterUpdateRunnable);
                Log.d(TAG, "⏹️ Main countdown timer stopped");
            }

            // Stop ANY remaining foreground monitoring timer
            if (foregroundAppRunnable != null && foregroundAppHandler != null) {
                foregroundAppHandler.removeCallbacks(foregroundAppRunnable);
                Log.d(TAG, "⏹️ Foreground monitoring timer stopped");
            }

            // Clear all handlers to prevent memory leaks
            limiterUpdateRunnable = null;
            foregroundAppRunnable = null;

            // Mark timer as not counting
            isTimerCountingDown = false;

            Log.d(TAG, "✅ ALL timer components completely stopped and cleared");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error stopping timers: " + e.getMessage());
            // Force stop anyway
            isTimerCountingDown = false;
        }
    }

    /**
     * Handle when limiter time is up - UPDATED: Show permanent notification instead
     * of blocking
     */
    private void handleLimiterTimeUp() {
        try {
            Log.d(TAG, "⏰ Usage limiter time is up!");

            // Stop countdown
            stopLimiterCountdown();

            // Update UI to show expired state
            remainingTimeMs = 0;
            updateLimiterUI();

            // 🔔 SHOW PERMANENT NOTIFICATION instead of blocking apps
            showPermanentLimiterExpiredNotification();

            // Update Firebase to mark timer as expired
            if (usageLimiterRef != null) {
                usageLimiterRef.child("remainingTimeMs").setValue(0);
                usageLimiterRef.child("isActive").setValue(false);
                usageLimiterRef.child("showPermanentNotification").setValue(true);
            }

            Log.d(TAG, "✅ Timer expired - Apps remain accessible with permanent notification");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error handling limiter time up: " + e.getMessage());
        }
    }

    /**
     * Get current foreground app using UsageStatsManager
     */
    private String getCurrentForegroundApp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                if (usageStatsManager == null)
                    return null;

                long currentTime = System.currentTimeMillis();
                long startTime = currentTime - 3000; // Last 3 seconds

                // Use simpler approach with UsageStats instead of UsageEvents
                List<UsageStats> usageStatsList = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        startTime,
                        currentTime);

                if (usageStatsList != null && !usageStatsList.isEmpty()) {
                    // Find the most recently used app
                    UsageStats recentApp = null;
                    for (UsageStats usageStats : usageStatsList) {
                        if (recentApp == null || usageStats.getLastTimeUsed() > recentApp.getLastTimeUsed()) {
                            recentApp = usageStats;
                        }
                    }

                    if (recentApp != null) {
                        return recentApp.getPackageName();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error getting current foreground app: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if the current foreground app is in the limited apps list
     */
    private boolean isCurrentAppLimited() {
        boolean isLimited = isAppLimited(currentForegroundApp);
        if (isLimiterActive) {
            Log.d(TAG,
                    "🔍 Checking if current app is limited - App: " + currentForegroundApp + ", Limited: " + isLimited);
        }
        return isLimited;
    }

    /**
     * Check if a specific app is in the limited apps list
     */
    private boolean isAppLimited(String packageName) {
        if (packageName == null || packageName.isEmpty() || limitedApps == null) {
            return false;
        }

        // Don't limit system apps and our own app
        if (packageName.equals(getPackageName()) ||
                packageName.startsWith("com.android") ||
                packageName.contains("launcher") ||
                packageName.contains("systemui") ||
                packageName.equals("android")) {
            return false;
        }

        boolean isLimited = limitedApps.contains(packageName);
        if (isLimited) {
            Log.v(TAG, "✅ App is limited: " + packageName);
        }

        return isLimited;
    }

    /**
     * 🔔 Show permanent notification when usage limiter expires
     * This notification cannot be dismissed and persists until timer resets
     */
    private void showPermanentLimiterExpiredNotification() {
        try {
            Log.d(TAG, "🔔 Creating permanent usage limiter expired notification...");

            Intent intent = new Intent(this, ChildDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Create notification channel for permanent notifications
            createPermanentLimiterNotificationChannel();

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "permanent_limiter_channel")
                    .setContentTitle("⏰ Usage Limit Reached")
                    .setContentText("Daily usage limit reached. Apps remain accessible.")
                    .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(false) // CRITICAL: Cannot be dismissed
                    .setOngoing(true) // CRITICAL: Persistent notification
                    .setColor(Color.parseColor("#FF5722")) // Deep orange for attention
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("Your daily usage limit has been reached.\n\n" +
                                    "Apps remain accessible, but please be mindful of your screen time.\n\n" +
                                    "This reminder will remain until your parent clears the timer."))
                    .addAction(android.R.drawable.ic_menu_view, "View Dashboard", pendingIntent);

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                // Use unique notification ID for limiter notifications
                int limiterNotificationId = 8888;
                manager.notify(limiterNotificationId, builder.build());

                // Save notification state for persistence
                SharedPreferences notificationPrefs = getSharedPreferences("permanent_limiter_notifications",
                        MODE_PRIVATE);
                notificationPrefs.edit()
                        .putBoolean("limiter_expired_notification_active", true)
                        .putLong("notification_created_time", System.currentTimeMillis())
                        .putString("device_id", childDeviceId)
                        .apply();

                Log.d(TAG, "✅ Permanent usage limiter notification created");

                // Show a brief toast to inform user
                Toast.makeText(this, "⏰ Daily usage limit reached - Apps remain accessible", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error creating permanent limiter notification: " + e.getMessage());
        }
    }

    /**
     * Create notification channel for permanent usage limiter notifications
     */
    private void createPermanentLimiterNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "permanent_limiter_channel",
                    "Usage Limit Notifications",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(
                    "Permanent notifications when daily usage limits are reached - Only removable by parent");
            channel.enableLights(true);
            channel.setLightColor(Color.parseColor("#FF5722"));
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(true);
            channel.setBypassDnd(false); // Respect Do Not Disturb

            // Make the notification channel non-blockable by user (requires system
            // permission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                channel.setBlockable(false);
            }

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "✅ Permanent limiter notification channel created (non-removable)");
            }
        }
    }

    // ================================
    // � PERMANENT NOTIFICATION SERVICE INTEGRATION
    // ================================

    /**
     * Start permanent notification service for expired timer notifications
     */
    /**
     * Handle app blocking when time is up or app usage exceeds limit
     * 🔔 UPDATED: No longer blocks apps - shows notification instead
     */
    private void handleAppBlocking(String packageName) {
        // 🔔 APP BLOCKING DISABLED - Using permanent notification instead
        Log.d(TAG, "🔔 App blocking DISABLED for: " + packageName + " - Using notification reminder instead");

        // Just show a brief reminder toast
        String appName = getCurrentAppName(packageName);
        Toast.makeText(this, "📱 Reminder: Daily limit reached for " + appName, Toast.LENGTH_SHORT).show();

        /*
         * ORIGINAL BLOCKING CODE - DISABLED
         * try {
         * Log.d(TAG, "🚫 Blocking limited app: " + packageName);
         *
         * // Go to home screen
         * Intent homeIntent = new Intent(Intent.ACTION_MAIN);
         * homeIntent.addCategory(Intent.CATEGORY_HOME);
         * homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         * startActivity(homeIntent);
         *
         * // Show blocking notification
         * String appName = getCurrentAppName(packageName);
         * Toast.makeText(this, "🚫 Time limit reached for " + appName,
         * Toast.LENGTH_LONG).show();
         *
         * Log.d(TAG, "✅ App blocked successfully: " + appName);
         *
         * } catch (Exception e) {
         * Log.e(TAG, "❌ Error blocking app: " + e.getMessage());
         * }
         */
    }

    /**
     * Show dialog when timer expires - UPDATED: Apps remain accessible
     */
    private void showTimeUpDialog() {
        try {
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setTitle("⏰ Daily Limit Reached")
                        .setMessage("Your daily usage limit has been reached.\n\n" +
                                "Apps remain accessible, but please be mindful of your screen time.\n\n" +
                                "A reminder notification will stay visible until the timer resets at midnight.")
                        .setPositiveButton("OK", (dialog, which) -> {
                            dialog.dismiss();
                        })
                        .setCancelable(false)
                        .show();
            });

        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing time up dialog: " + e.getMessage());
        }
    }

    /**
     * Start BlockService for foreground app monitoring (now integrated into main
     * timer)
     */
    private void startBlockService() {
        try {
            // Note: Foreground app monitoring is now integrated into the main countdown
            // timer
            // to prevent double-counting and ensure precise 1-second intervals
            Log.d(TAG, "✅ BlockService integration noted - using integrated monitoring");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error in BlockService setup: " + e.getMessage());
        }
    }

    /**
     * Start direct foreground app monitoring using UsageStats
     * NOTE: This is now DISABLED - monitoring is integrated into main timer to
     * prevent double-counting
     */
    private void startForegroundAppMonitoring() {
        Log.d(TAG, "⚠️ Separate foreground monitoring is DISABLED - using integrated timer monitoring only");
        // This method is intentionally empty to prevent duplicate timers
        // All monitoring is now handled in startLimiterCountdown()
    }

    /**
     * Setup foreground app monitoring using broadcast receiver
     */
    private void setupForegroundAppMonitoring() {
        try {
            Log.d(TAG, "📡 Setting up foreground app monitoring...");

            if (foregroundAppReceiver != null) {
                try {
                    unregisterReceiver(foregroundAppReceiver);
                } catch (Exception e) {
                    // Receiver might not have been registered
                }
            }

            foregroundAppReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("online.monarchlabs.sentinel.APP_FOREGROUND".equals(intent.getAction())) {
                        String packageName = intent.getStringExtra("package_name");
                        Log.d(TAG, "📱 Foreground app broadcast received: " + packageName);

                        if (packageName != null && !packageName.equals(currentForegroundApp)) {
                            String previousApp = currentForegroundApp;
                            currentForegroundApp = packageName;
                            Log.d(TAG, "📱 Foreground app changed: " + previousApp + " → " + packageName);

                            // If limiter is active, check if we need to handle this app
                            if (isLimiterActive) {
                                boolean isLimited = isAppLimited(packageName);
                                Log.d(TAG, "⏱️ Timer active - App limited: " + isLimited + ", Time remaining: "
                                        + formatTime(remainingTimeMs));

                                // If time is up and user opened a limited app, block it
                                if (remainingTimeMs <= 0 && isLimited) {
                                    Log.d(TAG, "🚫 Time is up and limited app opened - blocking: " + packageName);
                                    handleAppBlocking(packageName);
                                }
                            }
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter("online.monarchlabs.sentinel.APP_FOREGROUND");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(foregroundAppReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(foregroundAppReceiver, filter);
            }

            Log.d(TAG, "✅ Foreground app monitoring setup completed");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error setting up foreground app monitoring: " + e.getMessage());
        }
    }

    /**
     * Update remaining time in Firebase (prevents recursive listener calls)
     */
    private void updateFirebaseRemainingTime() {
        try {
            if (usageLimiterRef != null && remainingTimeMs >= 0) {
                isUpdatingFirebase = true; // Prevent recursive processing

                usageLimiterRef.child("remainingTimeMs").setValue(remainingTimeMs)
                        .addOnCompleteListener(task -> {
                            isUpdatingFirebase = false; // Reset flag when complete
                            if (task.isSuccessful()) {
                                Log.v(TAG, "✅ Firebase time updated: " + formatTime(remainingTimeMs));
                            } else {
                                Log.e(TAG, "❌ Failed to update Firebase time: " + task.getException());
                            }
                        });
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating Firebase remaining time: " + e.getMessage());
            isUpdatingFirebase = false; // Reset flag on error
        }
    }

    /**
     * Get app name from package name
     */
    private String getCurrentAppName(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty()) {
                return "Unknown App";
            }
            if ("online.monarchlabs.sentinel".equals(packageName) || "online_monarchlabs_sentinel".equals(packageName)) {
                return "Sentinel";
            }

            PackageManager packageManager = getPackageManager();
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            String appName = (String) packageManager.getApplicationLabel(appInfo);
            return appName != null ? appName : packageName;

        } catch (Exception e) {
            // Return package name if we can't get the app name
            return packageName;
        }
    }

    // Timer Notification Management
    private static final String TIMER_NOTIFICATION_CHANNEL_ID = "TimerCountdownChannel";
    private static final int TIMER_NOTIFICATION_ID = 1001;
    private NotificationManager notificationManager;

    /**
     * Create notification channel for timer notifications
     */
    private void createTimerNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    TIMER_NOTIFICATION_CHANNEL_ID,
                    "App Timer Countdown",
                    NotificationManager.IMPORTANCE_DEFAULT // Changed from LOW to DEFAULT for better visibility
            );
            channel.setDescription("Shows remaining time for app usage limit and current status");
            channel.setShowBadge(true); // Show badge
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); // Show on lockscreen

            if (notificationManager == null) {
                notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            }

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "✅ Timer notification channel created with DEFAULT importance");
            }
        }
    }

    /**
     * Show persistent timer notification
     */
    private void showTimerNotification() {
        try {
            if (notificationManager == null) {
                notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            }

            createTimerNotificationChannel();

            Intent notificationIntent = new Intent(this, ChildDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String timeText = formatTime(remainingTimeMs);
            String title = "⏱️ App Timer: " + timeText;

            // Create detailed content text
            StringBuilder contentBuilder = new StringBuilder();

            // Show current status
            boolean isLimited = isCurrentAppLimited();
            if (isLimited) {
                contentBuilder.append("🔴 ACTIVE - Timer counting down");
            } else {
                contentBuilder.append("⏸️ PAUSED - Switch to limited app to continue");
            }

            // Show limited apps count
            if (limitedApps != null && !limitedApps.isEmpty()) {
                contentBuilder.append(" • ").append(limitedApps.size()).append(" apps limited");
            }

            // Show current app if it's limited
            if (isLimited && currentForegroundApp != null) {
                String appName = getCurrentAppName(currentForegroundApp);
                contentBuilder.append(" • Current: ").append(appName);
            }

            String contentText = contentBuilder.toString();

            Notification notification = new NotificationCompat.Builder(this, TIMER_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(contentText)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true) // Makes it non-removable
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Changed from LOW to DEFAULT for better
                                                                      // visibility
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(false)
                    .setShowWhen(false) // Don't show timestamp
                    .build();

            if (notificationManager != null) {
                notificationManager.notify(TIMER_NOTIFICATION_ID, notification);
                Log.d(TAG, "✅ Timer notification updated: " + title + " | " + contentText);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing timer notification: " + e.getMessage());
        }
    }

    /**
     * Update timer notification with current time
     */
    private void updateTimerNotification() {
        if (isLimiterActive && remainingTimeMs > 0) {
            showTimerNotification(); // This will update the existing notification
        }
    }

    /**
     * Hide timer notification
     */
    private void hideTimerNotification() {
        try {
            if (notificationManager == null) {
                notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            }

            if (notificationManager != null) {
                notificationManager.cancel(TIMER_NOTIFICATION_ID);
                Log.d(TAG, "✅ Timer notification hidden");
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error hiding timer notification: " + e.getMessage());
        }
    }

    /**
     * Clear permanent limiter notifications when timer resets at midnight
     */
    public static void clearPermanentLimiterNotifications(Context context) {
        try {
            Log.d("ChildDashboardActivity", "🧹 Clearing permanent limiter notifications...");

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                // Clear the permanent limiter notification
                manager.cancel(8888); // Same ID used in showPermanentLimiterExpiredNotification

                // Clear notification state from SharedPreferences
                SharedPreferences notificationPrefs = context.getSharedPreferences("permanent_limiter_notifications",
                        Context.MODE_PRIVATE);
                notificationPrefs.edit()
                        .putBoolean("limiter_expired_notification_active", false)
                        .putLong("notification_cleared_time", System.currentTimeMillis())
                        .apply();

                Log.d("ChildDashboardActivity", "✅ Permanent limiter notifications cleared for daily reset");
            }
        } catch (Exception e) {
            Log.e("ChildDashboardActivity", "❌ Error clearing permanent limiter notifications: " + e.getMessage());
        }
    }

    // ==========================================
    // Active App Timers Logic
    // ==========================================

    private void setupActiveTimersListener() {
        try {
            activeTimersCard = findViewById(R.id.activeTimersCard);
            rvActiveTimers = findViewById(R.id.rvActiveTimers);

            if (activeTimersCard == null || rvActiveTimers == null)
                return;

            activeTimerAdapter = new ActiveTimerAdapter(activeTimerList);
            rvActiveTimers.setLayoutManager(new LinearLayoutManager(this));
            rvActiveTimers.setAdapter(activeTimerAdapter);

            if (childDeviceId == null)
                return;

            showCachedActiveTimers();

            activeTimersRef = FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("device_policies")
                    .child(childDeviceId)
                    .child("app_timers");

            activeTimersListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    activeTimerList.clear();
                    Map<String, AppTimerLocalStore.TimerRecord> cachedByPackage = new HashMap<>();
                    for (AppTimerLocalStore.TimerRecord cached :
                            AppTimerLocalStore.load(ChildDashboardActivity.this, childDeviceId)) {
                        if (cached.packageName != null && !cached.packageName.isEmpty()) {
                            cachedByPackage.put(cached.packageName, cached);
                        }
                    }

                    for (DataSnapshot timerSnap : snapshot.getChildren()) {
                        try {
                            String packageName = timerSnap.child("packageName").getValue(String.class);
                            if (packageName == null || packageName.isEmpty()) {
                                packageName = timerSnap.getKey();
                            }

                            Long remainingMs = getTimerLong(timerSnap, "remainingTimeMillis");
                            Long dailyLimitMs = getTimerLong(timerSnap, "dailyLimitMillis");
                            if (dailyLimitMs == null) {
                                dailyLimitMs = getTimerLong(timerSnap, "totalTimeMillis");
                            }
                            Long exceedMs = getTimerLong(timerSnap, "exceedTimeMillis");
                            Boolean active = timerSnap.child("active").getValue(Boolean.class);
                            Boolean expired = timerSnap.child("expired").getValue(Boolean.class);

                            if (packageName != null && !packageName.isEmpty()) {
                                if (remainingMs == null) {
                                    remainingMs = dailyLimitMs != null ? dailyLimitMs : 0L;
                                }

                                AppTimerLocalStore.TimerRecord record =
                                        new AppTimerLocalStore.TimerRecord();
                                record.packageName = packageName;
                                record.key = timerSnap.getKey();
                                record.appName = timerSnap.child("appName").getValue(String.class);
                                record.remainingTimeMillis = remainingMs;
                                record.dailyLimitMillis =
                                        dailyLimitMs != null ? dailyLimitMs : remainingMs;
                                record.exceedTimeMillis = exceedMs != null ? exceedMs : 0L;
                                Long usageAtSetMs = getTimerLong(timerSnap, "usageAtSetMillis");
                                record.usageAtSetMillis =
                                        usageAtSetMs != null ? usageAtSetMs : -1L;
                                record.expired = Boolean.TRUE.equals(expired)
                                        || (remainingMs <= 0 && !Boolean.TRUE.equals(active));
                                record.active = !record.expired;

                                Long policyVersion = getTimerLong(timerSnap, "policyVersion");
                                if (policyVersion == null) {
                                    policyVersion = getTimerLong(timerSnap, "lastUpdated");
                                }
                                if (policyVersion == null) {
                                    policyVersion = getTimerLong(timerSnap, "createdAt");
                                }
                                long incomingPolicyVersion = policyVersion != null ? policyVersion : 0L;
                                record.policyVersion = incomingPolicyVersion;

                                AppTimerLocalStore.TimerRecord cached = cachedByPackage.get(packageName);
                                if (cached != null && cached.policyVersion >= record.policyVersion) {
                                    record.remainingTimeMillis = cached.remainingTimeMillis;
                                    record.exceedTimeMillis = cached.exceedTimeMillis;
                                    record.expired = cached.expired
                                            || cached.remainingTimeMillis <= 0
                                            || record.expired;
                                    record.active = !record.expired && cached.active;
                                    if (cached.appName != null && !cached.appName.isEmpty()) {
                                        record.appName = cached.appName;
                                    }
                                }

                                activeTimerList.add(createActiveTimerItem(record));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing active timer: " + e.getMessage());
                        }
                    }

                    // AppTimerService owns durable execution state. The dashboard
                    // only renders remote policy here; replacing the local store
                    // would discard offline progress/version metadata.
                    updateActiveTimersUi();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "v2 active timers listener cancelled: " + error.getMessage());
                }
            };

            activeTimersRef.addValueEventListener(activeTimersListener);

        } catch (Exception e) {
            Log.e(TAG, "Error setting up active timers: " + e.getMessage());
        }
    }

    private void showCachedActiveTimers() {
        activeTimerList.clear();
        for (AppTimerLocalStore.TimerRecord record :
                AppTimerLocalStore.load(this, childDeviceId)) {
            if ("CANCELLED".equalsIgnoreCase(record.state)) {
                continue;
            }
            activeTimerList.add(createActiveTimerItem(record));
        }
        updateActiveTimersUi();
    }

    private ActiveTimerItem createActiveTimerItem(AppTimerLocalStore.TimerRecord record) {
        ActiveTimerItem item = new ActiveTimerItem();
        item.packageName = record.packageName;
        item.remainingTimeMillis = record.remainingTimeMillis;
        item.exceedTimeMillis = record.exceedTimeMillis;
        item.expired = record.expired
                || (record.remainingTimeMillis <= 0 && !record.active);
        item.active = !item.expired;

        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(record.packageName, 0);
            item.appName = pm.getApplicationLabel(appInfo).toString();
            item.icon = pm.getApplicationIcon(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            item.appName = record.appName != null && !record.appName.isEmpty()
                    ? record.appName : record.packageName;
            item.icon = null;
        }
        return item;
    }

    private void updateActiveTimersUi() {
        Collections.sort(activeTimerList, new Comparator<ActiveTimerItem>() {
            @Override
            public int compare(ActiveTimerItem first, ActiveTimerItem second) {
                return Long.compare(first.remainingTimeMillis, second.remainingTimeMillis);
            }
        });

        if (activeTimerAdapter != null) {
            activeTimerAdapter.notifyDataSetChanged();
        }
        if (activeTimersCard != null) {
            activeTimersCard.setVisibility(activeTimerList.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private Long getTimerLong(DataSnapshot timerSnapshot, String fieldName) {
        Object value = timerSnapshot.child(fieldName).getValue();
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                Log.w(TAG, "Invalid timer value for " + fieldName + ": " + value);
            }
        }
        return null;
    }

    private String formatTimeLeft(long millis) {
        return formatTimerDisplay(millis, false);
    }

    private String formatTimerDisplay(long millis, boolean isExceed) {
        if (millis <= 0 && isExceed) {
            return "Expired";
        }
        if (millis <= 0 && !isExceed) {
            return "0m left";
        }
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        String timeText;
        if (hours > 0) {
            timeText = String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            timeText = String.format(Locale.getDefault(), "%dm", minutes);
        } else {
            timeText = "<1m";
        }
        return timeText + (isExceed ? " exceed" : " left");
    }

    // Inner Classes for Active Timers
    class ActiveTimerItem {
        String packageName;
        String appName;
        long remainingTimeMillis;
        long exceedTimeMillis;
        Drawable icon;
        boolean active;
        boolean expired;
    }

    class ActiveTimerAdapter extends RecyclerView.Adapter<ActiveTimerAdapter.ViewHolder> {
        private final List<ActiveTimerItem> timers;

        ActiveTimerAdapter(List<ActiveTimerItem> timers) {
            this.timers = timers;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_child_active_timer, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ActiveTimerItem item = timers.get(position);

            holder.tvAppName.setText(item.appName);

            if (item.expired) {
                long exceed = item.exceedTimeMillis > 0 ? item.exceedTimeMillis : 0;
                holder.tvTimeLeft.setText(formatTimerDisplay(exceed, true));
                holder.tvTimeLeft.setTextColor(getResources().getColor(R.color.error_600, getTheme()));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    holder.pbTimer.setProgressTintList(android.content.res.ColorStateList.valueOf(
                            getResources().getColor(R.color.error_600, getTheme())));
                }
            } else {
                holder.tvTimeLeft.setText(formatTimerDisplay(item.remainingTimeMillis, false));
                holder.tvTimeLeft.setTextColor(getResources().getColor(R.color.modern_green_600, getTheme()));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    holder.pbTimer.setProgressTintList(android.content.res.ColorStateList.valueOf(
                            getResources().getColor(R.color.modern_green_600, getTheme())));
                }
            }

            if (item.icon != null) {
                holder.imgIcon.setImageDrawable(item.icon);
            } else {
                holder.imgIcon.setImageResource(R.mipmap.ic_launcher);
            }

            // Simple progress bar logic
            holder.pbTimer.setIndeterminate(false);
            holder.pbTimer.setMax(100);
            holder.pbTimer.setProgress(100);
        }

        @Override
        public int getItemCount() {
            return timers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvAppName;
            android.widget.ProgressBar pbTimer;
            TextView tvTimeLeft;

            ViewHolder(View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.imgAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                pbTimer = itemView.findViewById(R.id.pbTimer);
                tvTimeLeft = itemView.findViewById(R.id.tvTimeLeft);
            }
        }
    }

    /**
     * 🆕 Fetch the real parent profile name from Firebase using the parentUserId.
     * This fixes the issue where "Google sdk_gphone..." is displayed instead of
     * "Dad".
     */
    private static final int REQUEST_LOCATION_FINE_DASHBOARD = 5001;

    private void checkAndRequestLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!hasFine && !hasCoarse) {
                showLocationRequestDialog();
            }
        }
    }

    private void showLocationRequestDialog() {
        new AlertDialog.Builder(this)
                .setTitle("📍 Enable Location Tracking")
                .setMessage("To allow parents to track this device's location in real-time, please grant Location permission.\n\n" +
                        "This permission is optional and can be managed in settings, but is highly recommended.")
                .setMessage("Sentinel can collect this child device's location, accuracy, provider/status, and timestamp so the linked parent can view the family map or request a location refresh.\n\n" +
                        "Location data is uploaded to Sentinel services and shown only to the linked parent account. This permission is optional and can be changed later in Android settings.")
                .setPositiveButton("Grant Permission", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        requestPermissions(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        }, REQUEST_LOCATION_FINE_DASHBOARD);
                    } else {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_FINE_DASHBOARD);
                    }
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    Toast.makeText(this, "⚠️ Real-time location tracking is disabled", Toast.LENGTH_SHORT).show();
                    notifyLocationPermissionDenied();
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_FINE_DASHBOARD) {
            boolean fineGranted = false;
            boolean coarseGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])) {
                    fineGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                } else if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i])) {
                    coarseGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                }
            }
            if (fineGranted || coarseGranted) {
                Toast.makeText(this, "✅ Location permission granted!", Toast.LENGTH_SHORT).show();
                Intent serviceIntent = new Intent(this, RemoteBlockService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    showBackgroundLocationDisclosure();
                }
            } else {
                Toast.makeText(this, "⚠️ Location permission denied (tracking disabled)", Toast.LENGTH_SHORT).show();
                notifyLocationPermissionDenied();
            }
        } else if (requestCode == 1004) { // REQUEST_LOCATION_BACKGROUND
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ Continuous background location enabled!", Toast.LENGTH_SHORT).show();
                Intent serviceIntent = new Intent(this, RemoteBlockService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                Toast.makeText(this, "⚠️ Background location denied (continuous tracking disabled)", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showBackgroundLocationDisclosure() {
        new AlertDialog.Builder(this)
                .setTitle("📍 Continuous Location Tracking")
                .setMessage("To track this child device's location in real-time and show updates to parents even when the app is closed or not in use, " +
                        "please select 'Allow all the time' on the next settings screen.\n\n" +
                        "This is optional but recommended.")
                .setMessage("If background location is enabled, Sentinel can collect and upload this child device's location, accuracy, and timestamp even when Sentinel is closed or not in use.\n\n" +
                        "This lets the linked parent see family location updates and request refreshes from the parent dashboard. Background location is optional and can be changed later in Android settings. Select 'Allow all the time' on the next screen only if you agree.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 1004);
                    }
                })
                .setNegativeButton("No Thanks", null)
                .setCancelable(false)
                .show();
    }

    private void notifyLocationPermissionDenied() {
        online.monarchlabs.sentinel.services.PermissionMonitorService
                .requestImmediateCheck(this);
    }
}
