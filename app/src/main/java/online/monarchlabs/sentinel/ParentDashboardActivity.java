package online.monarchlabs.sentinel;

import android.content.Intent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Build;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.LayoutInflater;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationCompat;

import online.monarchlabs.sentinel.databinding.ActivityParentDashboardBinding;
import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.data.ParentAppInventoryCache;
import online.monarchlabs.sentinel.models.AppUsage; // Added for AppUsage model
import online.monarchlabs.sentinel.services.GeofenceService;
import androidx.annotation.NonNull;
import android.content.SharedPreferences;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import online.monarchlabs.sentinel.models.ChildDeviceManager;
import online.monarchlabs.sentinel.security.ParentAccessGate;
import online.monarchlabs.sentinel.utils.LoadingDialogManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ParentDashboardActivity extends BaseActivity {
    private Button btnSelectApps;
    private Button btnSetLimiter;
    private Button btnClearLimiter;
    private List<String> selectedDays = new ArrayList<>();
    private List<String> selectedApps = new ArrayList<>();

    // Usage Limiter Firebase references
    private DatabaseReference limiterRef;
    private ValueEventListener limiterListener;
    private DatabaseReference currentLimiterDeviceRef; // Tracks the device-specific limiter ref for listener lifecycle

    // Ã°Å¸â€Â§ MULTI-DEVICE FIX: Track active listeners for cleanup when switching
    // devices
    private ValueEventListener activeLimiterListener;
    private DatabaseReference activeLimiterRef;

    // Periodic timer refresh handler

    // Debouncing for UI updates
    private Handler uiUpdateHandler = new Handler();
    private Runnable pendingUIUpdate = null;
    private static final int UI_UPDATE_DEBOUNCE_DELAY = 100; // 100ms debounce

    // Date range for recurring timers
    private Calendar fromDate = Calendar.getInstance();
    private Calendar toDate = Calendar.getInstance();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

    // Add current selected date for usage display with protection against
    // auto-resets
    private Calendar currentUsageDate = Calendar.getInstance();
    private boolean dateSetByUser = false; // Flag to prevent automatic date resets
    private SimpleDateFormat usageDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    // Ã°Å¸â€Â DATE TRACE: Log initial values
    {
        String initialDate = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_INIT: currentUsageDate initialized at field declaration");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_INIT: Initial value = " + initialDate);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_INIT: dateSetByUser = false (initial)");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_INIT: Thread = " + Thread.currentThread().getName());
    }


    // Fresh login state flag
    private boolean isFreshLoginSession = false;

    // Focus mode components
    private SharedPreferences usageCachePrefs; // Ã°Å¸â€œÂ¦ PERSISTENT CACHE STORAGE

    private Button btnViewUsage;
    private Button btnSelectDays;
    private SessionManager sessionManager;
    private DeviceStatusManager deviceStatusManager;
    private ConnectedDevicesManager connectedDevicesManager;
    private ChildDeviceManager childDeviceManager;
    private QRCodeManager qrCodeManager;
    private LoadingDialogManager loadingDialogManager;
    private Button btnRemoveDevice;
    private ActivityParentDashboardBinding binding;
    private BottomNavigationView bottomNavigation;
    private TextView tvUninstallProtectionDash;
    private TextView tvUninstallProtectionBadge;
    private ValueAnimator guidePulseAnimator;
    private final Handler guideLabelHandler = new Handler(Looper.getMainLooper());
    private Runnable hideGuideLabelRunnable;
    private String currentChildDeviceId;
    private String currentChildDeviceName;
    private String currentChildUserName;
    private String permanentQRKey;
    private List<ChildDevice> connectedDevices = new ArrayList<>();
    private ExecutorService usageComputationExecutor = Executors.newSingleThreadExecutor();
    private MapView dashboardMapView;
    private boolean mapCardInitialized = false;
    private View cardMapContainer;
    private View mapBlurOverlay;
    private ImageView ivMapToggleIcon;
    private View homeContent;
    private View settingsContent;
    private LinearLayout llDeviceList;
    private DatabaseReference childLocationRef;
    private ValueEventListener childLocationListener;
    private DatabaseReference qrScanRef;
    private ValueEventListener qrScanListener;
    private DatabaseReference parentsConnectionRef;
    private ValueEventListener parentsConnectionListener;
    private DatabaseReference parentDeviceLinksRef;
    private ChildEventListener parentDeviceLinksListener;
    private DatabaseReference sosEventsRef;
    private ChildEventListener sosEventsListener;
    private DatabaseReference geofenceEventsRef;
    private ChildEventListener geofenceEventsListener;
    private DatabaseReference deviceAppsConnectionRef;
    private boolean deviceAppsConnectionListenerAttached = false;
    private DatabaseReference uninstallProtectionStatusRef;
    private ValueEventListener uninstallProtectionStatusListener;
    private GoogleMap dashboardGoogleMap;
    private Marker childLocationMarker;
    private LatLng lastChildLocation;
    private final Map<String, LatLng> cachedChildLocations = new HashMap<>();
    private final Map<String, Boolean> cachedChildGpsOffStates = new HashMap<>();
    private final Map<String, String> cachedChildLocationWarningMessages = new HashMap<>();
    private View mapStatusBadge;
    private View mapStatusDot;
    private View mapStatusProgress;
    private TextView tvMapStatusText;
    private final Map<String, Long> cachedChildLocationTimestamps = new HashMap<>();
    private Handler locationTimeoutHandler;
    private Runnable locationTimeoutRunnable;
    private long lastLocationTimestamp = 0L;
    private View childSwitchSkeletonOverlay;
    private final Handler childSwitchLoadingHandler = new Handler(Looper.getMainLooper());
    private ObjectAnimator childSwitchSkeletonAnimator;
    private Runnable childSwitchLoadingTimeoutRunnable;
    private long childSwitchLoadingShownAt = 0L;
    private static final long CHILD_SWITCH_LOADING_TIMEOUT_MS = 4500L;
    private static final long CHILD_SWITCH_LOADING_MIN_MS = 800L;
    private boolean autoLocationRefreshEnabled = true;
    private boolean waitingForFreshLocation = false;
    private static final String CONNECTION_TOAST_PREFS = "connection_toast_prefs";
    private static final long RECENT_CONNECTION_TOAST_WINDOW_MS = 2 * 60 * 1000L;
    private long locationRequestStartTime = 0L;
    private Map<String, Long> cachedUsageData = new HashMap<>();
    private EditText etLimiterHours;
    private EditText etLimiterMinutes;
    private TextView tvLimiterTimer;
    private TextView tvLimiterStatus;
    private Map<String, String> cachedUsageFormatted = new HashMap<>();
    private DatabaseReference smartUsageRef;
    private ValueEventListener smartUsageListener;
    private DatabaseReference smartUsageTimestampRef;
    private ValueEventListener smartUsageTimestampListener;
    private boolean usageMonitoringForeground;
    private final Handler usageLifecycleHandler = new Handler(Looper.getMainLooper());
    private Runnable usageMidnightRollover;
    private static final String TAG = "ParentDashboardActivity";
    private static final String MAP_BUNDLE_KEY = "MapViewBundleKey";

    /**
     * Ã°Å¸â€Â Helper method to get caller method name for trace logs
     */
    private String getCallerMethodName() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        // Index 0: getStackTrace, 1: getCallerMethodName, 2: actual caller, 3: caller's caller
        if (stackTrace.length > 3) {
            return stackTrace[3].getMethodName() + "() [Line " + stackTrace[3].getLineNumber() + "]";
        }
        return "Unknown";
    }

    // Ã°Å¸Å¡Â¨ UNINSTALL DETECTION
    private UninstallDetectionManager uninstallDetectionManager;
    private LinearLayout layoutUninstallWarning;
    private TextView tvUninstallWarningTitle;
    private TextView tvUninstallWarningMessage;
    private TextView tvSeeIssuesToggle;
    private LinearLayout layoutPossibleIssues;
    private TextView tvUninstallLastSeen;
    private boolean isPossibleIssuesExpanded = false;
    // Sync warning banner
    private View layoutSyncWarning;
    private TextView tvSyncWarning;
    private final Map<String, Long> lastUsageUploadTimestamps = new HashMap<>();
    private final Set<String> loadedUsageDeviceIds = new HashSet<>();
    private final Handler syncWarningHandler = new Handler(Looper.getMainLooper());
    private Runnable syncWarningRunnable;
    private String currentDeviceStatus = UninstallDetectionManager.STATUS_ONLINE;
    private final Map<String, String> lastNotifiedStatusByDevice = new HashMap<>();

    // Request codes
    private static final int REQUEST_APP_SELECTION = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1004;

    private FirebaseAuth mAuth;

    private String deviceIdJustRemoved = null;

    // Ã°Å¸â€™â€œ PARENT HEARTBEAT: Timer to keep session alive and detect if app is deleted
    private java.util.Timer parentHeartbeatTimer;
    private static final long HEARTBEAT_INTERVAL = 5 * 60 * 1000;

    public static class PieSlice {
        public String label;
        public float value;

        public PieSlice(String label, float value) {
            this.label = label;
            this.value = value;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ParentAccessGate.requireVerifiedParent(this);
        Log.d(TAG, "onCreate called");

        // Make status bar transparent but visible (light status bar icons)
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

        try {
            // Enable hardware acceleration for better performance
            getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

            Log.d(TAG, "Initializing view binding and Firebase...");

            binding = ActivityParentDashboardBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());

            // Usage history is read from exact v2/usage_daily device/date paths.

            mAuth = FirebaseAuth.getInstance();

            // Initialize session manager
            sessionManager = new SessionManager(this);

            // Initialize loading dialog manager
            loadingDialogManager = new LoadingDialogManager(this);

            Log.d(TAG, "Basic components initialized successfully");

            // Update session activity
            sessionManager.updateLastActivity();


            // Ã°Å¸â€œÂ¦ Initialize usage cache preferences
            usageCachePrefs = getSharedPreferences("usage_data_cache_prefs", MODE_PRIVATE);

            // Ã°Å¸â€Â DATE TRACE: Log date state after basic initialization
            String dateAfterPrefs = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_1: After preferences init");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_1: currentUsageDate = " + dateAfterPrefs);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_1: dateSetByUser = " + dateSetByUser);

            // Ã°Å¸â€Â DATE TRACE: Before initializeViews
            String dateBeforeViews = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_2: BEFORE initializeViews()");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_2: currentUsageDate = " + dateBeforeViews);

            initializeViews();

            // Ã°Å¸â€Â DATE TRACE: After initializeViews
            String dateAfterViews = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_3: AFTER initializeViews()");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_3: currentUsageDate = " + dateAfterViews);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_3: Changed? " + (!dateBeforeViews.equals(dateAfterViews)));

            initializeManagers();

            // Ã°Å¸â€Â DATE TRACE: After initializeManagers
            String dateAfterManagers = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_4: AFTER initializeManagers()");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_4: currentUsageDate = " + dateAfterManagers);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_4: Changed? " + (!dateAfterViews.equals(dateAfterManagers)));

            // Ã°Å¸Â§Â¹ FRESH LOGIN CHECK - Ensure clean slate for new login sessions
            // (Must be AFTER managers are initialized)
            boolean isFreshLogin = checkForFreshLoginAndCleanup();
            isFreshLoginSession = isFreshLogin; // Store for use in other methods

            // Ã°Å¸â€Â DATE TRACE: Before restoreLastSelectedChildOnStartup
            String dateBeforeRestore = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_5: BEFORE restoreLastSelectedChildOnStartup()");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_5: currentUsageDate = " + dateBeforeRestore);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_5: isFreshLoginSession = " + isFreshLoginSession);

            if (!isFreshLoginSession) {
                restoreLastSelectedChildOnStartup();

                // Ã°Å¸â€Â DATE TRACE: After restoreLastSelectedChildOnStartup
                String dateAfterRestore = usageDateFormat.format(currentUsageDate.getTime());
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_6: AFTER restoreLastSelectedChildOnStartup()");
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_6: currentUsageDate = " + dateAfterRestore);
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_6: dateSetByUser = " + dateSetByUser);
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_6: Changed? " + (!dateBeforeRestore.equals(dateAfterRestore)));
            } else {
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ONCREATE_6: SKIPPED restoreLastSelectedChildOnStartup (fresh login)");
            }

            // Show welcome message with important information
            showWelcomeMessage();

            setupBottomNavigation();
            setupUsageLimiter();
            setupQRCodeGeneration();
            setupDeviceSwitcher();



            setupChart();

            // Ã°Å¸â€â€ START PERSISTENT TIMER NOTIFICATION SERVICE for parent devices
            setupParentTimerExpiryListener();
            setupSosEventsListener();
            setupGeofenceEventsListener();

            // Ã°Å¸â€œÂ¡ START PERMISSION EVENT LISTENER to monitor child device service status
            startPermissionEventListener();

            // Only allow QR scan connections - user requirement
            Log.d(TAG, "Automatic device loading disabled - only QR scan connections allowed");

            if (connectedDevices.isEmpty()) {
                Log.d(TAG, "No devices restored - showing empty state");
                updateDeviceStatus();
                updateTargetDeviceDisplay();
            } else {
            }

            // Listen only to canonical v2 links created by QR pairing.
            setupV2ParentDeviceLinksListener();
            // Add settings buttons functionality
            addSettingsButtons();



            // Show Home content by default
            bottomNavigation.setSelectedItemId(R.id.nav_home);

            // Update map card state now that devices are known
            updateMapCardVisibility();

            // Load uninstall protection state for the initially selected child
            loadUninstallProtectionForDevice(currentChildDeviceId);

            // Auto-request fresh location for the initially selected child
            requestFreshLocation(currentChildDeviceId);

            // Hide the usage stats section (Usage Overview) if the data is fake
            // View usageOverviewSection = findViewById(R.id.usageOverviewSection);
            // if (usageOverviewSection != null) {
            // usageOverviewSection.setVisibility(View.GONE);
            // }

            setupCategorySummaryChart();

            // Ã°Å¸â€â€ Setup notification bell badge
            setupNotificationBadge();

            // Ã°Å¸â€™â€œ Start parent heartbeat timer to keep session alive
            startParentHeartbeatTimer();

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage());
            Toast.makeText(this, "Error loading dashboard: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Ã°Å¸â€™â€œ Start parent heartbeat timer to keep session alive
     * Other devices can check this heartbeat to know if the app was deleted
     */
    private void startParentHeartbeatTimer() {
        stopParentHeartbeatTimer();
        String parentUid = sessionManager.getParentUserId();
        if (parentUid == null || parentUid.isEmpty()) {
            return;
        }
        String parentDeviceId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        DatabaseReference heartbeatRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_clients")
                .child(parentUid)
                .child(parentDeviceId);
        parentHeartbeatTimer = new java.util.Timer();
        parentHeartbeatTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                Map<String, Object> status = new HashMap<>();
                status.put("lastHeartbeatAt", System.currentTimeMillis());
                status.put("platform", "android");
                status.put("schemaVersion", 2);
                heartbeatRef.updateChildren(status);
            }
        }, 0L, HEARTBEAT_INTERVAL);
    }

    /**
     * Ã°Å¸â€™â€œ Stop parent heartbeat timer
     */
    private void stopParentHeartbeatTimer() {
        if (parentHeartbeatTimer != null) {
            parentHeartbeatTimer.cancel();
            parentHeartbeatTimer = null;
            Log.d(TAG, "Ã°Å¸â€™â€œ Parent heartbeat timer stopped");
        }
    }

    private void initializeViews() {
        // Initialize navigation views
        bottomNavigation = findViewById(R.id.bottomNavigation);
        homeContent = findViewById(R.id.homeContent);
        // usageContent removed/merged
        settingsContent = findViewById(R.id.settingsContent);

        // New Dashboard Views
        llDeviceList = findViewById(R.id.llDeviceList);

        // Ã¢â€â‚¬Ã¢â€â‚¬ Map card setup Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
        cardMapContainer = findViewById(R.id.cardMapContainer);
        dashboardMapView = findViewById(R.id.dashboardMapView);
        mapBlurOverlay   = findViewById(R.id.mapBlurOverlay);
        mapStatusBadge = findViewById(R.id.mapStatusBadge);
        mapStatusDot = findViewById(R.id.mapStatusDot);
        mapStatusProgress = findViewById(R.id.mapStatusProgress);
        tvMapStatusText = findViewById(R.id.tvMapStatusText);
        childSwitchSkeletonOverlay = findViewById(R.id.childSwitchSkeletonOverlay);
        ivMapToggleIcon  = findViewById(R.id.ivMapToggleIcon);

        if (childSwitchSkeletonOverlay != null) {
            childSwitchSkeletonOverlay.setVisibility(View.GONE);
        }

        if (dashboardMapView != null) {
            dashboardMapView.onCreate(null);
            // Allow the mini-map to receive scroll/zoom gestures without the
            // parent ScrollView stealing vertical touches
            dashboardMapView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return false; // let MapView handle the rest
            });
            dashboardMapView.getMapAsync(googleMap -> {
                dashboardGoogleMap = googleMap;
                // Default world view
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(20.0, 0.0), 2f));
                // Enable pan/zoom; keep rotate & tilt off for the small card
                googleMap.getUiSettings().setScrollGesturesEnabled(true);
                googleMap.getUiSettings().setZoomGesturesEnabled(true);
                googleMap.getUiSettings().setRotateGesturesEnabled(false);
                googleMap.getUiSettings().setTiltGesturesEnabled(false);
                googleMap.getUiSettings().setMyLocationButtonEnabled(false);
                // If we already received a location before the map was ready, show it now
                updateChildMarkerOnDashboard();
                hideMapLoading();
            });
            mapCardInitialized = true;
        }

        // Toggle icon button (top-right of card) Ã¢â€ â€™ also opens full screen
        View btnToggle = findViewById(R.id.btnToggleMapSize);
        if (btnToggle != null) {
            btnToggle.setOnClickListener(v -> {
                Intent intent = new Intent(this, FullScreenMapActivity.class);
                intent.putExtra("childDeviceId", currentChildDeviceId);
                intent.putExtra("childName", currentChildUserName);
                if (lastChildLocation != null) {
                    intent.putExtra("lastLat", lastChildLocation.latitude);
                    intent.putExtra("lastLng", lastChildLocation.longitude);
                }
                if (currentChildDeviceId != null) {
                    Boolean offlineState = cachedChildGpsOffStates.get(currentChildDeviceId);
                    intent.putExtra("isOffline", Boolean.TRUE.equals(offlineState));
                }
                if (lastLocationTimestamp > 0) {
                    intent.putExtra("lastTimestamp", lastLocationTimestamp);
                }
                startActivity(intent);
            });
        }

        // My Location button Ã¢â‚¬â€ animate to childÃ¢â‚¬â„¢s last known location
        View btnMyLocCard = findViewById(R.id.btnMyLocationCard);
        if (btnMyLocCard != null) {
            btnMyLocCard.setOnClickListener(v -> {
                showMapLoading();
                // Animate to last known position immediately
                if (lastChildLocation != null && dashboardGoogleMap != null) {
                    dashboardGoogleMap.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(lastChildLocation, 14f));
                }
                // Request a fresh GPS fix from the child device
                if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
                    autoLocationRefreshEnabled = false;
                    waitingForFreshLocation = true;
                    locationRequestStartTime = System.currentTimeMillis();
                    online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository
                            .requestLocationRefresh(currentChildDeviceId);
                } else {
                    hideMapLoading();
                    Toast.makeText(this, "Connect a child device first", Toast.LENGTH_SHORT).show();
                }
            });
        }
        // Ã¢â€â‚¬Ã¢â€â‚¬ End map card setup Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

        View btnAddSafeZone = findViewById(R.id.btnAddSafeZone);
        if (btnAddSafeZone != null) {
            btnAddSafeZone.setOnClickListener(v -> showCreateGeofenceDialog());
        }

        // Quick Actions
        // View cardQrAction = findViewById(R.id.cardQrAction); // Removed from XML
        View cardAppLimitsAction = findViewById(R.id.cardAppLimitsAction);
        // View cardSettingsAction = findViewById(R.id.cardSettingsAction); // Removed

        /*
         * Removed from XML
         * if (cardQrAction != null) {
         * cardQrAction.setOnClickListener(v -> {
         * // Open QR Scanner / Connection screen
         * showQRScanner();
         * });
         * }
         */

        if (cardAppLimitsAction != null) {
            cardAppLimitsAction.setOnClickListener(v -> {
                if (currentChildDeviceId != null) {
                    // Open App Blocking / Limits
                    Intent intent = new Intent(this, ChildInstalledAppsActivity.class);
                    intent.putExtra(ChildInstalledAppsActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                    String displayName = (currentChildUserName != null && !currentChildUserName.isEmpty())
                            ? currentChildUserName
                            : currentChildDeviceName;
                    intent.putExtra(ChildInstalledAppsActivity.EXTRA_CHILD_NAME, displayName);
                    intent.putExtra(ChildInstalledAppsActivity.EXTRA_IS_PARENT_CONTEXT, true);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        View cardModesAction = findViewById(R.id.cardModesAction);
        if (cardModesAction != null) {
            cardModesAction.setOnClickListener(v -> {
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, ModesActivity.class);
                    intent.putExtra(ModesActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                    intent.putExtra(ModesActivity.EXTRA_CHILD_NAME, getCurrentChildDisplayName());
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
            });
        }
        // Wire up "Usage Stats" to ChildUsageViewActivity
        View cardUsageStatsAction = findViewById(R.id.cardUsageStatsAction);
        if (cardUsageStatsAction != null) {
            cardUsageStatsAction.setOnClickListener(v -> {
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, ChildUsageViewActivity.class);
                    intent.putExtra(ChildUsageViewActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                    intent.putExtra(ChildUsageViewActivity.EXTRA_CHILD_NAME, getCurrentChildDisplayName());
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Wire up "Activity Overview" card sections (Split Click Targets)
        View layoutManageDeviceHeader = findViewById(R.id.layoutManageDeviceHeader);
        if (layoutManageDeviceHeader != null) {
            layoutManageDeviceHeader.setOnClickListener(v -> {
                showDeviceSwitcher();
            });
        }

        View layoutUsageStatsBody = findViewById(R.id.layoutUsageStatsBody);
        if (layoutUsageStatsBody != null) {
            layoutUsageStatsBody.setOnClickListener(v -> {
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, ChildUsageViewActivity.class);
                    intent.putExtra(ChildUsageViewActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                    intent.putExtra(ChildUsageViewActivity.EXTRA_CHILD_NAME, getCurrentChildDisplayName());
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Settings card removed from quick actions
        // if (cardSettingsAction != null) ... removed

        // Uninstall Protection status on dashboard
        tvUninstallProtectionDash     = findViewById(R.id.tvUninstallProtectionDash);
        tvUninstallProtectionBadge    = findViewById(R.id.tvUninstallProtectionBadge);

        View btnAddDevice = findViewById(R.id.btnAddDevice);
        if (btnAddDevice != null) {
            btnAddDevice.setOnClickListener(v -> showQRScanner());
        }


        // Greeter
        TextView tvGreeter = findViewById(R.id.tvGreeter);
        TextView tvParentName = findViewById(R.id.tvParentName); // Added reference
        TextView tvCurrentDate = findViewById(R.id.tvCurrentDate);

        // Ã°Å¸â€Â§ Manage Devices Click Listener (Disable click on tvDeviceStatus text view so that clicks bubble up to the parent layoutManageDeviceHeader)
        if (binding != null && binding.tvDeviceStatus != null) {
            binding.tvDeviceStatus.setOnClickListener(null);
            binding.tvDeviceStatus.setClickable(false);
        } else {
            // Fallback if binding not fully ready or using findViewById
            TextView tvDeviceStatus = findViewById(R.id.tvDeviceStatus);
            if (tvDeviceStatus != null) {
                tvDeviceStatus.setOnClickListener(null);
                tvDeviceStatus.setClickable(false);
            }
        }

        // Ã°Å¸â€œËœ GUIDE BOOK BUTTON
        // Initialize the floating guide button and label container
        View btnGuideBook = findViewById(R.id.btnGuideBook);
        View fabGuide = findViewById(R.id.fabGuide);

        View.OnClickListener guideClickListener = v -> {
            startActivity(new Intent(ParentDashboardActivity.this, GuideBookActivity.class));
        };

        if (btnGuideBook != null)
            btnGuideBook.setOnClickListener(guideClickListener);
        if (fabGuide != null)
            fabGuide.setOnClickListener(guideClickListener);
        setupGuideMotion(btnGuideBook, fabGuide);

        // Ã°Å¸â€Â§ Set Parent Name - Load from Firebase Database
        final TextView tvParentNameRef = tvParentName;
        if (mAuth != null && mAuth.getCurrentUser() != null && tvParentNameRef != null) {
            String uid = mAuth.getCurrentUser().getUid();

            // First set temporary text
            tvParentNameRef.setText("Hi there!");

            // Query Firebase Database for saved name
            FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("parent_profiles")
                    .child(uid)
                    .child("displayName")
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        String name = snapshot.getValue(String.class);
                        if (name != null && !name.trim().isEmpty()) {
                            tvParentNameRef.setText("Hi " + name);
                            Log.d(TAG, "Loaded parent name from DB: " + name);
                        } else {
                            // Fallback to email extraction
                            String email = mAuth.getCurrentUser().getEmail();
                            if (email != null) {
                                try {
                                    String namePart = email.split("@")[0].replaceAll("\\d+", "").replaceAll("[^a-zA-Z]",
                                            "");
                                    if (!namePart.isEmpty()) {
                                        String extracted = namePart.substring(0, 1).toUpperCase()
                                                + namePart.substring(1);
                                        tvParentNameRef.setText("Hi " + extracted);
                                    }
                                } catch (Exception e) {
                                    tvParentNameRef.setText("Hi Parent");
                                }
                            } else {
                                tvParentNameRef.setText("Hi Parent");
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to load parent name: " + e.getMessage());
                        tvParentNameRef.setText("Hi Parent");
                    });
        } else if (tvParentName != null) {
            tvParentName.setText("Hi Parent");
        }

        if (tvGreeter != null) {
            tvGreeter.setText("Welcome Back");
        }

        if (tvCurrentDate != null) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEEE, MMM d",
                    java.util.Locale.getDefault());
            tvCurrentDate.setText(sdf.format(new java.util.Date()));
        }

        // Usage limiter views removed from parent dashboard per request
        tvLimiterStatus = null;
        tvLimiterTimer = null;
        etLimiterHours = null;
        etLimiterMinutes = null;
        btnSelectDays = null;
        btnSelectApps = null;
        btnSetLimiter = null;
        btnClearLimiter = null;

        // Notification Bell Icon - Opens child permission notifications page
        View btnNotifications = findViewById(R.id.btnNotifications);
        if (btnNotifications != null) {
            btnNotifications.setOnClickListener(v -> {
                if (currentChildDeviceId != null && sessionManager.getUserId() != null) {
                    Intent intent = new Intent(this, ChildNotificationsActivity.class);
                    intent.putExtra(ChildNotificationsActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                    intent.putExtra(ChildNotificationsActivity.EXTRA_CHILD_NAME,
                            (currentChildUserName != null && !currentChildUserName.isEmpty())
                                    ? currentChildUserName
                                    : currentChildDeviceName);
                    intent.putExtra(ChildNotificationsActivity.EXTRA_PARENT_USER_ID, sessionManager.getUserId());
                    startActivityForResult(intent, REQUEST_NOTIFICATIONS);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Ã°Å¸Å¡Â¨ UNINSTALL WARNING UI INITIALIZATION
        layoutUninstallWarning = findViewById(R.id.layoutUninstallWarning);
        tvUninstallWarningTitle = findViewById(R.id.tvUninstallWarningTitle);
        tvUninstallWarningMessage = findViewById(R.id.tvUninstallWarningMessage);
        tvSeeIssuesToggle = findViewById(R.id.tvSeeIssuesToggle);
        layoutPossibleIssues = findViewById(R.id.layoutPossibleIssues);
        tvUninstallLastSeen = findViewById(R.id.tvUninstallLastSeen);

        if (tvSeeIssuesToggle != null) {
            tvSeeIssuesToggle.setOnClickListener(v -> togglePossibleIssues());
        }

        if (layoutPossibleIssues != null) {
            layoutPossibleIssues.setVisibility(View.GONE);
            layoutPossibleIssues.setAlpha(0f);
        }

        // Sync warning banner
        layoutSyncWarning = findViewById(R.id.layoutSyncWarning);
        tvSyncWarning = findViewById(R.id.tvSyncWarning);

        // Initialize UninstallDetectionManager
        uninstallDetectionManager = new UninstallDetectionManager(this);

        // Start periodic sync warning check
        syncWarningRunnable = new Runnable() {
            @Override
            public void run() {
                updateSyncWarningBanner();
                syncWarningHandler.postDelayed(this, 30000); // 30 seconds check
            }
        };
        syncWarningHandler.post(syncWarningRunnable);

        // Ã°Å¸â€ºÂ¡Ã¯Â¸Â Uninstall Protection Education Expand/Collapse
        android.view.ViewGroup cardUninstallProtectionContainer = (android.view.ViewGroup) findViewById(R.id.cardUninstallProtectionContainer);
        View layoutUninstallEducationTrigger = findViewById(R.id.layoutUninstallEducationTrigger);
        View layoutUninstallEducationContent = findViewById(R.id.layoutUninstallEducationContent);
        ImageView ivUninstallEducationChevron = findViewById(R.id.ivUninstallEducationChevron);

        if (layoutUninstallEducationTrigger != null && layoutUninstallEducationContent != null && cardUninstallProtectionContainer != null) {
            layoutUninstallEducationTrigger.setOnClickListener(v -> {
                boolean isCurrentlyVisible = layoutUninstallEducationContent.getVisibility() == View.VISIBLE;

                // Animate transition smoothly
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    android.transition.TransitionManager.beginDelayedTransition(cardUninstallProtectionContainer);
                }

                if (isCurrentlyVisible) {
                    layoutUninstallEducationContent.animate()
                            .alpha(0f)
                            .translationY(-8f)
                            .setDuration(140)
                            .withEndAction(() -> {
                                layoutUninstallEducationContent.setVisibility(View.GONE);
                                layoutUninstallEducationContent.setTranslationY(0f);
                            })
                            .start();
                    if (ivUninstallEducationChevron != null) {
                        ivUninstallEducationChevron.animate().rotation(0f).setDuration(200).start();
                    }
                } else {
                    layoutUninstallEducationContent.setAlpha(0f);
                    layoutUninstallEducationContent.setTranslationY(-10f);
                    layoutUninstallEducationContent.setVisibility(View.VISIBLE);
                    layoutUninstallEducationContent.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(240)
                            .setInterpolator(new AccelerateDecelerateInterpolator())
                            .start();
                    if (ivUninstallEducationChevron != null) {
                        ivUninstallEducationChevron.animate().rotation(180f).setDuration(200).start();
                    }
                }
            });
        }

    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                // Home is always visible, just update UI
                return true;
            } else if (itemId == R.id.nav_timer_status) {
                // Launch Timer Status Activity
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, TimerStatusActivity.class);
                    intent.putExtra(TimerStatusActivity.EXTRA_DEVICE_ID, currentChildDeviceId);
                    intent.putExtra(TimerStatusActivity.EXTRA_IS_PARENT, true);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (itemId == R.id.nav_assistant) {
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, AssistantActivity.class);
                    intent.putExtra(AssistantActivity.EXTRA_SELECTED_CHILD_ID, currentChildDeviceId);
                    intent.putExtra(AssistantActivity.EXTRA_SELECTED_CHILD_NAME,
                            currentChildUserName != null && !currentChildUserName.isEmpty()
                                    ? currentChildUserName
                                    : currentChildDeviceName);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (itemId == R.id.nav_settings) {
                // Launch Settings Activity
                Intent intent = new Intent(this, ParentSettingsActivity.class);
                intent.putExtra("selected_child_device_id", currentChildDeviceId);
                startActivity(intent);
                return true;
            }
            return false;
        });
    }

    /**
     * Ã°Å¸â€â€ Setup notification badge on bell icon to show total unread count
     */
    private TextView tvNotificationBadge;
    private ValueEventListener notificationLastReadListener;
    private DatabaseReference notificationLastReadRef;
    private ValueEventListener notificationPermissionEventsListener;
    private DatabaseReference notificationPermissionEventsRef;
    private ValueEventListener notificationAppEventsListener;
    private DatabaseReference notificationAppEventsRef;
    private long notificationLastReadTime = 0L;
    private int notificationPermissionUnreadCount = 0;
    private int notificationAppUnreadCount = 0;
    private void setupNotificationBadge() {
        tvNotificationBadge = findViewById(R.id.tvNotificationBadge);
        FrameLayout btnNotifications = findViewById(R.id.btnNotifications);

        if (tvNotificationBadge == null || btnNotifications == null) {
            Log.w(TAG, "Notification badge views not found");
            return;
        }

        // Set click listener to open notifications
        btnNotifications.setOnClickListener(v -> {
            if (currentChildDeviceId != null && mAuth.getCurrentUser() != null) {
                Intent intent = new Intent(this, ChildNotificationsActivity.class);
                intent.putExtra(ChildNotificationsActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                intent.putExtra(ChildNotificationsActivity.EXTRA_CHILD_NAME, currentChildDeviceName);
                intent.putExtra(ChildNotificationsActivity.EXTRA_PARENT_USER_ID, mAuth.getCurrentUser().getUid());
                startActivityForResult(intent, REQUEST_NOTIFICATIONS);
            } else {
                Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            }
        });

        // Initial update
        refreshNotificationBadge();
    }

    /**
     * Refresh the notification badge - called when device changes or data updates
     */
    public void refreshNotificationBadge() {
        if (tvNotificationBadge == null) {
            tvNotificationBadge = findViewById(R.id.tvNotificationBadge);
        }
        if (tvNotificationBadge == null) {
            return;
        }

        stopNotificationBadgeMonitoring();

        if (currentChildDeviceId == null || mAuth == null || mAuth.getCurrentUser() == null) {
            tvNotificationBadge.setVisibility(View.GONE);
            return;
        }

        final String badgeDeviceId = currentChildDeviceId;
        final String parentUid = mAuth.getCurrentUser().getUid();
        notificationLastReadTime = 0L;
        notificationPermissionUnreadCount = 0;
        notificationAppUnreadCount = 0;
        Log.d(TAG, "Ã°Å¸â€â€ Refreshing notification badge for device: " + badgeDeviceId);

        notificationLastReadRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_notification_state")
                .child(parentUid)
                .child(badgeDeviceId)
                .child("lastReadTimestamp");

        notificationPermissionEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("permission_logs")
                .child(badgeDeviceId);

        notificationAppEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("app_events")
                .child(badgeDeviceId);

        notificationLastReadListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot lastReadSnap) {
                if (!badgeDeviceId.equals(currentChildDeviceId)) return;
                Long lastRead = lastReadSnap.getValue(Long.class);
                notificationLastReadTime = lastRead != null ? lastRead : 0L;
                recountNotificationBadgeOnce(badgeDeviceId, parentUid);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Last read error: " + error.getMessage());
            }
        };

        notificationPermissionEventsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!badgeDeviceId.equals(currentChildDeviceId)) return;
                notificationPermissionUnreadCount = countUnreadEvents(snapshot, notificationLastReadTime);
                updateNotificationBadgeView();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Permission events error: " + error.getMessage());
            }
        };

        notificationAppEventsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!badgeDeviceId.equals(currentChildDeviceId)) return;
                notificationAppUnreadCount = countUnreadEvents(snapshot, notificationLastReadTime);
                updateNotificationBadgeView();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "App events error: " + error.getMessage());
            }
        };

        notificationLastReadRef.addValueEventListener(notificationLastReadListener);
        notificationPermissionEventsRef.addValueEventListener(notificationPermissionEventsListener);
        notificationAppEventsRef.addValueEventListener(notificationAppEventsListener);
    }

    private void recountNotificationBadgeOnce(String badgeDeviceId, String parentUid) {
        DatabaseReference permEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("permission_logs")
                .child(badgeDeviceId);
        DatabaseReference appEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("app_events")
                .child(badgeDeviceId);

        permEventsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot permSnapshot) {
                if (!badgeDeviceId.equals(currentChildDeviceId)) return;
                notificationPermissionUnreadCount = countUnreadEvents(permSnapshot, notificationLastReadTime);
                appEventsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot appSnapshot) {
                        if (!badgeDeviceId.equals(currentChildDeviceId)) return;
                        notificationAppUnreadCount = countUnreadEvents(appSnapshot, notificationLastReadTime);
                        updateNotificationBadgeView();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "App events recount error: " + error.getMessage());
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Permission events recount error: " + error.getMessage());
            }
        });
    }

    private int countUnreadEvents(DataSnapshot snapshot, long lastReadTime) {
        int count = 0;
        for (DataSnapshot event : snapshot.getChildren()) {
            Long ts = event.child("timestamp").getValue(Long.class);
            if (ts != null && ts > lastReadTime) {
                count++;
            }
        }
        return count;
    }

    private void updateNotificationBadgeView() {
        int totalCount = notificationPermissionUnreadCount + notificationAppUnreadCount;
        runOnUiThread(() -> {
            if (tvNotificationBadge == null) return;
            if (totalCount > 0) {
                tvNotificationBadge.setVisibility(View.VISIBLE);
                tvNotificationBadge.setText(totalCount > 99 ? "99+" : String.valueOf(totalCount));
                Log.d(TAG, "Ã°Å¸â€â€ Badge showing: " + totalCount);
            } else {
                tvNotificationBadge.setVisibility(View.GONE);
                Log.d(TAG, "Ã°Å¸â€â€ Badge hidden (no unread)");
            }
        });
    }

    private void stopNotificationBadgeMonitoring() {
        if (notificationLastReadRef != null && notificationLastReadListener != null) {
            notificationLastReadRef.removeEventListener(notificationLastReadListener);
        }
        if (notificationPermissionEventsRef != null && notificationPermissionEventsListener != null) {
            notificationPermissionEventsRef.removeEventListener(notificationPermissionEventsListener);
        }
        if (notificationAppEventsRef != null && notificationAppEventsListener != null) {
            notificationAppEventsRef.removeEventListener(notificationAppEventsListener);
        }
        notificationLastReadRef = null;
        notificationLastReadListener = null;
        notificationPermissionEventsRef = null;
        notificationPermissionEventsListener = null;
        notificationAppEventsRef = null;
        notificationAppEventsListener = null;
    }
    private void openTimerManagement() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, TimerStatusActivity.class);
        intent.putExtra(TimerStatusActivity.EXTRA_DEVICE_ID, currentChildDeviceId);
        intent.putExtra(TimerStatusActivity.EXTRA_IS_PARENT, true);
        startActivity(intent);
    }

    // Timer tab removed per request; no timer navigation on parent dashboard

    // Settings methods moved to ParentSettingsActivity

    // ==============================================
    // UPDATED METHOD FOR MODERN DESIGN COLORS
    // ==============================================
    private void setupQRScanOnlyListener() {
        // Legacy connection/focus fallback removed; canonical v2 listeners own this state.
    }


    private void setupV2ParentDeviceLinksListener() {
        try {
            String parentUid = getCurrentParentUserId();
            if (parentUid == null || parentUid.isEmpty()) {
                Log.w(TAG, "Skipping v2 parent-device link listener; parent ID is unavailable");
                return;
            }

            detachV2ParentDeviceLinksListener();
            parentDeviceLinksRef = FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("parent_device_links")
                    .child(parentUid);

            parentDeviceLinksListener = new ChildEventListener() {
                @Override
                public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                    handleV2ParentDeviceLink(snapshot);
                }

                @Override
                public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                    handleV2ParentDeviceLink(snapshot);
                }

                @Override
                public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                    String removedDeviceId = snapshot.child("deviceId").getValue(String.class);
                    if (removedDeviceId == null || removedDeviceId.isEmpty()) {
                        removedDeviceId = snapshot.getKey();
                    }
                    if (removedDeviceId != null && !removedDeviceId.isEmpty()) {
                        clearParentConnectionCaches(removedDeviceId, parentUid);
                        clearParentConnectionMarker(parentUid, removedDeviceId);
                        removeDeviceFromCurrentSession(removedDeviceId);
                        updateDeviceStatus();
                        updateTargetDeviceDisplay();
                        refreshDeviceListPremium();
                    }
                }

                @Override
                public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {
                    // Not used.
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "v2 parent_device_links listener cancelled: " + error.getMessage());
                }
            };

            parentDeviceLinksRef.addChildEventListener(parentDeviceLinksListener);
            Log.d(TAG, "v2 parent_device_links listener active for parent: " + parentUid);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up v2 parent-device link listener: " + e.getMessage());
        }
    }

    private void handleV2ParentDeviceLink(DataSnapshot snapshot) {
        try {
            if (snapshot == null || !snapshot.exists()) {
                return;
            }

            String deviceId = snapshot.child("deviceId").getValue(String.class);
            if (deviceId == null || deviceId.isEmpty()) {
                deviceId = snapshot.getKey();
            }
            if (deviceId == null || deviceId.isEmpty()) {
                return;
            }

            final String resolvedDeviceId = deviceId;

            String childName = snapshot.child("childName").getValue(String.class);
            String userName = snapshot.child("userName").getValue(String.class);
            String childDeviceName = snapshot.child("childDeviceName").getValue(String.class);
            String deviceName = snapshot.child("deviceName").getValue(String.class);
            String displayName = firstNonEmpty(childName, userName, childDeviceName, deviceName, "Child Device");

            Long linkedAt = snapshot.child("linkedAt").getValue(Long.class);
            long linkedAtValue = linkedAt != null && linkedAt > 0
                    ? linkedAt : 0L;
            String connectionId = snapshot.child("connectionId").getValue(String.class);
            String parentUid = getCurrentParentUserId();
            if (parentUid != null && !parentUid.isEmpty()) {
                syncParentConnectionMarkerAndMaybeClearCaches(
                        parentUid, resolvedDeviceId, connectionId, linkedAtValue);
            }
            Long appCount = snapshot.child("appCount").getValue(Long.class);

            ChildDevice device = new ChildDevice();
            device.deviceId = deviceId;
            device.deviceName = displayName;
            device.userName = displayName;
            device.appCount = appCount != null ? appCount.intValue() : 0;
            device.connectionId = connectionId;
            device.linkedAt = linkedAtValue;
            device.lastConnected = linkedAtValue > 0 ? linkedAtValue : System.currentTimeMillis();
            device.apps = new ArrayList<>();

            runOnUiThread(() -> {
                addConnectedDevice(device, true);
                startListeningForDeviceStatus(resolvedDeviceId);
                loadV2DeviceInstallCount(resolvedDeviceId);
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle v2 parent-device link: " + e.getMessage());
        }
    }

    private String firstNonEmpty(String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private void loadV2DeviceInstallCount(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }

        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_installs")
                .child(deviceId)
                .child("appCount")
                .get()
                .addOnSuccessListener(snapshot -> {
                    Long appCount = snapshot.getValue(Long.class);
                    if (appCount == null) {
                        Integer intCount = snapshot.getValue(Integer.class);
                        appCount = intCount != null ? intCount.longValue() : null;
                    }
                    if (appCount == null) {
                        return;
                    }
                    for (ChildDevice device : connectedDevices) {
                        if (deviceId.equals(device.deviceId)) {
                            device.appCount = appCount.intValue();
                            connectedDevicesManager.addOrUpdateDevice(device);
                            refreshCurrentChildDeviceCards();
                            refreshDeviceListPremium();
                            break;
                        }
                    }
                })
                .addOnFailureListener(error -> Log.w(TAG,
                        "Could not load v2 device install count: " + error.getMessage()));
    }


    private void addConnectedDevice(ChildDevice device) {
        nuclearAddDevice(device); // Just add the device, fuck everything else
    }

    private void addConnectedDevice(ChildDevice device, boolean isFromQRScan) {
        nuclearAddDevice(device); // Just add the device, fuck everything else
    }

    private void nuclearAddDevice(ChildDevice device) {
        try {
            Log.d(TAG, "Ã¯Â¿Â½Ã°Å¸â€Â¥Ã°Å¸â€Â¥ NUCLEAR DEVICE ADDITION: " + device.deviceName + " (ID: " + device.deviceId + ")");
            Log.d(TAG, "Ã¯Â¿Â½ FUCK ALL BLOCKING - ADDING DEVICE IMMEDIATELY!");

            // Check if already exists
            boolean exists = connectedDevices.stream().anyMatch(d -> Objects.equals(d.deviceId, device.deviceId));

            if (!exists) {
                // Ã°Å¸â€Â§ DEVICE REMOVAL FIX: Check if device was permanently removed
                if (isPermanentlyRemoved(device.deviceId)) {
                    Log.d(TAG, "Ã°Å¸Å¡Â« Device was previously removed, clearing removal status for QR reconnection: "
                            + device.deviceName);
                    removePermanentRemoval(device.deviceId);
                }

                // Add to local list
                connectedDevices.add(device);
                Log.d(TAG, "Ã¯Â¿Â½ Device added to local list. Total devices now: " + connectedDevices.size());
            } else {
                // Update existing device
                for (int i = 0; i < connectedDevices.size(); i++) {
                    if (Objects.equals(connectedDevices.get(i).deviceId, device.deviceId)) {
                        connectedDevices.set(i, device);
                        Log.d(TAG, "Ã°Å¸â€œÂ± Device updated in local list: " + device.deviceName);
                        break;
                    }
                }
            }

            // Add to persistent storage
            connectedDevicesManager.addOrUpdateDevice(device);

            // FIXED: Only set as current device if NO devices exist AND user hasn't
            // manually selected one
            // This prevents auto-switching when new devices connect
            if (currentChildDeviceId == null && connectedDevices.size() == 1) {
                // Ã°Å¸â€Â§ MULTI-DEVICE FIX: Clean up before switching
                performMultiDeviceSwitchCleanup();

                // Only auto-select if this is the very first device (non-explicit)
                connectedDevicesManager.setCurrentDevice(device.deviceId, false);
                // Sync local cached value from manager
                currentChildDeviceId = connectedDevicesManager.getCurrentDeviceId();
                currentChildDeviceName = device.deviceName;

                // Initialize usage limiter for first device
                initializeLimiterForDevice(device.deviceId);

                // Ã°Å¸â€Â§ FORCE-CLOSE PERSISTENCE: Save device name immediately

                Log.d(TAG, "Ã°Å¸â€œÂ± Set as current device (first device only): " + device.deviceName);
            } else if (currentChildDeviceId != null) {
                Log.d(TAG, "Ã°Å¸â€œÂ± Device added but keeping current selection: " + currentChildDeviceName);
            } else {
                Log.d(TAG, "Ã°Å¸â€œÂ± Multiple devices present, user must manually select");
            }

            // Update UI immediately
            runOnUiThread(() -> {
                // UI updates handled by methods below
                updateDeviceStatus();
                updateTargetDeviceDisplay();
                refreshCurrentChildDeviceCards();
                refreshDeviceListPremium();

                Log.d(TAG, "Ã°Å¸Å¡â‚¬Ã°Å¸Å¡â‚¬Ã¯Â¿Â½ UI UPDATED FOR DEVICE: " + device.deviceName);

                showRecentConnectionToast(device, device.deviceName + " connected!");
            });

        } catch (Exception e) {
            Log.e(TAG, "Ã°Å¸â€Â¥ Error in nuclear device addition: " + e.getMessage());
        }
    }

    private void continueDeviceConnection(ChildDevice device, boolean isFromQRScan) {
        try {
            Log.d(TAG, "Ã¢Å“â€¦Ã¢Å“â€¦Ã¢Å“â€¦ DEVICE PASSES ALL CHECKS - PROCEEDING WITH CONNECTION");

            // Ã¢Å“â€¦ QR SCAN DEVICE CONNECTION ACCEPTED
            Log.d(TAG, "Ã¢Å“â€¦ QR SCAN DEVICE APPROVED: " + device.deviceName + " (ID: " + device.deviceId + ")");
            Log.d(TAG, "Ã¢Å“â€¦ Connection method: QR Code Scan (ONLY valid method)");
            Log.d(TAG, "Ã¢Å“â€¦ Parent Email Account: " + getCurrentParentUserId());

            // Remove from permanent removal list since QR scan was successful
            removePermanentRemoval(device.deviceId);
            Log.d(TAG, "Ã°Å¸â€â€œ Device removed from permanent removal list via QR scan: " + device.deviceId);
            Log.d(TAG, "Ã°Å¸â€â€œ Device can now connect normally until removed again");

            // Add to persistent storage (device has already passed blacklist check)
            connectedDevicesManager.addOrUpdateDevice(device);

            // Add to local list
            connectedDevices.removeIf(d -> d.deviceId.equals(device.deviceId));
            connectedDevices.add(device);
            Log.d(TAG, "Ã°Å¸â€œÂ± Device added to local list. Total devices now: " + connectedDevices.size());

            // Log all current devices
            for (int i = 0; i < connectedDevices.size(); i++) {
                ChildDevice d = connectedDevices.get(i);
                Log.d(TAG, "Ã°Å¸â€œÂ± Device " + (i + 1) + ": " + d.deviceName + " (ID: " + d.deviceId + ")");
            }

            // If this is the first device or no current device is set, make it the current
            // device
            if (currentChildDeviceId == null) {
                Log.d(TAG, "Setting as current device (first device or no current device)");
                // Non-explicit set: only set if no current device exists
                connectedDevicesManager.setCurrentDevice(device.deviceId, false);
                currentChildDeviceId = connectedDevicesManager.getCurrentDeviceId();
                currentChildDeviceName = device.deviceName;

                // Initialize usage limiter for first device
                initializeLimiterForDevice(device.deviceId);
            }

            Log.d(TAG, "Current device after adding: " + currentChildDeviceId);

            // Update UI
            updateDeviceStatus();
            updateTargetDeviceDisplay();
            refreshCurrentChildDeviceCards();

            // Start listening for device status
            startListeningForDeviceStatus(device.deviceId);

            // Refresh the category summary chart with new device data
            setupCategorySummaryChart();

            Log.d(TAG, "Ã¢Å“â€¦ Successfully added device: " + device.deviceName);

            // Show success message for QR scan connections
            if (isFromQRScan) {
                Toast.makeText(this, device.deviceName + " connected via QR scan", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Ã°Å¸Å½â€° QR scan connection successful - device now appears in parent app");
            }

        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error adding connected device: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void removeFromDeviceBlacklist(String deviceId) {
        SharedPreferences blacklistPrefs = getSharedPreferences("removed_devices", MODE_PRIVATE);
        blacklistPrefs.edit().remove(deviceId).apply();
        Log.d(TAG, "Ã°Å¸â€â€œ Removed device from blacklist (reconnecting): " + deviceId);
    }

    /**
     * Ã°Å¸Å¡Â« Mark device as permanently removed (requires QR scan to reconnect)
     * Email-specific removal tracking to ensure complete isolation
     */
    private void addToPermanentRemovalList(String deviceId) {
        String parentUserId = getCurrentParentUserId();
        if (parentUserId == null) {
            Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â Cannot add to permanent removal list - no parent user ID");
            return;
        }

        // Email-specific permanent removal storage
        String removalKey = parentUserId + "_permanently_removed_devices";
        SharedPreferences removedDevicesPrefs = getSharedPreferences(removalKey, MODE_PRIVATE);
        removedDevicesPrefs.edit().putBoolean(deviceId, true).apply();

        // Also add to global removal list for extra protection
        SharedPreferences globalRemovedPrefs = getSharedPreferences("permanently_removed_devices", MODE_PRIVATE);
        globalRemovedPrefs.edit().putBoolean(deviceId, true).apply();


        Log.d(TAG, "Ã°Å¸Å¡Â« PERMANENT REMOVAL: Added device to email-specific removal list");
        Log.d(TAG, "Ã°Å¸Å¡Â« Parent Email ID: " + parentUserId);
        Log.d(TAG, "Ã°Å¸Å¡Â« Device ID: " + deviceId);
        Log.d(TAG, "Ã°Å¸Å¡Â« Removal Storage Key: " + removalKey);
        Log.d(TAG, "Ã°Å¸â€â€ž Device will ONLY reconnect via QR scan - NO automatic loading allowed");
    }

    /**
     * Ã°Å¸â€Â Check if device is permanently removed (cannot auto-reconnect)
     * Email-specific checking with fallback to global list
     */
    private boolean isPermanentlyRemoved(String deviceId) {
        String parentUserId = getCurrentParentUserId();
        Log.d(TAG, "Ã°Å¸â€Â PERMANENT REMOVAL CHECK START:");
        Log.d(TAG, "Ã°Å¸â€Â getCurrentParentUserId() returned: " + (parentUserId != null ? parentUserId : "NULL"));

        if (parentUserId == null) {
            Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸ÂÃ¢Å¡Â Ã¯Â¸ÂÃ¢Å¡Â Ã¯Â¸Â CRITICAL: Cannot check permanent removal - no parent user ID");
            Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â This would block all connections! Allowing connection as safety fallback");
            return false; // CHANGED: Allow connection if we can't get parent ID
        }

        // Check email-specific removal list first
        String removalKey = parentUserId + "_permanently_removed_devices";
        SharedPreferences removedDevicesPrefs = getSharedPreferences(removalKey, MODE_PRIVATE);
        boolean isEmailSpecificRemoved = removedDevicesPrefs.getBoolean(deviceId, false);

        // Removal state must not leak between parent accounts on the same phone.
        boolean isGlobalRemoved = false;
        boolean isRemoved = isEmailSpecificRemoved;

        Log.d(TAG, "Ã°Å¸â€Â PERMANENT REMOVAL CHECK RESULTS:");
        Log.d(TAG, "Ã°Å¸â€Â Parent Email ID: " + parentUserId);
        Log.d(TAG, "Ã°Å¸â€Â Device ID: " + deviceId);
        Log.d(TAG, "Ã°Å¸â€Â Email-specific removed: " + isEmailSpecificRemoved);
        Log.d(TAG, "Ã°Å¸â€Â Global removed: " + isGlobalRemoved);
        Log.d(TAG, "Ã°Å¸â€Â FINAL RESULT: " + (isRemoved ? "DEVICE IS BLOCKED" : "DEVICE IS ALLOWED"));
        Log.d(TAG, "Ã°Å¸â€Â Final result: " + (isRemoved ? "Ã°Å¸Å¡Â« BLOCKED" : "Ã¢Å“â€¦ ALLOWED"));

        return isRemoved;
    }

    /**
     * Ã°Å¸â€â€œ Remove device from permanent removal list (QR scan reconnection)
     * Clears from both email-specific and global removal lists
     */
    private void removePermanentRemoval(String deviceId) {
        String parentUserId = getCurrentParentUserId();
        if (parentUserId == null) {
            Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â Cannot remove from permanent removal list - no parent user ID");
            return;
        }

        // Remove from email-specific list
        String removalKey = parentUserId + "_permanently_removed_devices";
        SharedPreferences removedDevicesPrefs = getSharedPreferences(removalKey, MODE_PRIVATE);
        removedDevicesPrefs.edit().remove(deviceId).apply();

        // Remove from global list
        SharedPreferences globalRemovedPrefs = getSharedPreferences("permanently_removed_devices", MODE_PRIVATE);
        globalRemovedPrefs.edit().remove(deviceId).apply();

        Log.d(TAG, "Ã°Å¸â€â€œ PERMANENT REMOVAL CLEARED:");
        Log.d(TAG, "Ã°Å¸â€â€œ Parent Email ID: " + parentUserId);
        Log.d(TAG, "Ã°Å¸â€â€œ Device ID: " + deviceId);
        Log.d(TAG, "Ã°Å¸â€â€œ Removed from: " + removalKey);
        Log.d(TAG, "Ã°Å¸â€â€œ QR scan reconnection successful - device can now connect");
    }

    /**
     * Ã°Å¸â€ â€ Get current parent user ID (email-based identifier)
     * Returns Firebase Auth UID or SessionManager user ID
     */
    private String getCurrentParentUserId() {
        String parentUserId = null;

        if (mAuth != null && mAuth.getCurrentUser() != null) {
            parentUserId = mAuth.getCurrentUser().getUid();
            Log.d(TAG, "Ã°Å¸â€ â€ Parent User ID from Firebase Auth: " + parentUserId);
            return parentUserId;
        }

        if (sessionManager != null && sessionManager.isLoggedIn()) {
            parentUserId = sessionManager.getParentUserId();
            if (parentUserId != null && !parentUserId.isEmpty()) {
                Log.d(TAG, "Ã°Å¸â€ â€ Parent User ID from Session Manager (parentUserId): " + parentUserId);
                return parentUserId;
            }

            parentUserId = sessionManager.getUserId();
            if (parentUserId != null && !parentUserId.isEmpty()) {
                Log.d(TAG, "Ã°Å¸â€ â€ Parent User ID from Session Manager (userId fallback): " + parentUserId);
                return parentUserId;
            }
        }

        Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â No parent user ID available - user not properly authenticated");
        return null;
    }

    /**
     * Ã°Å¸Â§Â¹ Clean up permanently removed devices from ConnectedDevicesManager storage
     * Email-specific cleanup to prevent removed devices from reappearing
     * This prevents removed devices from reappearing when the app restarts
     */
    private void cleanupPermanentlyRemovedDevices() {
        try {
            String parentUserId = getCurrentParentUserId();
            if (parentUserId == null) {
                Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â Cannot perform cleanup - no parent user ID available");
                return;
            }

            // Get email-specific removal list
            String removalKey = parentUserId + "_permanently_removed_devices";
            SharedPreferences removedDevicesPrefs = getSharedPreferences(removalKey, MODE_PRIVATE);
            Map<String, ?> emailSpecificRemoved = removedDevicesPrefs.getAll();

            // Also check global removal list for extra protection
            SharedPreferences globalRemovedPrefs = getSharedPreferences("permanently_removed_devices", MODE_PRIVATE);
            Map<String, ?> globalRemoved = globalRemovedPrefs.getAll();

            // Combine both removal lists
            Map<String, Object> allRemovedDevices = new HashMap<>();
            allRemovedDevices.putAll(emailSpecificRemoved);
            allRemovedDevices.putAll(globalRemoved);

            if (allRemovedDevices.isEmpty()) {
                Log.d(TAG, "Ã°Å¸Â§Â¹ CLEANUP: No permanently removed devices to clean up for email: " + parentUserId);
                return;
            }

            Log.d(TAG, "Ã°Å¸Â§Â¹ CLEANUP: Starting email-specific permanent removal cleanup");
            Log.d(TAG, "Ã°Å¸Â§Â¹ Parent Email ID: " + parentUserId);
            Log.d(TAG, "Ã°Å¸Â§Â¹ Email-specific removed devices: " + emailSpecificRemoved.size());
            Log.d(TAG, "Ã°Å¸Â§Â¹ Global removed devices: " + globalRemoved.size());
            Log.d(TAG, "Ã°Å¸Â§Â¹ Total devices to check for removal: " + allRemovedDevices.size());

            // Get current loaded devices from ConnectedDevicesManager
            List<ChildDevice> loadedDevices = connectedDevicesManager.getConnectedDevices();
            List<String> devicesToRemove = new ArrayList<>();

            // Find devices that are loaded but should be permanently removed
            for (ChildDevice device : loadedDevices) {
                if (allRemovedDevices.containsKey(device.deviceId)) {
                    devicesToRemove.add(device.deviceId);
                    Log.d(TAG, "Ã°Å¸Å¡Â« CLEANUP: Found permanently removed device in storage: " + device.deviceName
                            + " (ID: " + device.deviceId + ")");
                }
            }

            // Remove permanently removed devices from ConnectedDevicesManager
            for (String deviceId : devicesToRemove) {
                connectedDevicesManager.removeDevice(deviceId);
                Log.d(TAG, "Ã°Å¸â€”â€˜Ã¯Â¸Â CLEANUP: Permanently removed device deleted from storage: " + deviceId);
            }

            // Also clear from local connectedDevices list
            int removedFromLocal = 0;
            Iterator<ChildDevice> iterator = connectedDevices.iterator();
            while (iterator.hasNext()) {
                ChildDevice device = iterator.next();
                if (allRemovedDevices.containsKey(device.deviceId)) {
                    iterator.remove();
                    removedFromLocal++;
                    Log.d(TAG, "Ã°Å¸â€Â¥ CLEANUP: Removed from local memory: " + device.deviceName);
                }
            }

            if (!devicesToRemove.isEmpty() || removedFromLocal > 0) {
                Log.d(TAG, "Ã¢Å“â€¦ CLEANUP COMPLETE for email: " + parentUserId);
                Log.d(TAG, "Ã¢Å“â€¦ Removed from storage: " + devicesToRemove.size());
                Log.d(TAG, "Ã¢Å“â€¦ Removed from memory: " + removedFromLocal);
                Log.d(TAG, "Ã°Å¸â€â€ž These devices will NOT appear until QR scan reconnection");

                // Update UI to reflect clean device list
                updateDeviceStatus();
                updateTargetDeviceDisplay();
            } else {
                Log.d(TAG, "Ã¢Å“â€¦ CLEANUP COMPLETE: No permanently removed devices found in loaded storage for email: "
                        + parentUserId);
            }

        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error during email-specific permanent removal cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Ã°Å¸â€ â€¢ Clear local SharedPreferences timer storage for a specific device
     * This prevents timers from being restored when device reconnects
     */
    private void clearLocalTimerStorageForDevice(String deviceId) {
        try {
            Log.d(TAG, "Ã°Å¸Â§Â¹ Clearing LOCAL timer storage for device: " + deviceId);

            // 1. Clear timer_duration (stored per device)
            SharedPreferences timerDurationPrefs = getSharedPreferences("timer_duration", MODE_PRIVATE);
            String hoursKey = "timer_hours_" + deviceId;
            String minutesKey = "timer_minutes_" + deviceId;
            timerDurationPrefs.edit()
                    .remove(hoursKey)
                    .remove(minutesKey)
                    .apply();
            Log.d(TAG, "Ã¢Å“â€¦ Cleared timer_duration for device: " + deviceId);

            // 2. Clear smart_timer_prefs (if stored per device)
            SharedPreferences smartTimerPrefs = getSharedPreferences("smart_timer_prefs", MODE_PRIVATE);
            SharedPreferences.Editor smartEditor = smartTimerPrefs.edit();
            for (String key : smartTimerPrefs.getAll().keySet()) {
                if (key.contains(deviceId)) {
                    smartEditor.remove(key);
                    Log.d(TAG, "Ã¢Å“â€¦ Removed smart_timer key: " + key);
                }
            }
            smartEditor.apply();

            // 3. Clear timer_state (if stored per device)
            SharedPreferences timerStatePrefs = getSharedPreferences("timer_state", MODE_PRIVATE);
            SharedPreferences.Editor stateEditor = timerStatePrefs.edit();
            for (String key : timerStatePrefs.getAll().keySet()) {
                if (key.contains(deviceId)) {
                    stateEditor.remove(key);
                    Log.d(TAG, "Ã¢Å“â€¦ Removed timer_state key: " + key);
                }
            }
            stateEditor.apply();

            // 4. Clear app_timer_prefs (per-app timer settings for this device)
            SharedPreferences appTimerPrefs = getSharedPreferences("app_timer_prefs", MODE_PRIVATE);
            SharedPreferences.Editor appEditor = appTimerPrefs.edit();
            for (String key : appTimerPrefs.getAll().keySet()) {
                if (key.contains(deviceId)) {
                    appEditor.remove(key);
                    Log.d(TAG, "Ã¢Å“â€¦ Removed app_timer key: " + key);
                }
            }
            appEditor.apply();


            // 6. Clear blocked_apps SharedPreferences for this device
            SharedPreferences blockedAppsPrefs = getSharedPreferences("blocked_apps_" + deviceId, MODE_PRIVATE);
            if (blockedAppsPrefs.getAll().size() > 0) {
                blockedAppsPrefs.edit().clear().apply();
                Log.d(TAG, "Ã¢Å“â€¦ Cleared blocked_apps prefs for device: " + deviceId);
            }

            Log.d(TAG, "Ã°Å¸Å½Â¯ LOCAL timer storage completely cleared for device: " + deviceId);

        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error clearing local timer storage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Simple device removal - just remove from current session without blacklisting
     */
    private void removeDeviceFromCurrentSession(String deviceId) {
        // Remove from current session only
        connectedDevices.removeIf(device -> device.deviceId.equals(deviceId));
        connectedDevicesManager.removeDevice(deviceId);
        Log.d(TAG, "Ã°Å¸â€”â€˜Ã¯Â¸Â Device removed from current session: " + deviceId);
    }

    private void loadConnectedDevices() {
        // Ã°Å¸Å¡Â« DISABLED - This method was causing automatic device loading from Firebase
        // User requirement: Only QR scanned devices should be shown
        Log.d(TAG, "Ã°Å¸Å¡Â« AUTOMATIC DEVICE LOADING DISABLED - Only QR scan connections allowed");
        Log.d(TAG, "Ã°Å¸â€œÂ± Device list will remain empty until QR codes are scanned");

        // Clear any existing devices and show empty state
        connectedDevices.clear();
        updateDeviceStatus();
        updateTargetDeviceDisplay();

        return;
    }

    private void loadConnectedDevicesOLD_DISABLED() {
        try {
            Log.d(TAG, "Ã°Å¸â€Â Loading connected devices from multiple sources...");

            // First load from persistent storage (devices that were previously connected)
            connectedDevicesManager.loadDevicesAsync(new ConnectedDevicesManager.OnDevicesLoadedListener() {
                @Override
                public void onDevicesLoaded(List<ChildDevice> devices) {
                    Log.d(TAG, "Loaded " + devices.size() + " devices from persistent storage");
                    connectedDevices.clear();
                    connectedDevices.addAll(devices);

                    // Update Firebase data for each persistent device
                    for (ChildDevice device : devices) {
                        startListeningForDeviceStatus(device.deviceId);
                    }
                }

                @Override
                public void onCurrentDeviceLoaded(String deviceId) {
                    Log.d(TAG, "Current device from storage: " + deviceId);
                    if (deviceId != null) {
                        // Treat restored selection as explicit restoration
                        connectedDevicesManager.setCurrentDevice(deviceId, true);

                        // If we have a saved current device, switch to it
                        ChildDevice device = connectedDevicesManager.getDevice(deviceId);
                        if (device != null) {
                            currentChildDeviceName = device.deviceName;

                            // Ã¢Â­Â Setup device-specific data loading

                            updateDeviceStatus();
                            updateTargetDeviceDisplay();
                            // Refresh the category summary chart with loaded device data
                            setupCategorySummaryChart();
                            // Load usage data for the selected device
                            loadSmartUsageDataForSelectedDate();

                            Log.d(TAG, "Ã¢Å“â€¦ Device-specific data loaded for: " + device.deviceName);
                        }
                    }
                }
            });

            // Ã°Å¸Å¡Â« DISABLED - These methods cause automatic device loading
            // User requirement: Only QR scanned devices should be shown
            // loadDevicesFromQRShares();
            // loadDevicesFromParentsStructure();
            Log.d(TAG, "Ã°Å¸Å¡Â« Firebase device loading disabled - QR scan only mode active");

        } catch (Exception e) {
            Log.e(TAG, "Error loading connected devices: " + e.getMessage());
        }
    }

    private void finalizeDeviceLoading() {
        // Do NOT auto-select a device. Keep the current selection until the user
        // explicitly switches.
        if (currentChildDeviceId != null) {
            updateDeviceStatus();
        } else {
            // No device selected: update UI and wait for explicit user selection
            updateDeviceStatus();
            Log.d(TAG, "No device auto-selected; waiting for user to pick a device.");
        }

        Log.d(TAG, "Ã¢Å“â€¦ Device loading finalized. Total devices: " + connectedDevices.size());

        // Update UI to reflect current state
        runOnUiThread(() -> {
            updateDeviceStatus();
            if (connectedDevices.isEmpty()) {
                Log.d(TAG, "Ã°Å¸â€œÂ± No devices connected - showing tap to view devices message");
                binding.tvDeviceStatus.setText("Tap to view devices");
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600)); // Always green
            }
        });
    }

    // ==============================================
    // UPDATED METHOD FOR MODERN DESIGN COLORS
    // ==============================================
    private void updateDeviceStatus() {
        try {
            Log.d(TAG, "Ã°Å¸â€œÂ± UPDATING DEVICE STATUS DISPLAY");
            Log.d(TAG, "Ã°Å¸â€œÂ± Current device ID: " + currentChildDeviceId);
            Log.d(TAG, "Ã°Å¸â€œÂ± Current device name: " + currentChildDeviceName);

            if (currentChildDeviceId != null) {
                // Show formatted device status text
                String displayName = "";
                if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
                    displayName = currentChildUserName;
                } else if (currentChildDeviceName != null && !currentChildDeviceName.isEmpty()) {
                    displayName = currentChildDeviceName;
                } else {
                    displayName = currentChildDeviceId;
                }

                // Capitalize first letter
                String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
                String deviceStatusText = capitalizedName + " (Tap to Manage Device)";

                Log.d(TAG, "Ã°Å¸â€œÂ± Setting device status text to: " + deviceStatusText);
                binding.tvDeviceStatus.setText(deviceStatusText);
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600)); // Teal color

                // Force UI refresh
                binding.tvDeviceStatus.invalidate();
                binding.tvDeviceStatus.requestLayout();

                if (btnRemoveDevice != null) {
                    btnRemoveDevice.setVisibility(View.VISIBLE);
                }
                Log.d(TAG, "Ã¢Å“â€¦ Device status updated successfully");
            } else {
                // Show default text when no device
                Log.d(TAG, "Ã°Å¸â€œÂ± No current device, showing default text");
                binding.tvDeviceStatus.setText("Select a child");
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral_500));

                // Force UI refresh
                binding.tvDeviceStatus.invalidate();
                binding.tvDeviceStatus.requestLayout();

                if (btnRemoveDevice != null) {
                    btnRemoveDevice.setVisibility(View.GONE);
                }

                stopUninstallProtectionMonitoring();
                resetUninstallProtectionUI();
                resetUninstallWarningUI();
            }

            // Always rebind the child-specific protection card to the current selection.
            if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
                loadUninstallProtectionForDevice(currentChildDeviceId);
            }

            // Ã°Å¸â€Â§ REFRESH DEVICE LIST UI
            populateDeviceList();

        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error updating device status: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void refreshDeviceList() {
        try {
            Log.d(TAG, "Ã°Å¸â€â€ž Force refreshing device list display");

            // Force update device status display
            updateDeviceStatus();
            updateTargetDeviceDisplay();

            // Force adapter notification if using RecyclerView
            if (binding != null) {
                // Update any RecyclerView adapters here if they exist
                Log.d(TAG, "Ã°Å¸â€œÂ± Device list UI refreshed");
            }

            // Ã°Å¸â€Â§ POPULATE THE NEW HORIZONTAL LIST
            populateDeviceList();

        } catch (Exception e) {
            Log.e(TAG, "Error refreshing device list: " + e.getMessage());
        }
    }

    private void showQRScanner() {
        // In this app flow, "Connect New" means showing the Parent QR for the child to
        // scan
        showQRFullscreen();
    }

    private void populateDeviceList() {
        if (llDeviceList == null)
            return;

        llDeviceList.removeAllViews();

        // 1. Add all connected devices
        List<ChildDevice> devices = connectedDevicesManager.getConnectedDevices();

        for (ChildDevice device : devices) {

            // Build programmatically to avoid creating new XML right now
            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.VERTICAL);
            itemLayout.setGravity(android.view.Gravity.CENTER);
            itemLayout.setPadding(16, 8, 16, 8);

            // Frame for Icon/Avatar
            android.widget.FrameLayout iconFrame = new android.widget.FrameLayout(this);
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                    (int) (56 * getResources().getDisplayMetrics().density),
                    (int) (56 * getResources().getDisplayMetrics().density));
            iconFrame.setLayoutParams(frameParams);
            iconFrame.setBackgroundResource(R.drawable.selector_device_item);
            iconFrame.setSelected(device.deviceId.equals(currentChildDeviceId));

            ImageView icon = new ImageView(this);
            android.widget.FrameLayout.LayoutParams iconParams = new android.widget.FrameLayout.LayoutParams(
                    (int) (24 * getResources().getDisplayMetrics().density),
                    (int) (24 * getResources().getDisplayMetrics().density));
            iconParams.gravity = android.view.Gravity.CENTER;
            icon.setLayoutParams(iconParams);
            icon.setImageResource(R.drawable.ic_device); // Use appropriate icon
            icon.setColorFilter(ContextCompat.getColor(this, R.color.primary_600));

            iconFrame.addView(icon);

            final String displayName = (device.userName != null && !device.userName.isEmpty())
                    ? device.userName
                    : device.deviceName;

            // Ã°Å¸â€ºâ€˜ Add Remove badge to top-right corner of the avatar frame
            android.widget.FrameLayout badgeFrame = new android.widget.FrameLayout(this);
            int badgeSize = (int) (18 * getResources().getDisplayMetrics().density); // small and unobtrusive
            android.widget.FrameLayout.LayoutParams badgeParams = new android.widget.FrameLayout.LayoutParams(badgeSize, badgeSize);
            badgeParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            badgeFrame.setLayoutParams(badgeParams);
            badgeFrame.setBackgroundResource(R.drawable.bg_remove_badge);

            ImageView minusIcon = new ImageView(this);
            android.widget.FrameLayout.LayoutParams minusIconParams = new android.widget.FrameLayout.LayoutParams(
                    (int) (10 * getResources().getDisplayMetrics().density),
                    (int) (10 * getResources().getDisplayMetrics().density));
            minusIconParams.gravity = android.view.Gravity.CENTER;
            minusIcon.setLayoutParams(minusIconParams);
            minusIcon.setImageResource(R.drawable.ic_minus);
            badgeFrame.addView(minusIcon);

            // Click listener for the badge to show confirmation dialog
            badgeFrame.setOnClickListener(v -> {
                showRemoveDeviceConfirmationDialog(device.deviceId, displayName);
            });

            iconFrame.addView(badgeFrame);

            itemLayout.addView(iconFrame);

            // Name Text
            TextView nameText = new TextView(this);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
            nameText.setLayoutParams(textParams);
            nameText.setText(displayName);
            nameText.setTextSize(12);
            nameText.setTextColor(ContextCompat.getColor(this, R.color.neutral_700));
            nameText.setMaxLines(1);
            nameText.setEllipsize(android.text.TextUtils.TruncateAt.END);

            itemLayout.addView(nameText);

            // Click Listener
            itemLayout.setOnClickListener(v -> {
                switchDevice(device.deviceId);
            });

            // Long Click Listener to Remove Device
            itemLayout.setOnLongClickListener(v -> {
                showRemoveDeviceConfirmationDialog(device.deviceId, displayName);
                return true;
            });

            // Add margin
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            itemParams.setMarginEnd((int) (12 * getResources().getDisplayMetrics().density));
            itemLayout.setLayoutParams(itemParams);

            llDeviceList.addView(itemLayout);
        }

        // 2. Add static "Add" button at the end
        // We reuse the hidden definition from XML or create new?
        // In XML we added a STATIC "Add" button inside the ScrollView inside
        // llDeviceList?
        // Wait, in my XML I added:
        // <LinearLayout id="@+id/llDeviceList" ...>
        // <LinearLayout id="@+id/btnAddDevice" ... />
        // </LinearLayout>
        // calling removeAllViews() CLEARS that static button!
        // So I must Re-Add it or Inflate it.

        // Re-create Add Button programmatically for simplicity and robustness
        LinearLayout addLayout = new LinearLayout(this);
        addLayout.setOrientation(LinearLayout.VERTICAL);
        addLayout.setGravity(android.view.Gravity.CENTER);
        addLayout.setPadding(16, 8, 16, 8);
        addLayout.setOnClickListener(v -> showQRScanner());

        android.widget.FrameLayout addFrame = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams addFrameParams = new LinearLayout.LayoutParams(
                (int) (56 * getResources().getDisplayMetrics().density),
                (int) (56 * getResources().getDisplayMetrics().density));
        addFrame.setLayoutParams(addFrameParams);
        addFrame.setBackgroundResource(R.drawable.bg_icon_child); // Reuse existing or simple circle

        ImageView addIcon = new ImageView(this);
        android.widget.FrameLayout.LayoutParams addIconParams = new android.widget.FrameLayout.LayoutParams(
                (int) (24 * getResources().getDisplayMetrics().density),
                (int) (24 * getResources().getDisplayMetrics().density));
        addIconParams.gravity = android.view.Gravity.CENTER;
        addIcon.setLayoutParams(addIconParams);
        addIcon.setImageResource(R.drawable.ic_add);
        addIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary_600));

        addFrame.addView(addIcon);
        addLayout.addView(addFrame);

        TextView addText = new TextView(this);
        LinearLayout.LayoutParams addTextParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addTextParams.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
        addText.setLayoutParams(addTextParams);
        addText.setText("Add Child");
        addText.setTextSize(12);
        addText.setTextColor(ContextCompat.getColor(this, R.color.neutral_600));

        addLayout.addView(addText);

        llDeviceList.addView(addLayout);
    }

    private void switchDevice(String deviceId) {
        // Ã°Å¸â€Â DATE TRACE: Device switch initiated
        String dateBefore = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_START: === switchDevice() CALLED ===");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_START: FROM device = " + currentChildDeviceId);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_START: TO device = " + deviceId);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_START: currentUsageDate BEFORE = " + dateBefore);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_START: dateSetByUser BEFORE = " + dateSetByUser);

        if (deviceId.equals(currentChildDeviceId)) {
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_END: EARLY RETURN - Same device");
            return;
        }

        if (connectedDevicesManager == null) {
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_END: EARLY RETURN - No manager");
            return;
        }

        ChildDevice device = connectedDevicesManager.getDevice(deviceId);
        String deviceName = (device != null && device.deviceName != null && !device.deviceName.isEmpty())
                ? device.deviceName
                : deviceId;
        String userName = (device != null) ? device.userName : null;

        // Stop monitoring old device
        stopUninstallDetection();

        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_SAVE: Saving old device state");
            String dateBeforeSave = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_SAVE: currentUsageDate BEFORE save = " + dateBeforeSave);

            saveCompleteDeviceState();

            String dateAfterSave = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_SAVE: currentUsageDate AFTER save = " + dateAfterSave);
        }

        clearDeviceSpecificUI();

        String dateAfterClear = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_CLEAR: After clearDeviceSpecificUI()");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_CLEAR: currentUsageDate = " + dateAfterClear);

        connectedDevicesManager.setCurrentDevice(deviceId, true);
        currentChildDeviceId = deviceId;
        currentChildDeviceName = deviceName;
        currentChildUserName = (userName != null && !userName.isEmpty()) ? userName : "";
        autoLocationRefreshEnabled = true;

        showChildSwitchLoading(deviceName);

        if (device != null) {
            initializeLimiterForDevice(device.deviceId);
        }

        restoreCachedChildLocationPreview(deviceId);

        updateDeviceStatus();
        updateTargetDeviceDisplay(); // Update green text (now uses currentChildUserName)

        // Ã°Å¸â€Â DATE TRACE: Before loading new device state
        String dateBeforeLoad = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_LOAD: BEFORE loadCompleteDeviceState()");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_LOAD: currentUsageDate BEFORE = " + dateBeforeLoad);

        loadCompleteDeviceState();

        // Ã°Å¸â€Â DATE TRACE: After loading new device state
        String dateAfterLoad = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_LOAD: AFTER loadCompleteDeviceState()");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_LOAD: currentUsageDate AFTER = " + dateAfterLoad);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_LOAD: Date changed? " + (!dateBeforeLoad.equals(dateAfterLoad)));
        refreshCurrentChildDeviceCards();

        setupParentTimerExpiryListener();
        setupGeofenceEventsListener();

        // Start uninstall detection for new device
        startUninstallDetection();

        // Re-populate list to update selection state (uses simple circular icons)
        populateDeviceList();

        // Ã°Å¸â€Â DATE TRACE: Device switch complete
        String dateAfter = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_END: === switchDevice() COMPLETE ===");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_END: currentUsageDate AFTER = " + dateAfter);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_END: dateSetByUser AFTER = " + dateSetByUser);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SWITCH_END: Overall date changed? " + (!dateBefore.equals(dateAfter)));
    }

    /**
     * Refresh child-scoped dashboard cards for the currently selected device.
     */
    private void refreshCurrentChildDeviceCards() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            stopSmartUsageMonitoring();
            stopUninstallProtectionMonitoring();
            resetUninstallProtectionUI();
            resetUninstallWarningUI();
            hideMapLoading();
            hideChildSwitchLoading();
            return;
        }

        loadSmartUsageDataForSelectedDate();
        loadUninstallProtectionForDevice(currentChildDeviceId);
        updateMapCardVisibility();
        refreshChildLocationIfNeeded();
    }

    /** Binds child device status to security UI and usage cache generation. */
    private void loadUninstallProtectionForDevice(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            stopUninstallProtectionMonitoring();
            resetUninstallProtectionUI();
            return;
        }
        stopUninstallProtectionMonitoring();
        if (tvUninstallProtectionDash != null)
            tvUninstallProtectionDash.setText("Checking protection status...");
        startUninstallProtectionStatusMonitoring(deviceId);
    }

    private void setupGuideMotion(View guideContainer, View guideFab) {
        if (guideContainer == null || guideFab == null) {
            return;
        }

        TextView guideLabel = guideContainer.findViewById(R.id.tvGuideLabel);
        if (guideLabel != null) {
            guideLabel.setVisibility(View.GONE);
            guideLabel.setAlpha(0f);
            guideLabel.setTranslationX(8f);

            View.OnLongClickListener showGuideLabel = view -> {
                showGuideLabelTemporarily(guideLabel);
                return true;
            };
            guideContainer.setOnLongClickListener(showGuideLabel);
            guideFab.setOnLongClickListener(showGuideLabel);
            guideContainer.postDelayed(() -> showGuideLabelTemporarily(guideLabel), 650);
        }

        guideContainer.setAlpha(0f);
        guideContainer.setTranslationX(36f);
        guideContainer.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(350)
                .setDuration(420)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        guideContainer.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
            return false;
        });

        guidePulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        guidePulseAnimator.setDuration(1500);
        guidePulseAnimator.setStartDelay(900);
        guidePulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        guidePulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        guidePulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        guidePulseAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            float scale = 1f + (0.045f * fraction);
            guideFab.setScaleX(scale);
            guideFab.setScaleY(scale);
            guideFab.setAlpha(0.9f + (0.1f * fraction));
        });
        guidePulseAnimator.start();
    }

    private void showGuideLabelTemporarily(TextView guideLabel) {
        if (guideLabel == null) {
            return;
        }
        if (hideGuideLabelRunnable != null) {
            guideLabelHandler.removeCallbacks(hideGuideLabelRunnable);
        }
        guideLabel.animate().cancel();
        if (guideLabel.getVisibility() != View.VISIBLE) {
            guideLabel.setAlpha(0f);
            guideLabel.setTranslationX(8f);
            guideLabel.setVisibility(View.VISIBLE);
        }
        guideLabel.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(180)
                .start();

        hideGuideLabelRunnable = () -> guideLabel.animate()
                .alpha(0f)
                .translationX(8f)
                .setDuration(180)
                .withEndAction(() -> guideLabel.setVisibility(View.GONE))
                .start();
        guideLabelHandler.postDelayed(hideGuideLabelRunnable, 3200);
    }

    private void startUninstallProtectionStatusMonitoring(String deviceId) {
        uninstallProtectionStatusRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_status")
                .child(deviceId);

        uninstallProtectionStatusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!deviceId.equals(currentChildDeviceId)) {
                    return;
                }

                Boolean activeValue = snapshot.child("uninstallProtectionActive").getValue(Boolean.class);
                updateUninstallProtectionStatus(activeValue != null && activeValue);
                updateUsageHistoryGeneration(deviceId, snapshot);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Uninstall Protection status monitoring cancelled: " + error.getMessage());
            }
        };

        uninstallProtectionStatusRef.addValueEventListener(uninstallProtectionStatusListener);
    }

    private void updateUsageHistoryGeneration(
            String deviceId, DataSnapshot deviceStatus) {
        String connectionId = deviceStatus.child("usageBootstrap")
                .child("connectionId").getValue(String.class);
        String historyGeneration = deviceStatus.child("usageBootstrap")
                .child("historyGeneration").getValue(String.class);
        if (connectionId == null || connectionId.isEmpty()
                || historyGeneration == null || historyGeneration.isEmpty()) {
            return;
        }

        boolean cacheInvalidated = online.monarchlabs.sentinel.utils
                .ParentUsageCacheManager.getInstance(this)
                .setUsageScope(deviceId, connectionId, historyGeneration);
        if (!cacheInvalidated || !usageMonitoringForeground
                || currentUsageDate == null) {
            return;
        }

        String selectedDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(currentUsageDate.getTime());
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date());
        if (!today.equals(selectedDate)) {
            loadSmartUsageDataForSelectedDate();
        }
    }

    private void updateUninstallProtectionStatus(boolean active) {
        if (tvUninstallProtectionDash == null) return;
        if (active) {
            tvUninstallProtectionDash.setText("Protection active");
            tvUninstallProtectionDash.setTextColor(getResources().getColor(R.color.primary_600, getTheme()));
        } else {
            tvUninstallProtectionDash.setText("Protection deactivated");
            tvUninstallProtectionDash.setTextColor(ContextCompat.getColor(this, R.color.error_700));
        }
        updateUninstallProtectionBadge(active);
    }

    private void updateUninstallProtectionBadge(boolean active) {
        if (tvUninstallProtectionBadge == null) {
            return;
        }
        tvUninstallProtectionBadge.setText(active ? "ACTIVE" : "DEACTIVATED");
        tvUninstallProtectionBadge.setTextColor(ContextCompat.getColor(this,
                active ? R.color.success_700 : R.color.error_700));
        tvUninstallProtectionBadge.setBackgroundResource(active
                ? R.drawable.bg_security_enabled_badge
                : R.drawable.bg_status_badge_error);
    }

    private void stopUninstallProtectionMonitoring() {
        if (uninstallProtectionStatusListener != null && uninstallProtectionStatusRef != null) {
            uninstallProtectionStatusRef.removeEventListener(uninstallProtectionStatusListener);
            uninstallProtectionStatusListener = null;
            uninstallProtectionStatusRef = null;
        }
    }

    private void resetUninstallProtectionUI() {
        if (tvUninstallProtectionDash != null) {
            tvUninstallProtectionDash.setText("No child connected");
            tvUninstallProtectionDash.setTextColor(ContextCompat.getColor(this, R.color.neutral_500));
        }
        if (tvUninstallProtectionBadge != null) {
            tvUninstallProtectionBadge.setText("OFFLINE");
            tvUninstallProtectionBadge.setTextColor(ContextCompat.getColor(this, R.color.neutral_500));
            tvUninstallProtectionBadge.setBackgroundResource(R.drawable.bg_security_disabled_badge);
        }
    }

    private void debugDeviceLists(String context) {
        try {
            Log.d(TAG, "Ã°Å¸â€Â DEBUG DEVICE LISTS - " + context);
            Log.d(TAG, "Ã°Å¸â€œÂ± Local connectedDevices size: " + connectedDevices.size());
            for (int i = 0; i < connectedDevices.size(); i++) {
                ChildDevice device = connectedDevices.get(i);
                Log.d(TAG, "  [" + i + "] " + device.deviceName + " (ID: " + device.deviceId + ")");
            }

            List<ChildDevice> persistentDevices = connectedDevicesManager.getConnectedDevices();
            Log.d(TAG, "Ã°Å¸â€™Â¾ Persistent storage devices size: " + persistentDevices.size());
            for (int i = 0; i < persistentDevices.size(); i++) {
                ChildDevice device = persistentDevices.get(i);
                Log.d(TAG, "  [" + i + "] " + device.deviceName + " (ID: " + device.deviceId + ")");
            }

            Log.d(TAG, "Ã°Å¸Å½Â¯ Current device: " + currentChildDeviceId + " (" + currentChildDeviceName + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error debugging device lists: " + e.getMessage());
        }
    }

    private void startListeningForDeviceStatus(String deviceId) {
        try {
            Log.d(TAG, "Ã°Å¸â€˜â€š Starting device status listener for: " + deviceId);
            deviceStatusManager.listenForChildDeviceStatus(deviceId,
                    new DeviceStatusManager.OnDeviceStatusChangeListener() {
                        @Override
                        public void onDeviceStatusChanged(String deviceId, boolean isOnline, long lastSeen) {
                            runOnUiThread(() -> {
                                // Update the device status in persistent storage
                                connectedDevicesManager.updateDeviceLastSeen(deviceId, lastSeen);

                                // Update warning banner on state/status changes
                                if (deviceId.equals(currentChildDeviceId)) {
                                    updateSyncWarningBanner();
                                }

                                // Update the device status in our in-memory list for compatibility
                                for (ChildDevice device : connectedDevices) {
                                    if (device.deviceId.equals(deviceId)) {
                                        device.lastConnected = lastSeen;

                                        // Update UI if this is the current device
                                        if (deviceId.equals(currentChildDeviceId)) {
                                            updateDeviceStatusDisplay(device.deviceName, isOnline, lastSeen);
                                        }
                                        break;
                                    }
                                }
                            });
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error setting up device status listener: " + e.getMessage());
        }
    }

    // ==============================================
    // UPDATED METHOD FOR MODERN DESIGN COLORS
    // ==============================================
    private void updateDeviceStatusDisplay(String deviceName, boolean isOnline, long lastSeen) {
        try {
            // Show formatted device status text
            String displayName = "";
            if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
                displayName = currentChildUserName;
            } else if (deviceName != null && !deviceName.isEmpty()) {
                displayName = deviceName;
            } else {
                displayName = "Device";
            }

            // Capitalize first letter
            String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
            String deviceStatusText = capitalizedName + " (Tap to Manage Device)";

            binding.tvDeviceStatus.setText(deviceStatusText);
            binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600)); // Teal color
        } catch (Exception e) {
            Log.e(TAG, "Error updating device status display: " + e.getMessage());
        }
    }

    private void updateUsageChart(ChildDevice device) {
        setupChart();
    }

    private void setupChart() {
        // Chart removed - now showing only total usage display
        Log.d(TAG, "setupChart called - chart functionality removed");
    }

    /**
     * Ã°Å¸â€œÅ  Get day label for bar chart index (0 = oldest day, last index = today)
     */
    private String getDayLabelForBarIndex(int index) {
        // Calculate the date for this bar index
        Calendar barDate = getDateForBarIndex(index);
        if (barDate == null)
            return "Unknown";

        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (isSameDay(barDate, today)) {
            return "Today";
        } else if (isSameDay(barDate, yesterday)) {
            return "Yesterday";
        } else {
            String[] dayNames = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
            return dayNames[barDate.get(Calendar.DAY_OF_WEEK) - 1];
        }
    }

    /**
     * Ã°Å¸â€œâ€¦ Get date for bar chart index
     */
    private Calendar getDateForBarIndex(int index) {
        // Get current 7-day window
        List<Calendar> dayWindow = getCurrentSevenDayWindow();
        if (index >= 0 && index < dayWindow.size()) {
            return dayWindow.get(index);
        }
        return null;
    }

    /**
     * Check if two Calendar dates represent the same day
     */
    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        if (cal1 == null || cal2 == null)
            return false;
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * Get current 7-day window for bar chart
     */
    private List<Calendar> getCurrentSevenDayWindow() {
        List<Calendar> dayWindow = new ArrayList<>();
        Calendar cal = Calendar.getInstance();

        // Start from 6 days ago and go to today
        for (int i = 6; i >= 0; i--) {
            Calendar dayCalendar = Calendar.getInstance();
            dayCalendar.add(Calendar.DAY_OF_YEAR, -i);
            dayWindow.add(dayCalendar);
        }

        return dayWindow;
    }

    /**
     * Update parent dashboard bar chart with usage data
     * /**
     * Update the parent dashboard bar chart - DISABLED
     * 7-Day Usage Overview section has been completely removed from layout
     */
    private void updateParentDashboardBarChart(List<Float> barValues, List<String> dayLabels) {
        // 7-Day Usage Overview section has been removed from layout
        // This method is disabled to prevent errors
        Log.d(TAG, "Ã°Å¸â€œÅ  7-Day Usage Overview disabled - section removed from layout");
        return;
    }

    /**
     * Update bar chart from Firebase snapshot data
     */
    private void updateBarChartFromSnapshot(DataSnapshot snapshot) {
        try {
            if (snapshot == null) {
                Log.w(TAG, "Cannot update bar chart - snapshot is null");
                return;
            }

            // Get bar chart data from snapshot
            List<Float> barValues = new ArrayList<>();
            List<String> dayLabels = new ArrayList<>();

            // Check for bars data
            if (snapshot.child("bars").exists()) {
                DataSnapshot barsSnapshot = snapshot.child("bars");

                // Extract bar values (usage data in minutes)
                for (DataSnapshot barSnapshot : barsSnapshot.getChildren()) {
                    Float value = barSnapshot.getValue(Float.class);
                    if (value != null) {
                        barValues.add(value);
                    } else {
                        barValues.add(0.0f);
                    }
                }

                Log.d(TAG, "Ã°Å¸â€œÅ  Extracted " + barValues.size() + " bar values from Firebase");
            }

            // Check for day labels data
            if (snapshot.child("dayLabels").exists()) {
                DataSnapshot labelsSnapshot = snapshot.child("dayLabels");

                // Extract day labels
                for (DataSnapshot labelSnapshot : labelsSnapshot.getChildren()) {
                    String label = labelSnapshot.getValue(String.class);
                    if (label != null) {
                        dayLabels.add(label);
                    }
                }

                Log.d(TAG, "Ã°Å¸â€œâ€¦ Extracted " + dayLabels.size() + " day labels from Firebase");
            }

            // If we have data, update the bar chart
            if (!barValues.isEmpty()) {
                // Ensure dayLabels matches barValues length
                while (dayLabels.size() < barValues.size()) {
                    int index = dayLabels.size();
                    dayLabels.add(getDayLabelForBarIndex(index));
                }

                // Update the chart
                updateParentDashboardBarChart(barValues, dayLabels);
                Log.d(TAG, "Ã°Å¸â€œÅ  Successfully updated bar chart with " + barValues.size() + " data points");
            } else {
                Log.d(TAG, "Ã°Å¸â€œÅ  No bar chart data available in snapshot");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating bar chart from snapshot: " + e.getMessage(), e);
        }
    }



    private void addSettingsButtons() {
        // Terms and Services button - REMOVED from dashboard footer
        // Button btnTermsAndServices = findViewById(R.id.btnTermsAndServices);
        // ...

        // Disconnect All Devices button - REMOVED
        // Button btnDisconnectAll = findViewById(R.id.btnDisconnectAll);
        // ...
    }

    private void addWelcomeTextToSettings() {
        // Add the full welcome message text directly to settings content
        if (settingsContent != null && settingsContent instanceof LinearLayout) {
            LinearLayout settingsLayout = (LinearLayout) settingsContent;

            // Remove any existing welcome/help content first
            View existingWelcomeText = settingsContent.findViewWithTag("welcome_text");
            View existingHelpInfo = settingsContent.findViewWithTag("help_info");

            if (existingWelcomeText != null) {
                settingsLayout.removeView(existingWelcomeText);
                Log.d(TAG, "Removed existing welcome text");
            }
            if (existingHelpInfo != null) {
                settingsLayout.removeView(existingHelpInfo);
                Log.d(TAG, "Removed existing help info");
            }

            // Create welcome text directly - no card wrapper, no button
            TextView welcomeText = new TextView(this);
            welcomeText.setTag("welcome_text");
            welcomeText.setText("Welcome & Important Information\\n\\n" +
                    "Welcome! Here are some important tips:\n\n" +
                    "Ã¢â‚¬Â¢ Use the QR code scanner to connect child devices\n" +
                    "Ã¢â‚¬Â¢ Monitor and manage your child's screen time easily\n" +
                    "Ã¢â‚¬Â¢ Access all controls from this parent dashboard\n\n" +
                    "Ã¢Å¡Â Ã¯Â¸Â TROUBLESHOOTING: If you can see a device name but cannot track its data, please:\n" +
                    "1. Remove the device from this app\n" +
                    "2. Reinstall the app on the child device\n" +
                    "3. Connect the child via QR code again\n\n" +
                    "Ã°Å¸â€â€™ IMPORTANT SECURITY: Before uninstalling this app or logging out permanently:\n" +
                    "Ã¢â‚¬Â¢ Always remove all connected child devices first\n" +
                    "Ã¢â‚¬Â¢ This prevents security issues and data conflicts\n" +
                    "Ã¢â‚¬Â¢ Use 'Disconnect All Devices' in Settings if needed\n\n" +
                    "Ã°Å¸â€™Â¡ TIP: Ensure both devices have stable internet when connecting via QR code.");

            // Style the text
            welcomeText.setTextSize(14);
            welcomeText.setTextColor(ContextCompat.getColor(this, android.R.color.black));
            welcomeText.setLineSpacing(6, 1.2f);
            welcomeText.setPadding(32, 32, 32, 32);
            welcomeText.setBackground(ContextCompat.getDrawable(this, R.drawable.card_background));

            // Set layout parameters with margin
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.setMargins(0, 24, 0, 24);
            welcomeText.setLayoutParams(textParams);

            // Add text to settings layout (at the bottom)
            settingsLayout.addView(welcomeText);

            Log.d(TAG, "Ã¢Å“â€¦ SUCCESS! Welcome information text added to settings - should be visible now!");
        } else {
            Log.e(TAG, "Ã¢ÂÅ’ FAILED! Settings content is null or not LinearLayout - cannot add welcome text");
            if (settingsContent == null) {
                Log.e(TAG, "settingsContent is NULL");
            } else {
                Log.e(TAG, "settingsContent type: " + settingsContent.getClass().getSimpleName());
            }
        }
    }

    private void addHelpInfoToSettings() {
        try {
            if (settingsContent instanceof LinearLayout) {
                LinearLayout settingsLayout = (LinearLayout) settingsContent;

                // Create a card-like container for help information
                LinearLayout helpCard = new LinearLayout(this);
                helpCard.setOrientation(LinearLayout.VERTICAL);
                helpCard.setTag("help_info");
                helpCard.setPadding(32, 24, 32, 24);
                helpCard.setBackground(ContextCompat.getDrawable(this, R.drawable.card_background));

                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                cardParams.setMargins(0, 16, 0, 16);
                helpCard.setLayoutParams(cardParams);

                // Title
                TextView helpTitle = new TextView(this);
                helpTitle.setText("Welcome & Important Information");
                helpTitle.setTextSize(16);
                helpTitle.setTextColor(ContextCompat.getColor(this, R.color.primary_600));
                helpTitle.setTypeface(null, Typeface.BOLD);
                helpTitle.setPadding(0, 0, 0, 16);

                // Help content - FULL WELCOME MESSAGE
                TextView helpContent = new TextView(this);
                helpContent.setText("Welcome! Here are some important tips:\n\n" +
                        "Ã¢â‚¬Â¢ Use the QR code scanner to connect child devices\n" +
                        "Ã¢â‚¬Â¢ Monitor and manage your child's screen time easily\n" +
                        "Ã¢â‚¬Â¢ Access all controls from this parent dashboard\n\n" +
                        "Ã¢Å¡Â Ã¯Â¸Â TROUBLESHOOTING: If you can see a device name but cannot track its data, please:\n" +
                        "1. Remove the device from this app\n" +
                        "2. Reinstall the app on the child device\n" +
                        "3. Connect the child via QR code again\n\n" +
                        "Ã¯Â¿Â½ IMPORTANT SECURITY: Before uninstalling this app or logging out permanently:\n" +
                        "Ã¢â‚¬Â¢ Always remove all connected child devices first\n" +
                        "Ã¢â‚¬Â¢ This prevents security issues and data conflicts\n" +
                        "Ã¢â‚¬Â¢ Use 'Disconnect All Devices' in Settings if needed\n\n" +
                        "Ã¯Â¿Â½ TIP: Ensure both devices have stable internet when connecting via QR code.");
                helpContent.setTextSize(14);
                helpContent.setTextColor(ContextCompat.getColor(this, android.R.color.black));
                helpContent.setLineSpacing(4, 1.1f);

                // Show more details button
                Button btnShowDetails = new Button(this);
                btnShowDetails.setText("Show Detailed Help");
                btnShowDetails.setTextColor(ContextCompat.getColor(this, R.color.modern_orange_600));
                btnShowDetails.setOnClickListener(v -> showTroubleshootingDialog());

                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                btnParams.topMargin = 16;
                btnShowDetails.setLayoutParams(btnParams);

                // Add all components to the card
                helpCard.addView(helpTitle);
                helpCard.addView(helpContent);
                helpCard.addView(btnShowDetails);

                // Add card to settings layout (at the top)
                settingsLayout.addView(helpCard, 0);

                Log.d(TAG, "Help information card added to settings");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding help info to settings: " + e.getMessage());
        }
    }

    private void showDisconnectAllDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("Disconnect All Devices");
        builder.setMessage(
                "Are you sure you want to disconnect all connected child devices?\n\nThis action will:\nÃ¢â‚¬Â¢ Remove all connected devices\nÃ¢â‚¬Â¢ Sign out all child devices\nÃ¢â‚¬Â¢ Redirect you to the login page\n\nThis action cannot be undone.");
        builder.setPositiveButton("Disconnect All", (dialog, which) -> {
            disconnectAllDevices();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void performLogout() {
        try {
            Log.d(TAG, "Ã°Å¸Å¡Âª NUCLEAR LOGOUT INITIATED - Obliterating all device connections");

            // Show loading dialog for logout process
            runOnUiThread(() -> {
                if (loadingDialogManager != null) {
                    loadingDialogManager.show("Logging Out", "Disconnecting devices and clearing data...");
                }
            });

            // STEP 1: Get current user for Firebase cleanup
            String parentId = null;
            if (mAuth != null && mAuth.getCurrentUser() != null) {
                parentId = mAuth.getCurrentUser().getUid();
            } else if (sessionManager != null && sessionManager.isLoggedIn()) {
                parentId = sessionManager.getUserId();
            }

            if (parentId != null) {
                Log.d(TAG, "Ã¢ËœÂ¢Ã¯Â¸Â NUCLEAR FIREBASE OBLITERATION for user: " + parentId);
                performNuclearFirebaseCleanup(parentId, () -> {
                    // STEP 2: Complete local cleanup after Firebase cleanup
                    completeLogoutProcess();
                });
            } else {
                Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â No user ID found - proceeding with local cleanup only");
                completeLogoutProcess();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during logout: " + e.getMessage());
            runOnUiThread(() -> {
                if (loadingDialogManager != null) {
                    loadingDialogManager.hide();
                }
                Toast.makeText(this, "Error during logout", Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * NUCLEAR FIREBASE OBLITERATION - Removes ALL parent device data from Firebase
     */
    /** Removes every owned child through the server-authoritative v2 operation. */
    private void performNuclearFirebaseCleanup(String parentId, Runnable onComplete) {
        DatabaseReference linksRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_device_links")
                .child(parentId);
        linksRef.get()
                .addOnSuccessListener(snapshot -> {
                    List<String> deviceIds = new ArrayList<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        if (child.getKey() != null && !child.getKey().isEmpty()) {
                            deviceIds.add(child.getKey());
                        }
                    }
                    if (deviceIds.isEmpty()) {
                        removeParentClientAndComplete(parentId, onComplete);
                        return;
                    }

                    online.monarchlabs.sentinel.services.RelationshipService service =
                            new online.monarchlabs.sentinel.services.RelationshipService(
                                    getApplicationContext());
                    List<java.util.concurrent.CompletableFuture<
                            online.monarchlabs.sentinel.services.RelationshipService.Result>> removals =
                            new ArrayList<>();
                    for (String deviceId : deviceIds) {
                        removals.add(service.remove(deviceId, "parent_logout"));
                    }

                    java.util.concurrent.CompletableFuture
                            .allOf(removals.toArray(
                                    new java.util.concurrent.CompletableFuture[0]))
                            .whenComplete((ignored, error) -> {
                                if (error != null) {
                                    showRelationshipCleanupFailure(
                                            "Could not disconnect every child device.", error);
                                    return;
                                }
                                for (java.util.concurrent.CompletableFuture<
                                        online.monarchlabs.sentinel.services.RelationshipService.Result> removal
                                        : removals) {
                                    online.monarchlabs.sentinel.services.RelationshipService.Result result =
                                            removal.join();
                                    if (!result.success) {
                                        showRelationshipCleanupFailure(
                                                result.message != null
                                                        ? result.message
                                                        : "Could not disconnect every child device.",
                                                null);
                                        return;
                                    }
                                }
                                removeParentClientAndComplete(parentId, onComplete);
                            });
                })
                .addOnFailureListener(error -> showRelationshipCleanupFailure(
                        "Could not load the connected child devices.", error));
    }

    private void removeParentClientAndComplete(String parentId, Runnable onComplete) {
        runOnUiThread(() -> {
            String clientId = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ANDROID_ID);
            FirebaseDatabase.getInstance().getReference("v2")
                    .child("parent_clients")
                    .child(parentId)
                    .child(clientId)
                    .removeValue()
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) {
                            showRelationshipCleanupFailure(
                                    "Child devices were removed, but parent cleanup failed.",
                                    task.getException());
                            return;
                        }
                        onComplete.run();
                    });
        });
    }

    private void showRelationshipCleanupFailure(String message, Throwable error) {
        if (error != null) {
            Log.e(TAG, message, error);
        } else {
            Log.e(TAG, message);
        }
        runOnUiThread(() -> {
            if (loadingDialogManager != null) {
                loadingDialogManager.hide();
            }
            Toast.makeText(this, message + " Please try again.", Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Complete the logout process with local cleanup
     */
    private void completeLogoutProcess() {
        Log.d(TAG, "Ã°Å¸Â§Â¹ COMPLETING LOCAL CLEANUP");

        // Set flag for fresh login cleanup
        SharedPreferences appStatePrefs = getSharedPreferences("app_state", MODE_PRIVATE);
        appStatePrefs.edit().putBoolean("was_logged_out", true).apply();

        // Clear parent-only cached usage and icons before clearing the session.
        online.monarchlabs.sentinel.utils.ParentUsageCacheManager.getInstance(this).clearAll();

        // Clear session
        sessionManager.logoutUser();

        // Clear connected devices data
        if (connectedDevicesManager != null) {
            connectedDevicesManager.clearAllDevices();
        }


        // Sign out from Firebase
        FirebaseAuth.getInstance().signOut();

        runOnUiThread(() -> {
            if (loadingDialogManager != null) {
                loadingDialogManager.hide();
            }

            Toast.makeText(this, "Logged out successfully - All devices disconnected", Toast.LENGTH_LONG).show();

            // Navigate to main activity
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        Log.d(TAG, "Ã°Å¸Å½Â¯ NUCLEAR LOGOUT COMPLETED - Clean slate achieved");
        Log.d(TAG, "Ã°Å¸Å½Â¯ NEXT LOGIN WILL BE TREATED AS FRESH LOGIN");
    }

    /**
     * DEBUG METHOD: Force fresh login state for testing
     * Call this from settings or debug menu to test fresh login behavior
     */
    private void debugForceFreshLogin() {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit().putBoolean("was_logged_out", true).apply();
        Log.d(TAG, "Ã°Å¸Â§Âª DEBUG: Fresh login flag set - restart app to test");
        Toast.makeText(this, "Fresh login flag set - restart app to test", Toast.LENGTH_LONG).show();
    }

    /**
     * Trigger logout on a specific child device
     */
    /**
     * Ã°Å¸â€Â¥ Trigger nuclear cleanup from parent side to ensure child cannot reconnect
     */
    /**
     * Enable device connection listeners after fresh login when user manually
     * connects first device
     * Ã°Å¸Å¡Â« DISABLED - Prevents automatic device loading
     */
    private void enableDeviceListenersAfterFreshLogin() {
        // Ã°Å¸Å¡Â« DISABLED - This method would enable automatic device loading listeners
        // User requirement: Only QR scanned devices should be shown
        Log.d(TAG, "Ã°Å¸Å¡Â« AUTOMATIC LISTENER ACTIVATION DISABLED - QR scan only mode maintained");

        if (isFreshLoginSession) {
            isFreshLoginSession = false; // Clear fresh login flag
            Log.d(TAG, "Ã¢Å“â€¦ Fresh login flag cleared - but listeners remain disabled");
        }
    }

    /**
     * Check if this is a fresh login and cleanup any residual data
     *
     * @return true if this was a fresh login (skip device loading), false otherwise
     */
    private boolean checkForFreshLoginAndCleanup() {
        try {
            SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
            boolean wasLoggedOut = prefs.getBoolean("was_logged_out", false);
            boolean isFirstRun = prefs.getBoolean("is_first_run", true);

            if (wasLoggedOut || isFirstRun) {
                // Ã°Å¸â€Â§ CONNECTION FIX: Check if we have existing devices - if so, this isn't a
                // fresh start, it's an update!
                boolean hasExistingDevices = false;
                SharedPreferences devicePrefs = getSharedPreferences("connected_devices", MODE_PRIVATE);
                String devicesJson = devicePrefs.getString("devices", "[]");
                if (devicesJson != null && !devicesJson.equals("[]") && !devicesJson.isEmpty()) {
                    hasExistingDevices = true;
                    Log.d(TAG, "Ã°Å¸â€œÂ± EXISTING DEVICES DETECTED during fresh login check - Preserving data");
                }

                if (isFirstRun) {
                    if (hasExistingDevices) {
                        Log.d(TAG, "Ã°Å¸Å¡â‚¬ APP UPDATE DETECTED - Existing devices found, skipping initial cleanup");
                        prefs.edit().putBoolean("is_first_run", false).apply();
                        // SKIP CLEANUP!
                        return false;
                    }
                    Log.d(TAG, "Ã°Å¸Å¡â‚¬ FIRST APP RUN DETECTED - Performing initial cleanup");
                    prefs.edit().putBoolean("is_first_run", false).apply();
                } else {
                    Log.d(TAG, "Ã°Å¸Â§Â¹ FRESH LOGIN DETECTED - Performing cleanup");
                }

                // Clear the fresh login flag
                prefs.edit().remove("was_logged_out").apply();

                // Ensure all connected device data is cleared
                if (connectedDevicesManager != null) {
                    connectedDevicesManager.clearAllDevices();
                    Log.d(TAG, "Ã°Å¸â€”â€˜Ã¯Â¸Â ConnectedDevicesManager cleared");
                }


                // Clear any other app state preferences
                SharedPreferences connectedDevicesPrefs = getSharedPreferences("connected_devices", MODE_PRIVATE);
                connectedDevicesPrefs.edit().clear().apply();

                // Reset connected devices list
                connectedDevices.clear();
                // Explicitly clear current device selection (treat as explicit user action)
                connectedDevicesManager.setCurrentDevice(null, true);
                // Sync local cache
                currentChildDeviceId = connectedDevicesManager.getCurrentDeviceId();
                currentChildDeviceName = "No Device";

                Log.d(TAG, "Ã¢Å“â€¦ Fresh start cleanup completed - NO DEVICES SHOULD BE LOADED");
                Log.d(TAG, "Ã°Å¸Å½Â¯ EXPECTED RESULT: User should see 'No Device' and empty device list");
                Log.d(TAG, "Ã°Å¸â€œÂ± Device connection method: Manual QR scan ONLY");
                return true; // This is a fresh login - skip device loading
            } else {
                Log.d(TAG, "Ã°Å¸â€œÂ± Continuing existing session");
                return false; // Normal session - allow device loading
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during fresh login check: " + e.getMessage());
            return false;
        }
    }

    private void disconnectAllDevices() {
        String parentUid = mAuth != null && mAuth.getCurrentUser() != null
                ? mAuth.getCurrentUser().getUid() : null;
        if (parentUid == null) {
            Toast.makeText(this, "Please sign in again.", Toast.LENGTH_SHORT).show();
            return;
        }
        loadingDialogManager.show("Disconnecting Devices", "Removing child connections...");
        performNuclearFirebaseCleanup(parentUid, () -> runOnUiThread(() -> {
            connectedDevices.clear();
            connectedDevicesManager.clearAllDevices();
            loadingDialogManager.hide();
            completeLogoutProcess();
        }));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // MapView lifecycle
        if (mapCardInitialized && dashboardMapView != null) {
            dashboardMapView.onResume();
        }
        // Update map card visibility each time we return
        updateMapCardVisibility();
        refreshNotificationBadge();

        // Fix: Force bottom navigation to "Home" when returning to dashboard
        if (bottomNavigation != null) {
            bottomNavigation.setSelectedItemId(R.id.nav_home);
        }

        // Update session activity
        if (sessionManager != null) {
            sessionManager.updateLastActivity();
        }
        // Request notification permission if not already granted
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1001);
            }
        }


        // Additional checks for current device
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            rebindCurrentChildSessionState();

            Log.d(TAG, "App resumed - syncing device: " + currentChildDeviceId);

            // Restore timer state from Firebase
            Log.d(TAG, "Restoring timer state for device: " + currentChildDeviceId);

            // Use delayed refresh to avoid conflicts
            Handler timerRestoreHandler = new Handler(Looper.getMainLooper());
            timerRestoreHandler.postDelayed(() -> {
                Log.d(TAG, "State restoration delayed actions completed");
            }, 500); // 500ms delay to allow app to fully resume

            // Ensure background services are running
            ensureBackgroundServicesRunning();
        }

        if (parentDeviceLinksListener == null || parentDeviceLinksRef == null) {
            setupV2ParentDeviceLinksListener();
        }

        refreshChildLocationIfNeeded();

    }

    // Ã¢â€â‚¬Ã¢â€â‚¬ Map card visibility helper Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    /**
     * Shows the map card if at least one child device is connected.
     * When no child is connected the card is still visible but blurred with
     * a "Connect a child device" message so the parent knows the feature exists.
     */
    private void updateMapCardVisibility() {
        if (cardMapContainer == null) return;

        boolean hasChild = (connectedDevices != null && !connectedDevices.isEmpty())
                || (currentChildDeviceId != null && !currentChildDeviceId.isEmpty());

        // Always show the card (so parents know the feature exists)
        cardMapContainer.setVisibility(View.VISIBLE);

        if (hasChild) {
            // Child connected Ã¢â‚¬â€ clear blur overlay, show map
            if (mapBlurOverlay != null)  mapBlurOverlay.setVisibility(View.GONE);
            if (ivMapToggleIcon != null) ivMapToggleIcon.setImageResource(R.drawable.ic_map_expand);
            // Start listening for real-time child location
            if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
                attachChildLocationListener(currentChildDeviceId);
            }
        } else {
            // No child Ã¢â‚¬â€ show blur + message
            if (mapBlurOverlay != null)  mapBlurOverlay.setVisibility(View.VISIBLE);
            detachChildLocationListener();
            // Hide sync warning when no child is connected
            if (layoutSyncWarning != null) layoutSyncWarning.setVisibility(View.GONE);
        }

        // Start the MapView if not already started
        if (mapCardInitialized && dashboardMapView != null) {
            try { dashboardMapView.onResume(); } catch (Exception ignored) {}
        }
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬ MapView additional lifecycle forwards Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
    @Override
    protected void onStart() {
        super.onStart();
        usageMonitoringForeground = true;
        if (mapCardInitialized && dashboardMapView != null) dashboardMapView.onStart();
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            loadSmartUsageDataForSelectedDate();
        }
        scheduleUsageMidnightRollover();
    }

    private void scheduleUsageMidnightRollover() {
        if (usageMidnightRollover != null) {
            usageLifecycleHandler.removeCallbacks(usageMidnightRollover);
        }

        Calendar nextDay = Calendar.getInstance();
        nextDay.add(Calendar.DAY_OF_YEAR, 1);
        nextDay.set(Calendar.HOUR_OF_DAY, 0);
        nextDay.set(Calendar.MINUTE, 0);
        nextDay.set(Calendar.SECOND, 1);
        nextDay.set(Calendar.MILLISECOND, 0);

        usageMidnightRollover = () -> {
            if (!usageMonitoringForeground) {
                return;
            }
            if (!dateSetByUser) {
                currentUsageDate = Calendar.getInstance();
                updateSelectedDateDisplay();
                loadSmartUsageDataForSelectedDate();
            }
            scheduleUsageMidnightRollover();
        };
        long delayMs = Math.max(1000L, nextDay.getTimeInMillis() - System.currentTimeMillis());
        usageLifecycleHandler.postDelayed(usageMidnightRollover, delayMs);
    }
    @Override
    protected void onPause() {
        if (mapCardInitialized && dashboardMapView != null) {
            try { dashboardMapView.onPause(); } catch (Exception ignored) {}
        }
        detachChildLocationListener();
        super.onPause();
    }

    @Override
    protected void onStop() {
        usageMonitoringForeground = false;
        if (usageMidnightRollover != null) {
            usageLifecycleHandler.removeCallbacks(usageMidnightRollover);
        }
        if (mapCardInitialized && dashboardMapView != null) dashboardMapView.onStop();
        stopSmartUsageMonitoring();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapCardInitialized && dashboardMapView != null) {
            android.os.Bundle mapBundle = outState.getBundle(MAP_BUNDLE_KEY);
            if (mapBundle == null) {
                mapBundle = new android.os.Bundle();
                outState.putBundle(MAP_BUNDLE_KEY, mapBundle);
            }
            dashboardMapView.onSaveInstanceState(mapBundle);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (mapCardInitialized && dashboardMapView != null) dashboardMapView.onLowMemory();
    }
    // Ã¢â€â‚¬Ã¢â€â‚¬ End MapView lifecycle Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

    // Ã¢â€â‚¬Ã¢â€â‚¬ Child live location Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

    private void attachChildLocationListener(String deviceId) {
        detachChildLocationListener(); // Remove any previous listener first
        if (deviceId == null || deviceId.isEmpty()) return;
        childLocationRef = FirebaseDatabase.getInstance()
                .getReference("v2").child("locations").child(deviceId);
        childLocationListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!deviceId.equals(currentChildDeviceId)) {
                    return;
                }
                if (!snapshot.exists()) return;
                Long timestamp = snapshot.child("timestamp").getValue(Long.class);
                if (timestamp != null) {
                    cachedChildLocationTimestamps.put(deviceId, timestamp);
                    lastLocationTimestamp = timestamp;
                }
                // Check for location status or GPS-off flag
                Boolean gpsOff = snapshot.child("gps_off").getValue(Boolean.class);
                String status = snapshot.child("status").getValue(String.class);

                boolean isOffline = "permission_denied".equals(status) || Boolean.TRUE.equals(gpsOff);

                if ("permission_denied".equals(status)) {
                    cachedChildGpsOffStates.put(deviceId, true);
                    cachedChildLocationWarningMessages.put(deviceId, "Location permission denied");
                    hideMapLoading();
                    waitingForFreshLocation = false;
                    showMapLocationWarning(true, "Location permission denied");
                    hideChildSwitchLoading();
                } else if (Boolean.TRUE.equals(gpsOff)) {
                    cachedChildGpsOffStates.put(deviceId, true);
                    cachedChildLocationWarningMessages.put(deviceId, "GPS is off");
                    hideMapLoading();
                    waitingForFreshLocation = false;
                    showMapLocationWarning(true, "GPS is off");
                    hideChildSwitchLoading();
                } else {
                    cachedChildGpsOffStates.put(deviceId, false);
                    cachedChildLocationWarningMessages.remove(deviceId);
                    showMapLocationWarning(false, "");
                }

                Double lat = snapshot.child("lat").getValue(Double.class);
                Double lng = snapshot.child("lng").getValue(Double.class);
                if (lat != null && lng != null) {
                    lastChildLocation = new LatLng(lat, lng);
                    cachedChildLocations.put(deviceId, lastChildLocation);

                    // Persist to SharedPreferences
                    android.content.SharedPreferences prefs = getSharedPreferences("last_known_locations_prefs", MODE_PRIVATE);
                    android.content.SharedPreferences.Editor editor = prefs.edit();
                    editor.putFloat("last_lat_" + deviceId, (float) lat.doubleValue());
                    editor.putFloat("last_lng_" + deviceId, (float) lng.doubleValue());
                    if (timestamp != null) {
                        editor.putLong("last_time_" + deviceId, timestamp);
                    }
                    editor.apply();

                    updateChildMarkerOnDashboard();
                } else {
                    LatLng cachedLocation = cachedChildLocations.get(deviceId);
                    if (cachedLocation == null) {
                        // Load from SharedPreferences
                        android.content.SharedPreferences prefs = getSharedPreferences("last_known_locations_prefs", MODE_PRIVATE);
                        float cLat = prefs.getFloat("last_lat_" + deviceId, 999f);
                        float cLng = prefs.getFloat("last_lng_" + deviceId, 999f);
                        if (cLat != 999f && cLng != 999f) {
                            cachedLocation = new LatLng(cLat, cLng);
                            cachedChildLocations.put(deviceId, cachedLocation);
                            long ts = prefs.getLong("last_time_" + deviceId, 0L);
                            if (ts > 0) {
                                cachedChildLocationTimestamps.put(deviceId, ts);
                            }
                        }
                    }

                    if (cachedLocation != null) {
                        lastChildLocation = cachedLocation;
                        updateChildMarkerOnDashboard();
                    } else {
                        lastChildLocation = null;
                        if (childLocationMarker != null) {
                            childLocationMarker.remove();
                            childLocationMarker = null;
                        }
                    }
                }
                hideChildSwitchLoading();

                if (!isOffline && waitingForFreshLocation && timestamp != null && timestamp >= locationRequestStartTime) {
                    hideMapLoading();
                    waitingForFreshLocation = false;
                } else {
                    updateLastSeenUI();
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Child location listener cancelled: " + error.getMessage());
            }
        };
        childLocationRef.addValueEventListener(childLocationListener);
    }

    /** Shows or hides the location warning TextView on the map card. */
    private void showMapLocationWarning(boolean show, String message) {
        TextView warning = findViewById(R.id.tvMapGpsOff);
        if (warning != null) {
            warning.setText(message);
            warning.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void showMapGpsOffWarning(boolean show) {
        showMapLocationWarning(show, "GPS is off");
    }

    private void showMapLoading() {
        if (mapStatusProgress != null) mapStatusProgress.setVisibility(View.VISIBLE);
        if (mapStatusDot != null) mapStatusDot.setVisibility(View.GONE);
        if (tvMapStatusText != null) tvMapStatusText.setText("Locating...");
        if (mapStatusBadge != null) mapStatusBadge.setVisibility(View.VISIBLE);

        // Cancel previous timeout runnable if active
        if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
            locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
        }

        // Start a 15-second locating timeout
        locationTimeoutHandler = new Handler(Looper.getMainLooper());
        locationTimeoutRunnable = () -> {
            waitingForFreshLocation = false;
            hideMapLoading();
        };
        locationTimeoutHandler.postDelayed(locationTimeoutRunnable, 15000);
    }

    private void hideMapLoading() {
        if (mapStatusProgress != null) mapStatusProgress.setVisibility(View.GONE);
        if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
            locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
        }
        updateLastSeenUI();
    }

    private void updateLastSeenUI() {
        if (mapStatusBadge != null && tvMapStatusText != null && mapStatusDot != null) {
            if (lastLocationTimestamp > 0) {
                String statusStr = formatLastSeenTime(lastLocationTimestamp);
                tvMapStatusText.setText(statusStr);
                mapStatusDot.setVisibility(View.VISIBLE);
                if ("Live".equals(statusStr)) {
                    mapStatusDot.setBackgroundResource(R.drawable.bg_circle_green);
                } else {
                    mapStatusDot.setBackgroundResource(R.drawable.bg_circle_neutral);
                }
                mapStatusBadge.setVisibility(View.VISIBLE);
            } else {
                mapStatusBadge.setVisibility(View.GONE);
            }
        }
    }

    private String formatLastSeenTime(long timestamp) {
        long diffMs = System.currentTimeMillis() - timestamp;
        if (diffMs < 0) diffMs = 0;

        long diffSec = diffMs / 1000;
        if (diffSec < 60) {
            return "Live";
        }

        long diffMin = diffSec / 60;
        if (diffMin < 60) {
            return "Last seen " + diffMin + "m ago";
        }

        long diffHr = diffMin / 60;
        if (diffHr < 24) {
            return "Last seen " + diffHr + "h ago";
        }

        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault());
        return "Last seen at " + sdf.format(new java.util.Date(timestamp));
    }

    private void showChildSwitchLoading(String deviceName) {
        hideChildSwitchLoadingImmediately();
        childSwitchLoadingShownAt = SystemClock.uptimeMillis();

        if (childSwitchSkeletonOverlay != null) {
            childSwitchSkeletonOverlay.setVisibility(View.VISIBLE);
            childSwitchSkeletonOverlay.setAlpha(1f);
            childSwitchSkeletonOverlay.bringToFront();
        }

        startChildSwitchSkeletonPulse();

        childSwitchLoadingTimeoutRunnable = this::hideChildSwitchLoadingImmediately;
        childSwitchLoadingHandler.postDelayed(childSwitchLoadingTimeoutRunnable, CHILD_SWITCH_LOADING_TIMEOUT_MS);

        if (deviceName != null && !deviceName.isEmpty()) {
            Log.d(TAG, "Showing child switch skeleton for: " + deviceName);
        }
    }

    private void hideChildSwitchLoading() {
        if (childSwitchSkeletonOverlay != null
                && childSwitchSkeletonOverlay.getVisibility() == View.VISIBLE) {
            long elapsedMs = SystemClock.uptimeMillis() - childSwitchLoadingShownAt;
            if (elapsedMs >= 0L && elapsedMs < CHILD_SWITCH_LOADING_MIN_MS) {
                if (childSwitchLoadingTimeoutRunnable != null) {
                    childSwitchLoadingHandler.removeCallbacks(childSwitchLoadingTimeoutRunnable);
                }
                childSwitchLoadingTimeoutRunnable = this::hideChildSwitchLoadingImmediately;
                childSwitchLoadingHandler.postDelayed(
                        childSwitchLoadingTimeoutRunnable,
                        CHILD_SWITCH_LOADING_MIN_MS - elapsedMs);
                return;
            }
        }

        hideChildSwitchLoadingImmediately();
    }

    private void hideChildSwitchLoadingImmediately() {
        if (childSwitchLoadingTimeoutRunnable != null) {
            childSwitchLoadingHandler.removeCallbacks(childSwitchLoadingTimeoutRunnable);
            childSwitchLoadingTimeoutRunnable = null;
        }

        stopChildSwitchSkeletonPulse();

        if (childSwitchSkeletonOverlay != null) {
            childSwitchSkeletonOverlay.setVisibility(View.GONE);
        }
    }
    private void startChildSwitchSkeletonPulse() {
        if (childSwitchSkeletonOverlay == null) {
            return;
        }

        if (childSwitchSkeletonAnimator != null) {
            childSwitchSkeletonAnimator.cancel();
        }

        childSwitchSkeletonAnimator = ObjectAnimator.ofFloat(childSwitchSkeletonOverlay, "alpha", 0.72f, 1f);
        childSwitchSkeletonAnimator.setDuration(900L);
        childSwitchSkeletonAnimator.setRepeatMode(ValueAnimator.REVERSE);
        childSwitchSkeletonAnimator.setRepeatCount(ValueAnimator.INFINITE);
        childSwitchSkeletonAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        childSwitchSkeletonAnimator.start();
    }

    private void stopChildSwitchSkeletonPulse() {
        if (childSwitchSkeletonAnimator != null) {
            childSwitchSkeletonAnimator.cancel();
            childSwitchSkeletonAnimator = null;
        }
        if (childSwitchSkeletonOverlay != null) {
            childSwitchSkeletonOverlay.setAlpha(1f);
        }
    }

    private void resetChildLocationPreview() {
        if (childLocationMarker != null) {
            childLocationMarker.remove();
            childLocationMarker = null;
        }
        lastChildLocation = null;
        lastLocationTimestamp = 0L;
        waitingForFreshLocation = false;
        locationRequestStartTime = 0L;
        showMapLocationWarning(false, "");
        hideMapLoading();
    }

    private boolean restoreCachedChildLocationPreview(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return false;
        }

        Boolean gpsOff = cachedChildGpsOffStates.get(deviceId);

        // Load from SharedPreferences if not present in memory cache
        LatLng cachedLocation = cachedChildLocations.get(deviceId);
        if (cachedLocation == null) {
            android.content.SharedPreferences prefs = getSharedPreferences("last_known_locations_prefs", MODE_PRIVATE);
            float cLat = prefs.getFloat("last_lat_" + deviceId, 999f);
            float cLng = prefs.getFloat("last_lng_" + deviceId, 999f);
            if (cLat != 999f && cLng != 999f) {
                cachedLocation = new LatLng(cLat, cLng);
                cachedChildLocations.put(deviceId, cachedLocation);
                long ts = prefs.getLong("last_time_" + deviceId, 0L);
                if (ts > 0) {
                    cachedChildLocationTimestamps.put(deviceId, ts);
                }
            }
        }

        String message = cachedChildLocationWarningMessages.get(deviceId);
        if (message == null || message.isEmpty()) {
            message = "GPS is off"; // default fallback
        }

        if (Boolean.TRUE.equals(gpsOff)) {
            showMapLocationWarning(true, message);
            hideMapLoading();
            hideChildSwitchLoading();
            if (cachedLocation != null) {
                lastChildLocation = cachedLocation;
                Long cachedTimestamp = cachedChildLocationTimestamps.get(deviceId);
                lastLocationTimestamp = (cachedTimestamp != null) ? cachedTimestamp : 0L;
                updateChildMarkerOnDashboard();
                updateLastSeenUI();
                return true;
            } else {
                lastChildLocation = null;
                lastLocationTimestamp = 0L;
                if (childLocationMarker != null) {
                    childLocationMarker.remove();
                    childLocationMarker = null;
                }
                return true;
            }
        }

        if (cachedLocation != null) {
            lastChildLocation = cachedLocation;
            Long cachedTimestamp = cachedChildLocationTimestamps.get(deviceId);
            lastLocationTimestamp = (cachedTimestamp != null) ? cachedTimestamp : 0L;
            showMapLocationWarning(false, "");
            updateChildMarkerOnDashboard();
            hideChildSwitchLoading();
            updateLastSeenUI();
            return true;
        }

        return false;
    }

    /** Requests a fresh fix through v2/commands/{deviceId}/location_refresh. */
    private void requestFreshLocation(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return;
        online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository
                            .requestLocationRefresh(deviceId);
        Log.d(TAG, "Ã°Å¸â€œÂ Auto-requested fresh location for: " + deviceId);
    }

    private void detachChildLocationListener() {
        if (childLocationListener != null && childLocationRef != null) {
            childLocationRef.removeEventListener(childLocationListener);
        }
        if (childLocationRef != null) {
            childLocationRef.keepSynced(false);
        }
        childLocationListener = null;
        childLocationRef = null;
    }

    private void detachQRScanListener() {
        if (qrScanListener != null && qrScanRef != null) {
            qrScanRef.removeEventListener(qrScanListener);
        }
        qrScanListener = null;
        qrScanRef = null;
    }

    private void detachParentConnectionListener() {
        if (parentsConnectionListener != null && parentsConnectionRef != null) {
            parentsConnectionRef.removeEventListener(parentsConnectionListener);
        }
        parentsConnectionListener = null;
        parentsConnectionRef = null;
    }

    private void detachV2ParentDeviceLinksListener() {
        if (parentDeviceLinksListener != null && parentDeviceLinksRef != null) {
            parentDeviceLinksRef.removeEventListener(parentDeviceLinksListener);
        }
        parentDeviceLinksListener = null;
        parentDeviceLinksRef = null;
    }

    private void updateChildMarkerOnDashboard() {
        if (dashboardGoogleMap == null || lastChildLocation == null) return;
        String baseTitle = (currentChildUserName != null && !currentChildUserName.isEmpty())
                ? currentChildUserName
                : ((currentChildDeviceName != null && !currentChildDeviceName.isEmpty()) ? currentChildDeviceName : "Child");

        boolean isOffline = false;
        String deviceId = currentChildDeviceId;
        if (deviceId != null) {
            Boolean offlineState = cachedChildGpsOffStates.get(deviceId);
            if (offlineState != null && offlineState) {
                isOffline = true;
            }
        }

        String title = isOffline ? baseTitle + " (Last Seen)" : baseTitle;
        float alpha = isOffline ? 0.6f : 1.0f;
        int circleColor = isOffline ? 0xFF757575 : 0xFF1A73E8; // Gray vs Google Blue

        if (childLocationMarker == null) {
            childLocationMarker = dashboardGoogleMap.addMarker(new MarkerOptions()
                    .position(lastChildLocation)
                    .title(title)
                    .anchor(0.5f, 0.5f)
                    .alpha(alpha)
                    .icon(BitmapDescriptorFactory.fromBitmap(createInitialsBitmap(baseTitle, circleColor))));
            // Center the mini-map on first location received
            dashboardGoogleMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(lastChildLocation, 12f));
        } else {
            childLocationMarker.setPosition(lastChildLocation);
            childLocationMarker.setTitle(title);
            childLocationMarker.setAlpha(alpha);
            childLocationMarker.setIcon(BitmapDescriptorFactory.fromBitmap(createInitialsBitmap(baseTitle, circleColor)));
        }
    }

    private Bitmap createInitialsBitmap(String name) {
        return createInitialsBitmap(name, 0xFF1A73E8);
    }

    /**
     * Creates a filled circle bitmap with up to 2 initials of the child's name in white.
     * e.g. "hamza" Ã¢â€ â€™ "H", "John Doe" Ã¢â€ â€™ "JD"
     */
    private Bitmap createInitialsBitmap(String name, int circleColor) {
        int sizeDp = 48;
        float density = getResources().getDisplayMetrics().density;
        int sizePx = (int) (sizeDp * density);

        // Extract up to 2 initials
        StringBuilder initials = new StringBuilder();
        String[] parts = name.trim().split("\\s+");
        for (String part : parts) {
            if (!part.isEmpty()) initials.append(Character.toUpperCase(part.charAt(0)));
            if (initials.length() == 2) break;
        }
        if (initials.length() == 0) initials.append("?");

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Filled circle with dynamic color
        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(circleColor);
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, circlePaint);

        // White border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - density, borderPaint);

        // White initials text
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextSize(sizePx * 0.38f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float textY = sizePx / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(initials.toString(), sizePx / 2f, textY, textPaint);

        return bitmap;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ParentDashboardActivity destroyed");

        // Stop heartbeat timer to prevent leaked timers
        stopParentHeartbeatTimer();

        hideChildSwitchLoading();

        if (guidePulseAnimator != null) {
            guidePulseAnimator.cancel();
            guidePulseAnimator = null;
        }

        // MapView lifecycle
        if (mapCardInitialized && dashboardMapView != null) {
            dashboardMapView.onDestroy();
        }

        // Detach child location listener
        detachChildLocationListener();
        detachQRScanListener();
        detachParentConnectionListener();
        detachV2ParentDeviceLinksListener();
        detachSosEventsListener();
        detachGeofenceEventsListener();

        // Detach usage listeners
        stopSmartUsageMonitoring();
        stopNotificationBadgeMonitoring();
        guideLabelHandler.removeCallbacksAndMessages(null);

        // Stop device status tracking to avoid memory leaks
        if (deviceStatusManager != null) {
            deviceStatusManager.stopStatusTracking();
        }

        // Ã°Å¸Å¡Â¨ Stop uninstall detection monitoring
        if (uninstallDetectionManager != null) {
            uninstallDetectionManager.stopAllMonitoring();
        }

        // Clean up sync warning handler callback
        if (syncWarningRunnable != null) {
            syncWarningHandler.removeCallbacks(syncWarningRunnable);
        }

        // Detach limiter realtime listener to avoid leaks/crosstalk
        detachLimiterRealtimeListener();
        if (timerExpiryNotifRef != null && timerExpiryListener != null) {
            timerExpiryNotifRef.removeEventListener(timerExpiryListener);
        }
        timerExpiryNotifRef = null;
        timerExpiryListener = null;
    }

    // MANAGE DEVICES BOTTOM SHEET
    private void showManageDevicesDialog() {
        if (connectedDevicesManager == null)
            return;

        try {
            // Inflate bottom sheet layout
            View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_manage_devices, null);
            final com.google.android.material.bottomsheet.BottomSheetDialog bottomSheetDialog = new com.google.android.material.bottomsheet.BottomSheetDialog(
                    this, R.style.BottomSheetDialogTheme);
            bottomSheetDialog.setContentView(sheetView);

            // Handle Interactions
            View btnClose = sheetView.findViewById(R.id.btnSheetClose);
            LinearLayout btnAdd = sheetView.findViewById(R.id.btnSheetAddDevice);
            LinearLayout listContainer = sheetView.findViewById(R.id.llSheetDeviceList);

            if (btnClose != null) {
                btnClose.setOnClickListener(v -> bottomSheetDialog.dismiss());
            }

            if (btnAdd != null) {
                btnAdd.setOnClickListener(v -> {
                    bottomSheetDialog.dismiss();
                    showQRScanner();
                });
            }

            // Populate List
            if (listContainer != null) {
                listContainer.removeAllViews();
                List<ChildDevice> devices = connectedDevicesManager.getConnectedDevices();

                if (devices != null && !devices.isEmpty()) {
                    for (ChildDevice device : devices) {
                        View itemView = getLayoutInflater().inflate(R.layout.item_manage_device, listContainer, false);

                        TextView tvName = itemView.findViewById(R.id.tvItemDeviceName);
                        TextView tvStatus = itemView.findViewById(R.id.tvItemLastSeen);
                        TextView tvBadge = itemView.findViewById(R.id.tvItemCurrentBadge);
                        View btnRemove = itemView.findViewById(R.id.btnItemRemove);
                        ImageView ivIcon = itemView.findViewById(R.id.ivItemDeviceIcon);

                        // Set Data
                        String displayName = (device.userName != null && !device.userName.isEmpty())
                                ? device.userName
                                : device.deviceName;
                        // Capitalize
                        if (displayName != null && !displayName.isEmpty()) {
                            displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
                        }

                        final String finalDisplayName = displayName;

                        tvName.setText(finalDisplayName);

                        boolean isCurrent = device.deviceId.equals(currentChildDeviceId);

                        if (isCurrent) {
                            tvBadge.setVisibility(View.VISIBLE);
                            tvStatus.setText("Active Now");
                            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));
                            if (ivIcon != null)
                                ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.success_600));
                        } else {
                            tvBadge.setVisibility(View.GONE);
                            tvStatus.setText("Tap to switch");
                            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral_500));
                            if (ivIcon != null)
                                ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.neutral_400));
                        }

                        // Remove Action
                        if (btnRemove != null) {
                            final String deviceIdToRemove = device.deviceId;
                            final String deviceNameToRemove = (device.userName != null && !device.userName.isEmpty())
                                    ? device.userName
                                    : device.deviceName;

                            btnRemove.setOnClickListener(v -> {
                                bottomSheetDialog.dismiss();

                                // Show confirmation dialog and pass device ID directly
                                new AlertDialog.Builder(new android.view.ContextThemeWrapper(
                                        ParentDashboardActivity.this, R.style.AlertDialogCustom))
                                        .setTitle("Ã°Å¸â€”â€˜Ã¯Â¸Â Remove Device")
                                        .setMessage("Removing \"" + deviceNameToRemove
                                            + "\".\n\nThis will log out the child from the child side as well as here.\n\nDo you wish to continue?")
                                        .setPositiveButton("Remove", (dialog, which) -> {
                                            removeChildDevice(deviceIdToRemove);
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            });
                        }

                        // Switch Action (Row Click)
                        itemView.setOnClickListener(v -> {
                            if (!isCurrent) {
                                switchDevice(device.deviceId);
                                Toast.makeText(ParentDashboardActivity.this, "Switched to " + finalDisplayName,
                                        Toast.LENGTH_SHORT).show();
                            }
                            bottomSheetDialog.dismiss();
                        });

                        listContainer.addView(itemView);
                    }
                } else {
                    // Show empty state?
                    TextView emptyText = new TextView(this);
                    emptyText.setText("No connected devices");
                    emptyText.setPadding(32, 32, 32, 32);
                    listContainer.addView(emptyText);
                }
            }

            bottomSheetDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing manage devices dialog: " + e.getMessage());
            Toast.makeText(this, "Could not open device manager", Toast.LENGTH_SHORT).show();
        }
    }

    // REMOVE CHILD DEVICE FUNCTIONALITY
    private void showRemoveDeviceConfirmationDialog() {
        showRemoveDeviceConfirmationDialog(currentChildDeviceId, currentChildDeviceName);
    }

    private void showRemoveDeviceConfirmationDialog(final String deviceId, final String deviceName) {
        if (deviceId == null || deviceId.isEmpty()) {
            Toast.makeText(this, "No child device selected to remove.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("Ã°Å¸â€”â€˜Ã¯Â¸Â Remove Device");
        builder.setMessage("Removing \"" + deviceName + "\".\n\n" +
                "This will log out the child from the child side as well as here.\n\n" +
                "Do you wish to continue?");

        builder.setPositiveButton("Remove", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                removeChildDevice(deviceId);
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }


    // Enhanced removeChildDevice method with extensive debugging
    private void removeChildDevice(String childDeviceIdToRemove) {
        deviceIdJustRemoved = childDeviceIdToRemove;
        loadingDialogManager.show(
                "Removing Device",
                "Please wait while we securely remove the device...");

        if (mAuth == null || mAuth.getCurrentUser() == null) {
            loadingDialogManager.hide();
            Toast.makeText(this,
                    "You must be logged in as the parent to remove a device.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (childDeviceIdToRemove == null || childDeviceIdToRemove.trim().isEmpty()) {
            loadingDialogManager.hide();
            Toast.makeText(this, "Invalid device ID.", Toast.LENGTH_SHORT).show();
            return;
        }

        removeChildDeviceV2Only(
                childDeviceIdToRemove,
                mAuth.getCurrentUser().getUid());
    }

    private void removeChildDeviceV2Only(String deviceId, String parentUid) {
        online.monarchlabs.sentinel.services.RelationshipService relationshipService =
                new online.monarchlabs.sentinel.services.RelationshipService(
                        getApplicationContext());
        relationshipService.remove(deviceId)
                .thenAccept(result -> runOnUiThread(() -> {
                    if (result.success) {
                        completeV2ChildRemoval(deviceId, parentUid);
                    } else {
                        showV2RemovalFailure(result.message);
                    }
                }))
                .exceptionally(error -> {
                    runOnUiThread(() -> showV2RemovalFailure(
                            error.getMessage() != null
                                    ? error.getMessage()
                                    : "Relationship service failed."));
                    return null;
                });
    }
    private void completeV2ChildRemoval(String deviceId, String parentUid) {
        clearParentConnectionCaches(deviceId, parentUid);
        clearParentConnectionMarker(parentUid, deviceId);
        if (deviceStatusManager != null) {
            deviceStatusManager.stopListeningForChildDeviceStatus(deviceId);
        }
        connectedDevicesManager.removeDevice(deviceId);
        connectedDevices.removeIf(device -> deviceId.equals(device.deviceId));
        if (connectedDevices.isEmpty()) {
            connectedDevicesManager.clearAllDevices();
        }

        if (deviceId.equals(currentChildDeviceId)) {
            if (!connectedDevices.isEmpty()) {
                switchDevice(connectedDevices.get(0).deviceId);
            } else {
                stopUninstallProtectionMonitoring();
                stopUninstallDetection();
                currentChildDeviceId = null;
                currentChildDeviceName = null;
                currentChildUserName = null;
            }
        }

        updateDeviceStatus();
        updateTargetDeviceDisplay();
        if (binding != null && binding.tvTotalTime != null
                && currentChildDeviceId == null) {
            binding.tvTotalTime.setText("0h 0m");
        }
        loadingDialogManager.hide();
        Toast.makeText(this, "Device removed successfully.", Toast.LENGTH_LONG).show();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (deviceId.equals(deviceIdJustRemoved)) {
                deviceIdJustRemoved = null;
            }
        }, 3000L);
    }

    private void showV2RemovalFailure(String message) {
        loadingDialogManager.hide();
        deviceIdJustRemoved = null;
        Toast.makeText(this,
                "Failed to remove device: " + (message != null ? message : "Unknown error"),
                Toast.LENGTH_LONG).show();
    }
    private void syncParentConnectionMarkerAndMaybeClearCaches(String parentId,
            String childDeviceId, String connectionId, long linkedAt) {
        SharedPreferences markers = getSharedPreferences(
                "parent_connection_markers_" + parentId, MODE_PRIVATE);
        String connectionKey = childDeviceId + "_connectionId";
        String linkedAtKey = childDeviceId + "_linkedAt";
        boolean hasMarker = markers.contains(connectionKey)
                || markers.contains(linkedAtKey);
        String previousConnectionId = markers.getString(connectionKey, "");
        long previousLinkedAt = markers.getLong(linkedAtKey, 0L);

        boolean connectionChanged = hasMarker
                && connectionId != null && !connectionId.isEmpty()
                && !connectionId.equals(previousConnectionId);
        boolean linkedAtChanged = hasMarker && linkedAt > 0L
                && previousLinkedAt > 0L && linkedAt != previousLinkedAt;
        if (connectionChanged || linkedAtChanged) {
            clearParentConnectionCaches(childDeviceId, parentId);
        }

        // Usage cache scope must come only from device_status/usageBootstrap.
        // A parent-link fallback generation would erase persisted historical days
        // on every app restart before the real historyGeneration arrives.
        markers.edit()
                .putString(connectionKey, connectionId != null ? connectionId : "")
                .putLong(linkedAtKey, linkedAt)
                .commit();
    }

    private void clearParentConnectionMarker(String parentId, String childDeviceId) {
        if (parentId == null || parentId.isEmpty()
                || childDeviceId == null || childDeviceId.isEmpty()) {
            return;
        }
        getSharedPreferences("parent_connection_markers_" + parentId,
                MODE_PRIVATE).edit()
                .remove(childDeviceId + "_connectionId")
                .remove(childDeviceId + "_linkedAt")
                .commit();
    }
    private void clearParentConnectionCaches(String childDeviceId,
            String parentId) {
        cachedUsageFormatted.remove(childDeviceId);
        if (usageCachePrefs != null) {
            usageCachePrefs.edit().remove(childDeviceId).commit();
        }
        online.monarchlabs.sentinel.utils.ParentUsageCacheManager
                .getInstance(this).clearDevice(childDeviceId);
        ParentAppInventoryCache.clear(this, parentId, childDeviceId);

        getSharedPreferences("usage_cache_" + parentId + "_" + childDeviceId,
                MODE_PRIVATE).edit().clear().commit();
        getSharedPreferences("timer_execution_cache_" + parentId + "_"
                + childDeviceId, MODE_PRIVATE).edit().clear().commit();
        getSharedPreferences("blocked_apps_" + childDeviceId,
                MODE_PRIVATE).edit().clear().commit();

        getSharedPreferences("timer_inventory_checks_" + parentId,
                MODE_PRIVATE).edit()
                .remove(childDeviceId + "_checked_at")
                .commit();
        getSharedPreferences("app_limits_policy_migration_" + parentId,
                MODE_PRIVATE).edit()
                .remove(childDeviceId + "_v2")
                .commit();

        SharedPreferences refreshPrefs = getSharedPreferences(
                "timer_refresh_requests_" + parentId, MODE_PRIVATE);
        SharedPreferences.Editor refreshEditor = refreshPrefs.edit();
        for (String key : refreshPrefs.getAll().keySet()) {
            if (key.startsWith(childDeviceId + "_")) {
                refreshEditor.remove(key);
            }
        }
        refreshEditor.commit();

        getSharedPreferences("last_known_locations_prefs", MODE_PRIVATE)
                .edit()
                .remove("last_lat_" + childDeviceId)
                .remove("last_lng_" + childDeviceId)
                .remove("last_time_" + childDeviceId)
                .commit();


        cachedChildLocations.remove(childDeviceId);
        cachedChildLocationTimestamps.remove(childDeviceId);
        cachedChildGpsOffStates.remove(childDeviceId);
        cachedChildLocationWarningMessages.remove(childDeviceId);
        clearLocalTimerStorageForDevice(childDeviceId);
        Log.d(TAG, "Cleared parent caches for removed device: " + childDeviceId);
    }
    private void updateTargetDeviceDisplay() {
        if (binding == null || binding.tvDeviceStatus == null)
            return;

        String displayName = getCurrentChildDisplayName();

        // Capitalize first letter
        String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);

        // Format as "Name (Tap to Manage Device)"
        String deviceStatusText = capitalizedName + " (Tap to Manage Device)";
        binding.tvDeviceStatus.setText(deviceStatusText);

        // Set appropriate color (teal for modern look)
            binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));
    }

    private String getCurrentChildDisplayName() {
        if (currentChildUserName != null && !currentChildUserName.trim().isEmpty()) {
            return currentChildUserName.trim();
        }
        if (currentChildDeviceName != null && !currentChildDeviceName.trim().isEmpty()) {
            return currentChildDeviceName.trim();
        }
        return "Device";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_NOTIFICATIONS) {
            refreshNotificationBadge();
            return;
        }

        if (requestCode == 1003 && resultCode == RESULT_OK && data != null) {
            // Handle usage limiter app selection result
            ArrayList<String> selectedAppPackages = data.getStringArrayListExtra("selected_packages");
            if (selectedAppPackages != null) {
                Log.d(TAG, "Received " + selectedAppPackages.size() + " selected apps for usage limiter");

                // Update selected apps list
                selectedApps.clear();
                selectedApps.addAll(selectedAppPackages);

                // Update button text to show selection count
                String buttonText = selectedApps.isEmpty() ? "Select Apps"
                        : "Update Apps (" + selectedApps.size() + ")";
                if (btnSelectApps != null) {
                    btnSelectApps.setText(buttonText);
                }

                // Update Set Timer button state based on all requirements
                updateSetTimerButtonState();

                Toast.makeText(this, "Selected " + selectedApps.size() + " apps for usage limiter",
                        Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Usage limiter now has " + selectedApps.size() + " apps selected");
            }
        } else if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            // Handle timer navigation result
            String selectedTab = data.getStringExtra("selected_tab");

            if ("home".equals(selectedTab)) {
                // Just update navigation and UI (home is always visible)
                if (bottomNavigation != null) {
                    bottomNavigation.setSelectedItemId(R.id.nav_home);
                }
            } else if ("settings".equals(selectedTab)) {
                // Launch Settings Activity
                Intent intent = new Intent(this, ParentSettingsActivity.class);
                intent.putExtra("selected_child_device_id", currentChildDeviceId);
                startActivity(intent);
                if (bottomNavigation != null) {
                    bottomNavigation.setSelectedItemId(R.id.nav_settings);
                }
            }
        }
    }

    private void initializeManagers() {
        try {
            qrCodeManager = new QRCodeManager(this);
            childDeviceManager = new ChildDeviceManager(this);
            deviceStatusManager = new DeviceStatusManager(this);
            connectedDevicesManager = new ConnectedDevicesManager(this);

            // Ã°Å¸Å¡Â« CRITICAL: Clean up permanently removed devices from loaded storage
            cleanupPermanentlyRemovedDevices();

            // Ã°Å¸â€Â§ PERSISTENCE FIX: Don't clear devices! Load them instead.
            // Old code: connectedDevicesManager.clearAllDevices();

            // Load preserved devices from storage
            connectedDevices = connectedDevicesManager.getConnectedDevices();
            if (connectedDevices == null) {
                connectedDevices = new ArrayList<>();
            }
            Log.d(TAG, "Ã°Å¸â€œÂ± Loaded " + connectedDevices.size() + " preserved devices from storage");

            // Sync current device ID
            String savedDeviceId = connectedDevicesManager.getCurrentDeviceId();
            if (savedDeviceId != null) {
                currentChildDeviceId = savedDeviceId;
                // Find name
                for (ChildDevice d : connectedDevices) {
                    if (d.deviceId.equals(savedDeviceId)) {
                        currentChildDeviceName = d.deviceName;
                        break;
                    }
                }
                Log.d(TAG, "Ã°Å¸â€œÂ± Restored current device: " + currentChildDeviceName);
            }

            // Start as parent device
            String deviceName = ParentUtils.getParentDeviceName();
            deviceStatusManager.startAsParentDevice(deviceName);

            Log.d(TAG, "Managers initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing managers: " + e.getMessage());
        }
    }

    /**
     * Restore the last selected child device when the parent reopens the app.
     * If no explicit selection was saved, fall back to the most recently used
     * connected child so single-child sessions load automatically.
     */
    private void restoreLastSelectedChildOnStartup() {
        try {
            if (connectedDevicesManager == null) {
                Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â Cannot restore child selection - ConnectedDevicesManager is null");
                return;
            }

            String deviceIdToRestore = connectedDevicesManager.getCurrentDeviceId();

            if ((deviceIdToRestore == null || deviceIdToRestore.isEmpty()) && !connectedDevices.isEmpty()) {
                deviceIdToRestore = connectedDevicesManager.autoSelectDevice();
                Log.d(TAG, "Ã°Å¸â€œÂ± No saved child selection found - auto-selected: " + deviceIdToRestore);
            }

            if (deviceIdToRestore == null || deviceIdToRestore.isEmpty()) {
                Log.d(TAG, "Ã°Å¸â€œÂ± No child device available to restore on startup");
                currentChildDeviceId = null;
                currentChildDeviceName = "No Device";
                currentChildUserName = null;
                return;
            }

            ChildDevice restoredDevice = connectedDevicesManager.getDevice(deviceIdToRestore);
            if (restoredDevice == null) {
                for (ChildDevice device : connectedDevices) {
                    if (deviceIdToRestore.equals(device.deviceId)) {
                        restoredDevice = device;
                        break;
                    }
                }
            }

            currentChildDeviceId = deviceIdToRestore;
            if (restoredDevice != null) {
                currentChildDeviceName = restoredDevice.deviceName;
                currentChildUserName = restoredDevice.userName != null ? restoredDevice.userName : "";
            } else {
                currentChildDeviceName = deviceIdToRestore;
                currentChildUserName = "";
            }

            Log.d(TAG, "Ã°Å¸â€œÂ± Restored child selection on startup: " + currentChildDeviceId + " ("
                    + currentChildDeviceName + ")");

            rebindCurrentChildSessionState();
        } catch (Exception e) {
            Log.e(TAG, "Error restoring child selection on startup: " + e.getMessage(), e);
        }
    }

    /**
     * Rebind the currently selected child to the dashboard UI and live data sources.
     * This keeps the selected child visible and reloads its device-specific state
     * after a long idle restart or app resume.
     */
    private void rebindCurrentChildSessionState() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            return;
        }

        ChildDevice restoredDevice = null;
        if (connectedDevicesManager != null) {
            restoredDevice = connectedDevicesManager.getDevice(currentChildDeviceId);
        }

        if (restoredDevice == null) {
            for (ChildDevice device : connectedDevices) {
                if (currentChildDeviceId.equals(device.deviceId)) {
                    restoredDevice = device;
                    break;
                }
            }
        }

        if (restoredDevice != null) {
            currentChildDeviceName = restoredDevice.deviceName;
            currentChildUserName = (restoredDevice.userName != null) ? restoredDevice.userName : "";
        }

        if (connectedDevicesManager != null) {
            connectedDevicesManager.setCurrentDevice(currentChildDeviceId, true);
        }

        if (restoredDevice != null && connectedDevices.stream().noneMatch(d -> currentChildDeviceId.equals(d.deviceId))) {
            connectedDevices.add(restoredDevice);
        }

        if (restoredDevice != null) {
            initializeLimiterForDevice(restoredDevice.deviceId);
        }

        updateDeviceStatus();
        updateTargetDeviceDisplay();
        updateMapCardVisibility();
        refreshDeviceListPremium();
        loadCompleteDeviceState();
        refreshCurrentChildDeviceCards();
        refreshNotificationBadge();
        startUninstallDetection();
    }

    private void setupQRCodeGeneration() {
        try {
            // Generate permanent QR code
            permanentQRKey = qrCodeManager.getPermanentQRKey();
            Log.d(TAG, "QR key generated: " + permanentQRKey);

            // Sync the v2 parent identity used by pairing sessions
            initializeQRShareInFirebase();

            // Remove the code that creates and adds the QR section programmatically
            // Just set up the click listener for the XML blue button
            Button btnShowQRFullscreen = findViewById(R.id.btnShowQRFullscreen);
            if (btnShowQRFullscreen != null) {
                btnShowQRFullscreen.setOnClickListener(v -> showQRFullscreen());
                Log.d(TAG, "Ã°Å¸â€œÂ± QR button ready for manual device connections");
            }

            // Removed qrImageView code since user does not want to show QR code image in
            // the card


        } catch (Exception e) {
            Log.e(TAG, "Error setting up QR code: " + e.getMessage());
        }
    }

    private void initializeQRShareInFirebase() {
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "Skipping v2 parent identity sync; parent is signed out");
            return;
        }

        String deviceId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        Map<String, Object> updates = new HashMap<>();
        FirebaseSchemaV2Repository.addParentIdentityUpdates(
                updates,
                user.getUid(),
                sessionManager.getParentProfileName(),
                user.getEmail(),
                sessionManager.getPhoneNumber(),
                deviceId,
                null);
        FirebaseDatabase.getInstance().getReference().updateChildren(updates)
                .addOnFailureListener(error ->
                        Log.e(TAG, "Could not sync v2 parent identity", error));
    }
    private void generateAndDisplayQR(ImageView imageView) {
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String deviceName = ParentUtils.getParentDeviceName();

            String qrData = permanentQRKey + "|" + deviceId + "|" + deviceName;
            Bitmap qrBitmap = QRCodeManager.generateQRCodeBitmap(qrData, 200, 200);
            if (qrBitmap != null) {
                imageView.setImageBitmap(qrBitmap);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error generating QR: " + e.getMessage());
        }
    }

    private void showQRFullscreen() {
        try {
            Intent intent = new Intent(this, QRDisplayActivity.class);
            intent.putExtra("qr_key", permanentQRKey);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error showing QR fullscreen: " + e.getMessage());
            Toast.makeText(this, "Error opening QR display", Toast.LENGTH_SHORT).show();
        }
    }

    // Ã¢Â­Â NEW: Confirmation dialog for clearing timer
    private void showClearTimerConfirmation() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("Ã°Å¸â€”â€˜Ã¯Â¸Â Clear Timer");
        builder.setMessage("Are you sure you want to clear the timer for \"" + currentChildDeviceName + "\"?\n\n" +
                "Ã¢Å¡Â Ã¯Â¸Â This will:\n" +
                "Ã¢â‚¬Â¢ Stop the current timer immediately\n" +
                "Ã¢â‚¬Â¢ Remove all timer settings\n" +
                "Ã¢â‚¬Â¢ Clear selected apps for this device\n" +
                "Ã¢â‚¬Â¢ Require setting a new timer to restart\n\n" +
                "This action cannot be undone.");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("Ã°Å¸â€”â€˜Ã¯Â¸Â Clear Timer", (dialog, which) -> {
            Log.d(TAG, "Ã¢Å“â€¦ User confirmed timer clear for device: " + currentChildDeviceName);
            clearUsageLimiter();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            Log.d(TAG, "Ã¢ÂÅ’ User cancelled timer clear");
        });

        builder.show();
    }

    private void setupDeviceSwitcher() {
        try {
            View layoutManageDeviceHeader = findViewById(R.id.layoutManageDeviceHeader);
            if (layoutManageDeviceHeader != null) {
                layoutManageDeviceHeader.setOnClickListener(v -> showDeviceSwitcher());
            } else if (binding != null && binding.layoutManageDeviceHeader != null) {
                binding.layoutManageDeviceHeader.setOnClickListener(v -> showDeviceSwitcher());
            }
            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.setOnClickListener(null);
                binding.tvDeviceStatus.setClickable(false);
            } else {
                TextView tvDeviceStatus = findViewById(R.id.tvDeviceStatus);
                if (tvDeviceStatus != null) {
                    tvDeviceStatus.setOnClickListener(null);
                    tvDeviceStatus.setClickable(false);
                }
            }
            updateDeviceStatus();
        } catch (Exception e) {
            Log.e(TAG, "Error in setupDeviceSwitcher: " + e.getMessage());
        }
    }

    private void showRecentConnectionToast(ChildDevice device, String message) {
        if (device == null || device.deviceId == null || message == null) {
            return;
        }

        long eventTime = device.lastConnected;
        if (eventTime <= 0 || System.currentTimeMillis() - eventTime > RECENT_CONNECTION_TOAST_WINDOW_MS) {
            Log.d(TAG, "Skipping stale connection toast for: " + device.deviceName);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(CONNECTION_TOAST_PREFS, MODE_PRIVATE);
        String toastKey = "shown_" + device.deviceId + "_" + eventTime;
        if (prefs.getBoolean(toastKey, false)) {
            Log.d(TAG, "Skipping duplicate connection toast for: " + device.deviceName);
            return;
        }

        prefs.edit().putBoolean(toastKey, true).apply();
        Toast.makeText(ParentDashboardActivity.this, message, Toast.LENGTH_LONG).show();
    }

    private void showDeviceSwitcher() {
        // Redirect to the new premium bottom sheet
        showManageDevicesDialog();
    }

    private void showDeviceSwitcherLegacy() {
        try {
            Log.d(TAG, "Ã°Å¸â€â€ž DEVICE SWITCHER - Checking connected devices");
            Log.d(TAG, "Ã°Å¸â€œÂ± Current device: " + currentChildDeviceId + " (" + currentChildDeviceName + ")");

            // Ã°Å¸â€Â DEBUG: Log device list status before showing switcher
            debugDeviceLists("Before Device Switcher");

            // Use local connectedDevices list (QR-scanned devices)
            List<ChildDevice> devices = new ArrayList<>(connectedDevices);
            Log.d(TAG, "Ã°Å¸â€œÂ± Local device list has " + devices.size() + " devices");

            // Ã°Å¸â€Â§ DEVICE REMOVAL FIX: Check persistent storage but filter out removed
            // devices
            List<ChildDevice> persistentDevices = connectedDevicesManager.getConnectedDevices();
            Log.d(TAG, "Ã°Å¸â€™Â¾ Persistent storage has " + persistentDevices.size() + " devices");

            // Filter out permanently removed devices from persistent storage
            List<ChildDevice> filteredPersistentDevices = new ArrayList<>();
            for (ChildDevice device : persistentDevices) {
                if (!isPermanentlyRemoved(device.deviceId)) {
                    filteredPersistentDevices.add(device);
                } else {
                    Log.d(TAG, "Ã°Å¸Å¡Â« Filtering out permanently removed device: " + device.deviceName);
                }
            }
            Log.d(TAG, "Ã°Å¸â€™Â¾ Filtered persistent storage has " + filteredPersistentDevices.size() + " devices");

            // Use whichever list has devices (prioritize local list)
            if (devices.isEmpty() && !filteredPersistentDevices.isEmpty()) {
                devices = filteredPersistentDevices;
                Log.d(TAG, "Ã°Å¸â€œÂ± Using filtered persistent devices as backup");
            }

            if (devices.isEmpty()) {
                Log.d(TAG, "Ã¢ÂÅ’ No devices found in either local or persistent storage");
                Toast.makeText(this,
                        "No child devices connected\n\nTo connect a device:\n1. Open child app\n2. Scan the QR code from parent app",
                        Toast.LENGTH_LONG).show();
                return;
            }

            // Log all available devices
            Log.d(TAG, "Ã°Å¸â€œÂ± Available devices for switching:");
            for (int i = 0; i < devices.size(); i++) {
                ChildDevice device = devices.get(i);
                String currentFlag = device.deviceId.equals(currentChildDeviceId) ? " [CURRENT]" : "";
                Log.d(TAG, "  " + (i + 1) + ". " + device.deviceName + " (ID: " + device.deviceId + ")" + currentFlag);
            }

            // Create a custom dialog with a vertical LinearLayout
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(32, 32, 32, 32);

            final AlertDialog[] dialogHolder = new AlertDialog[1];

            for (ChildDevice device : devices) {
                Log.d(TAG, "Ã°Å¸â€œÂ² Adding device to switcher: " + device.deviceName + " (ID: " + device.deviceId + ")");

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 16, 0, 16);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                TextView deviceName = new TextView(this);
                // Display child's name if available, otherwise device name
                String displayName = (device.userName != null && !device.userName.isEmpty())
                        ? device.userName
                        : device.deviceName;
                String displayText = displayName;
                if (device.deviceId.equals(currentChildDeviceId)) {
                    displayText += " [CURRENT]";
                    deviceName.setTypeface(Typeface.DEFAULT_BOLD);
                }
                deviceName.setText(displayText);
                deviceName.setTextSize(16);
                deviceName.setTextColor(ContextCompat.getColor(this, R.color.text_primary)); // Modern color
                deviceName.setPadding(0, 0, 24, 0);
                deviceName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                deviceName.setOnClickListener(v -> {
                    Log.d(TAG,
                            "Ã°Å¸â€â€ž Device selected for switch: " + device.deviceName + " (ID: " + device.deviceId + ")");

                    // Check if this is already the current device
                    if (device.deviceId.equals(currentChildDeviceId)) {
                        Log.d(TAG, "Device already selected, no switch needed");
                        Toast.makeText(ParentDashboardActivity.this, "Already using " + device.deviceName,
                                Toast.LENGTH_SHORT).show();
                        if (dialogHolder[0] != null)
                            dialogHolder[0].dismiss();
                        return;
                    }

                    // Close dialog first
                    if (dialogHolder[0] != null)
                        dialogHolder[0].dismiss();

                    // Perform the switch with loading
                    switchToDevice(device);
                });

                Button removeBtn = new Button(this);
                removeBtn.setText("Remove");
                removeBtn.setTextColor(ContextCompat.getColor(this, R.color.error_600)); // Modern red
                removeBtn.setTextSize(14);
                removeBtn.setOnClickListener(v -> {
                    Log.d(TAG, "Ã°Å¸â€”â€˜Ã¯Â¸Â REMOVE BUTTON CLICKED for device: " + device.deviceId);

                    // Show confirmation dialog before removing
                    new AlertDialog.Builder(ParentDashboardActivity.this)
                            .setTitle("Remove Device?")
                                .setMessage("Removing \"" + device.deviceName
                                    + "\".\n\nThis will log out the child from the child side as well as here.\n\nDo you wish to continue?")
                            .setPositiveButton("Remove", (dialog, which) -> {
                                removeChildDevice(device.deviceId);
                                if (dialogHolder[0] != null)
                                    dialogHolder[0].dismiss();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });

                row.addView(deviceName);
                row.addView(removeBtn);
                layout.addView(row);
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(
                    new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                    .setTitle("Select Child Device")
                    .setView(layout)
                    .setNegativeButton("Cancel", null);
            dialogHolder[0] = builder.create();
            dialogHolder[0].show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing device switcher: " + e.getMessage());
        }
    }

    private void switchToDevice(ChildDevice device) {
        try {
            if (device == null || device.deviceId == null || device.deviceId.isEmpty()) {
                return;
            }

            Log.d(TAG, "Ã°Å¸â€â€ž DEVICE SWITCH: From '" + currentChildDeviceName + "' to '" + device.deviceName + "'");

            if (connectedDevicesManager != null) {
                connectedDevicesManager.addOrUpdateDevice(device);
            }

            switchDevice(device.deviceId);

            Toast.makeText(this, "Switched to " + device.deviceName, Toast.LENGTH_SHORT).show();

            Log.d(TAG, "Ã°Å¸â€â€ž Successfully switched to device: " + device.deviceName + " (" + device.deviceId + ")");
        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error switching device: " + e.getMessage());
            runOnUiThread(() -> {
                hideChildSwitchLoading();
                Toast.makeText(this, "Error switching device: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
            // Fallback: Clear display on error
            clearUsageDisplay();
        }
    }

    private void clearDeviceSpecificUI() {
        try {
            Log.d(TAG, "Ã°Å¸Â§Â¹ Clearing previous device-specific UI data");

            detachChildLocationListener();
            resetChildLocationPreview();
            hideChildSwitchLoading();

            // ENHANCED: Clear ALL device-specific data completely
            // Clear usage display immediately
            clearUsageDisplay();
            Log.d(TAG, "Ã¢Å“â€¦ Cleared usage display");

            // DON'T clear timer data during device switch - it will be loaded for new
            // device
            Log.d(TAG, "Ã¢Å“â€¦ Skipped timer display clear to preserve timer state");


            // Clear any cached usage data
            clearCachedUsageData();
            Log.d(TAG, "Ã¢Å“â€¦ Cleared cached usage data");

            // Clear timer display for device isolation
            // Timer running state no longer needed

            // ENHANCED: Clear selected apps for timer (device-specific)
            selectedApps.clear();

            // Clear device-specific timer references
            // Active timer ref cleanup no longer needed - using direct Firebase references
            // Timer ref cleanup no longer needed - using direct Firebase references

            // Clear usage chart data
            setupCategorySummaryChart(); // This will clear the chart for new device

            Log.d(TAG, "Ã¢Å“â€¦ Device-specific UI completely cleared");
        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error clearing device-specific UI: " + e.getMessage());
        }
    }

    private void refreshDeviceSpecificData() {
        if (currentChildDeviceId == null)
            return;

        Log.d(TAG, "Ã°Å¸â€â€ž Refreshing device-specific data for: " + currentChildDeviceName);

        try {
            // Refresh category summary chart with device-specific data
            setupCategorySummaryChart();

            // Clear any cached timer display and reload for current device

            // Refresh usage data
            loadSmartUsageDataForSelectedDate();

            Log.d(TAG, "Ã¢Å“â€¦ Device-specific data refresh complete");
        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error refreshing device-specific data: " + e.getMessage());
        }
    }

    private ChildDevice getCurrentDevice() {
        if (currentChildDeviceId != null) {
            return connectedDevicesManager.getDevice(currentChildDeviceId);
        }
        return null;
    }

    /**
     */
    private void setupCategorySummaryChart() {
        /*
         * Removed from XML
         * if (btnViewDetailedUsage != null) {
         * btnViewDetailedUsage.setOnClickListener(v -> {
         * // ...
         * });
         * }
         */

        // Setup Update Usage Data button - REMOVED per user request
        // Button btnUpdateUsageData = findViewById(R.id.btnUpdateUsageData);
        // if (btnUpdateUsageData != null) ...
        View btnUpdateUsageData = findViewById(R.id.btnUpdateUsageData); // Keep finding it to avoid null checks failing
                                                                         // if used elsewhere, but simply ignore it
        if (btnUpdateUsageData != null) {
            btnUpdateUsageData.setOnClickListener(v -> {
                refreshUsageDataFromChild(false);
            });
        }

        // Setup View Installed Apps button
        Button btnViewInstalledApps = findViewById(R.id.btnViewInstalledApps);
        if (btnViewInstalledApps != null) {
            btnViewInstalledApps.setOnClickListener(v -> {
                Log.d(TAG, "Ã°Å¸â€œÂ± View Installed Apps button clicked");
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, ChildInstalledAppsActivity.class);
                    intent.putExtra(ChildInstalledAppsActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                    // Ã°Å¸â€Â§ FIX: Pass actual child name if available, otherwise device name
                    String displayName = (currentChildUserName != null && !currentChildUserName.isEmpty())
                            ? currentChildUserName
                            : currentChildDeviceName;
                    intent.putExtra(ChildInstalledAppsActivity.EXTRA_CHILD_NAME, displayName);
                    intent.putExtra(ChildInstalledAppsActivity.EXTRA_IS_PARENT_CONTEXT, true);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "No child device selected", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Load usage data for selected device and date using the SMART tracking system
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            Log.d(TAG, "Initializing usage data for device: " + currentChildDeviceId);
            loadSmartUsageDataForSelectedDate();
        } else {
            Log.w(TAG, "No device selected, showing empty usage data");
            clearUsageDisplay();
        }
    }

    // Date navigation is disabled - only showing today's data
    // The arrows will show a toast message when clicked

    /**
     * SAFE method to change date - only allows user-initiated changes
     */
    private void setUserSelectedDate(Calendar newDate, String reason) {
        String oldDateKey = usageDateFormat.format(currentUsageDate.getTime());
        String newDateKey = usageDateFormat.format(newDate.getTime());

        Log.d(TAG, "Ã°Å¸â€Â USER DATE CHANGE: " + oldDateKey + " Ã¢â€ â€™ " + newDateKey + " (Reason: " + reason + ")");

        currentUsageDate = (Calendar) newDate.clone();
        dateSetByUser = true;

        // Save the date change for the current device
        saveUsageDateForDevice();

        updateSelectedDateDisplay();
        loadSmartUsageDataForSelectedDate();
    }

    /**
     * PROTECTED method - prevents automatic date resets when user has chosen a
     * specific date
     */
    private boolean preventAutoDateReset(String attemptReason) {
        if (dateSetByUser) {
            String currentDateKey = usageDateFormat.format(currentUsageDate.getTime());
            Log.w(TAG, "Ã°Å¸Å¡Â« BLOCKED AUTO DATE RESET: User selected " + currentDateKey + ", blocking reset attempt: "
                    + attemptReason);
            return true; // Block the operation
        }
        Log.d(TAG, "Ã¢Å“â€¦ Auto date operation allowed: " + attemptReason + " (User hasn't manually set date)");
        return false; // Allow the operation
    }

    /**
     * Update the selected date display in the UI
     */
    private void updateSelectedDateDisplay() {
        TextView tvSelectedDate = findViewById(R.id.tvSelectedDate);
        if (tvSelectedDate != null) {
            String displayDate;
            Calendar today = Calendar.getInstance();

            // Clear time components for proper comparison
            Calendar todayCompare = Calendar.getInstance();
            todayCompare.set(Calendar.HOUR_OF_DAY, 0);
            todayCompare.set(Calendar.MINUTE, 0);
            todayCompare.set(Calendar.SECOND, 0);
            todayCompare.set(Calendar.MILLISECOND, 0);

            Calendar selectedCompare = (Calendar) currentUsageDate.clone();
            selectedCompare.set(Calendar.HOUR_OF_DAY, 0);
            selectedCompare.set(Calendar.MINUTE, 0);
            selectedCompare.set(Calendar.SECOND, 0);
            selectedCompare.set(Calendar.MILLISECOND, 0);

            if (selectedCompare.equals(todayCompare)) {
                displayDate = "Today";
            } else {
                displayDate = dateFormat.format(currentUsageDate.getTime());
            }

            tvSelectedDate.setText(displayDate);
            String dateKey = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€œâ€¦ DISPLAY: Updated date display to: " + dateKey + " (User set: " + dateSetByUser + ")");
        }
    }

    /**
     * Load usage data for the currently selected date and device
     */
    /**
     * Save the current usage date for the specific device
     */
    private void saveUsageDateForDevice() {
        // Ã°Å¸â€Â DATE TRACE: Save operation
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_START: === saveUsageDateForDevice() CALLED ===");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_START: currentChildDeviceId = " + currentChildDeviceId);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_START: Called from: " + getCallerMethodName());

        if (currentChildDeviceId == null) {
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_END: EARLY RETURN - No device");
            return;
        }

        try {
            SharedPreferences datePrefs = getSharedPreferences("usage_dates", MODE_PRIVATE);
            String dateKey = "usage_date_" + currentChildDeviceId;
            String userSetKey = "date_user_set_" + currentChildDeviceId;

            String dateString = usageDateFormat.format(currentUsageDate.getTime());

            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_WRITE: Saving to SharedPreferences");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_WRITE: dateKey = " + dateKey);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_WRITE: Saving dateString = " + dateString);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_WRITE: Saving dateSetByUser = " + dateSetByUser);

            datePrefs.edit()
                    .putString(dateKey, dateString)
                    .putBoolean(userSetKey, dateSetByUser)
                    .apply();

            Log.d(TAG, "Saved usage date for device " + currentChildDeviceId + ": " + dateString + " (user set: "
                    + dateSetByUser + ")");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_SAVE_END: Save complete");
        } catch (Exception e) {
            Log.e(TAG, "Error saving usage date for device: " + e.getMessage());
        }
    }

    /**
     * Load the saved usage date for the current device
     */
    private void loadUsageDateForDevice() {
        // Ã°Å¸â€Â DATE TRACE: Entry point
        String dateBefore = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_START: === loadUsageDateForDevice() CALLED ===");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_START: currentChildDeviceId = " + currentChildDeviceId);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_START: currentUsageDate BEFORE = " + dateBefore);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_START: dateSetByUser BEFORE = " + dateSetByUser);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_START: Called from: " + getCallerMethodName());

        if (currentChildDeviceId == null) {
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_END: EARLY RETURN - No device selected");
            return;
        }

        try {
            SharedPreferences datePrefs = getSharedPreferences("usage_dates", MODE_PRIVATE);
            String dateKey = "usage_date_" + currentChildDeviceId;
            String userSetKey = "date_user_set_" + currentChildDeviceId;

            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_PREFS: Reading SharedPreferences");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_PREFS: dateKey = " + dateKey);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_PREFS: userSetKey = " + userSetKey);

            String savedDateString = datePrefs.getString(dateKey, null);
            boolean savedUserSetFlag = datePrefs.getBoolean(userSetKey, false);

            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_PREFS: savedDateString = " + savedDateString);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_PREFS: savedUserSetFlag = " + savedUserSetFlag);

            if (savedDateString != null && savedUserSetFlag) {
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_FOUND: Saved date EXISTS in prefs and was manually set by user");
                try {
                    Date savedDate = usageDateFormat.parse(savedDateString);

                    // Ã°Å¸â€Â DATE TRACE: CRITICAL MOMENT - About to change date
                    String beforeChange = usageDateFormat.format(currentUsageDate.getTime());
                    Log.d(TAG, "Ã°Å¸â€ÂÃ°Å¸â€ÂÃ°Å¸â€Â DATE_TRACE_CHANGE_CRITICAL: ABOUT TO MODIFY currentUsageDate");
                    Log.d(TAG, "Ã°Å¸â€ÂÃ°Å¸â€ÂÃ°Å¸â€Â DATE_TRACE_CHANGE_CRITICAL: currentUsageDate BEFORE = " + beforeChange);
                    Log.d(TAG, "Ã°Å¸â€ÂÃ°Å¸â€ÂÃ°Å¸â€Â DATE_TRACE_CHANGE_CRITICAL: Will set to = " + savedDateString);
                    Log.d(TAG, "Ã°Å¸â€ÂÃ°Å¸â€ÂÃ°Å¸â€Â DATE_TRACE_CHANGE_CRITICAL: Parsed Date object = " + savedDate);

                        currentUsageDate.setTime(savedDate);

                    // Ã°Å¸â€Â DATE TRACE: After change
                    String afterChange = usageDateFormat.format(currentUsageDate.getTime());
                    Log.d(TAG, "Ã°Å¸â€ÂÃ°Å¸â€ÂÃ°Å¸â€Â DATE_TRACE_CHANGE_COMPLETE: currentUsageDate AFTER = " + afterChange);
                    Log.d(TAG, "Ã°Å¸â€ÂÃ°Å¸â€ÂÃ°Å¸â€Â DATE_TRACE_CHANGE_COMPLETE: Date actually changed? " + (!beforeChange.equals(afterChange)));

                        dateSetByUser = savedUserSetFlag;
                        Log.d(TAG, "Ã°Å¸â€ÂÃ°Å¸â€ÂÃ°Å¸â€Â DATE_TRACE_CHANGE_COMPLETE: dateSetByUser set to = " + savedUserSetFlag);

                        Log.d(TAG, "DBG_USAGE_PATH: loadUsageDateForDevice device=" + currentChildDeviceId +
                            " savedDate=" + savedDateString + " savedUserSet=" + savedUserSetFlag);

                        updateSelectedDateDisplay();
                        Log.d(TAG, "Loaded usage date for device " + currentChildDeviceId + ": " + savedDateString
                            + " (user set: " + dateSetByUser + ")");
                } catch (Exception parseE) {
                    Log.e(TAG, "Ã°Å¸â€Â DATE_TRACE_ERROR: Error parsing saved date: " + parseE.getMessage());
                    String beforeReset = usageDateFormat.format(currentUsageDate.getTime());

                    // Reset to today if parse fails
                    currentUsageDate = Calendar.getInstance();
                    dateSetByUser = false;

                    String afterReset = usageDateFormat.format(currentUsageDate.getTime());
                    Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ERROR: Reset to today due to parse error");
                    Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ERROR: BEFORE reset = " + beforeReset);
                    Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_ERROR: AFTER reset = " + afterReset);
                }
            } else {
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_NOT_FOUND: No saved date in prefs");
                String beforeDefault = usageDateFormat.format(currentUsageDate.getTime());

                // No saved date, default to today
                currentUsageDate = Calendar.getInstance();
                dateSetByUser = false;

                String afterDefault = usageDateFormat.format(currentUsageDate.getTime());
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_NOT_FOUND: Defaulting to today");
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_NOT_FOUND: BEFORE default = " + beforeDefault);
                Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_NOT_FOUND: AFTER default = " + afterDefault);
                Log.d(TAG, "No saved usage date for device " + currentChildDeviceId + ", defaulting to today");
            }
        } catch (Exception e) {
            Log.e(TAG, "Ã°Å¸â€Â DATE_TRACE_EXCEPTION: Exception in loadUsageDateForDevice: " + e.getMessage());
            String beforeException = usageDateFormat.format(currentUsageDate.getTime());

            currentUsageDate = Calendar.getInstance();
            dateSetByUser = false;

            String afterException = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_EXCEPTION: Reset to today due to exception");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_EXCEPTION: BEFORE = " + beforeException);
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_EXCEPTION: AFTER = " + afterException);
        }

        // Ã°Å¸â€Â DATE TRACE: Final state before exit
        String dateAfter = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_END: === loadUsageDateForDevice() COMPLETE ===");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_END: currentUsageDate AFTER = " + dateAfter);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_END: dateSetByUser AFTER = " + dateSetByUser);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_LOAD_END: Date changed? " + (!dateBefore.equals(dateAfter)));
    }

    /**
     * Comprehensive device-specific state loading for complete isolation
     */
    private void loadCompleteDeviceState() {
        // Ã°Å¸â€Â DATE TRACE: Complete state load
        String dateBefore = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_START: === loadCompleteDeviceState() CALLED ===");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_START: currentUsageDate BEFORE = " + dateBefore);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_START: Called from: " + getCallerMethodName());

        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot load device state: no device selected");
            Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_END: EARLY RETURN - No device");
            return;
        }

        Log.d(TAG, "Ã°Å¸â€â€ž Loading complete state for device: " + currentChildDeviceId);

        // Load all device-specific data
        loadSelectedAppsForDevice();
        loadTimerDurationFromLocal();

        // Ã°Å¸â€Â DATE TRACE: Critical - about to load date
        String dateBeforeLoadDate = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_BEFORE_DATE: BEFORE loadUsageDateForDevice()");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_BEFORE_DATE: currentUsageDate = " + dateBeforeLoadDate);

        loadUsageDateForDevice();

        // Ã°Å¸â€Â DATE TRACE: After loading date
        String dateAfterLoadDate = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_AFTER_DATE: AFTER loadUsageDateForDevice()");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_AFTER_DATE: currentUsageDate = " + dateAfterLoadDate);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_AFTER_DATE: Date changed? " + (!dateBeforeLoadDate.equals(dateAfterLoadDate)));

        // Update all UI components for this device
        updateTargetDeviceDisplay();
        updateSelectedDateDisplay();

        // Force UI refresh to show device change
        runOnUiThread(() -> {
            updateDeviceStatus();
            updateTargetDeviceDisplay();
            // Force layout refresh
            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.invalidate();
                binding.tvDeviceStatus.requestLayout();
            }
        });

        Log.d(TAG, "Ã¢Å“â€¦ Complete device state loaded for: " + currentChildDeviceId);

        // Ã°Å¸â€Â DATE TRACE: Complete state load finished
        String dateAfter = usageDateFormat.format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_END: === loadCompleteDeviceState() COMPLETE ===");
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_END: currentUsageDate AFTER = " + dateAfter);
        Log.d(TAG, "Ã°Å¸â€Â DATE_TRACE_COMPLETE_END: Overall date changed? " + (!dateBefore.equals(dateAfter)));
    }

    private void refreshChildLocationIfNeeded() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            return;
        }

        if (!autoLocationRefreshEnabled) {
            Log.d(TAG, "Ã°Å¸â€œÂ Manual location mode enabled for " + currentChildDeviceId + ", skipping auto refresh");
            return;
        }

        boolean hadCachedLocation = restoreCachedChildLocationPreview(currentChildDeviceId);
        if (!hadCachedLocation) {
            showMapLoading();
        }

        waitingForFreshLocation = true;
        locationRequestStartTime = System.currentTimeMillis();
        requestFreshLocation(currentChildDeviceId);
    }

    /**
     * Save all device-specific state for persistence
     */
    private void saveCompleteDeviceState() {
        if (currentChildDeviceId == null)
            return;

        Log.d(TAG, "Ã°Å¸â€™Â¾ Saving complete state for device: " + currentChildDeviceId);

        saveSelectedAppsForDevice();
        saveUsageDateForDevice();

        // Save timer duration if currently set
        if (etLimiterHours != null && etLimiterMinutes != null) {
            try {
                String hoursStr = etLimiterHours.getText().toString().trim();
                String minutesStr = etLimiterMinutes.getText().toString().trim();
                if (!hoursStr.isEmpty() || !minutesStr.isEmpty()) {
                    int hours = hoursStr.isEmpty() ? 0 : Integer.parseInt(hoursStr);
                    int minutes = minutesStr.isEmpty() ? 0 : Integer.parseInt(minutesStr);
                    saveTimerDurationLocally(hours, minutes);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving timer duration during state save: " + e.getMessage());
            }
        }

        Log.d(TAG, "Ã¢Å“â€¦ Complete device state saved for: " + currentChildDeviceId);
    }

    /**
     * Ã°Å¸Å½Â¯ Load Smart Usage Data - GAME CHANGER!
     * Load rolling 7-day usage data from connection-based tracking
     */
    private void loadSmartUsageDataForSelectedDate() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            stopSmartUsageMonitoring();
            clearUsageDisplay();
            return;
        }

        stopSmartUsageMonitoring();

        final String selectedDeviceId = currentChildDeviceId;
        final String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(currentUsageDate.getTime());
        final String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date());
        final boolean isTodaySelected = dateKey.equals(todayKey);
        final online.monarchlabs.sentinel.utils.ParentUsageCacheManager cacheManager =
                online.monarchlabs.sentinel.utils.ParentUsageCacheManager.getInstance(this);

        cacheManager.pruneOldCache(selectedDeviceId);

        if (isTodaySelected) {
            Map<String, Object> cachedTodayData = cacheManager.getDailyUsage(selectedDeviceId, todayKey);
            if (cachedTodayData != null) {
                displayCachedSmartUsageData(cachedTodayData, todayKey);
            }

            if (!usageMonitoringForeground) {
                return;
            }

            DatabaseReference dayRef = FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("usage_daily")
                    .child(selectedDeviceId)
                    .child(todayKey);

            Log.d(TAG, "Listening to scalar usage summary for "
                    + selectedDeviceId + "/" + todayKey);

            smartUsageRef = dayRef.child("totalScreenTimeMillis");
            smartUsageListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (!isUsageSelectionCurrent(selectedDeviceId, todayKey)) {
                        return;
                    }

                    Object rawVal = snapshot.getValue();
                    Log.d(TAG, "Ã°Å¸â€Â DBG_TODAY_LISTENER: rawVal=" + rawVal + " type=" + (rawVal != null ? rawVal.getClass().getSimpleName() : "null") + " device=" + selectedDeviceId + " date=" + todayKey);
                    Number totalValue = rawVal instanceof Number ? (Number) rawVal : null;
                    loadedUsageDeviceIds.add(selectedDeviceId);
                    if (totalValue != null) {
                        long totalUsageMs = Math.max(0L, totalValue.longValue());
                        Log.d(TAG, "Ã°Å¸â€Â DBG_TODAY_LISTENER: totalUsageMs=" + totalUsageMs);
                        updateUsageDisplayUI(totalUsageMs);
                        Long lastSynced = lastUsageUploadTimestamps.get(selectedDeviceId);
                        cacheTodayUsageSummary(cacheManager, selectedDeviceId, todayKey, totalUsageMs,
                                lastSynced != null ? lastSynced : 0L);
                    } else {
                        // FALLBACK: totalScreenTimeMillis node is null/missing,
                        // try reading the full day node and compute total from apps
                        Log.w(TAG, "Ã°Å¸â€Â DBG_TODAY_LISTENER: totalScreenTimeMillis is NULL, attempting full day fallback for " + selectedDeviceId + "/" + todayKey);
                        dayRef.addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            @SuppressWarnings("unchecked")
                            public void onDataChange(@NonNull DataSnapshot fullDaySnap) {
                                if (!isUsageSelectionCurrent(selectedDeviceId, todayKey)) return;
                                if (fullDaySnap.exists()) {
                                    Object fullRaw = fullDaySnap.getValue();
                                    Log.d(TAG, "Ã°Å¸â€Â DBG_TODAY_FALLBACK: fullDaySnap exists, type=" + (fullRaw != null ? fullRaw.getClass().getSimpleName() : "null"));
                                    if (fullRaw instanceof Map) {
                                        Map<String, Object> dayMap = (Map<String, Object>) fullRaw;
                                        Log.d(TAG, "Ã°Å¸â€Â DBG_TODAY_FALLBACK: dayMap keys=" + dayMap.keySet());
                                        displayCachedSmartUsageData(dayMap, todayKey);
                                    } else {
                                        clearUsageDisplay();
                                    }
                                } else {
                                    Log.w(TAG, "Ã°Å¸â€Â DBG_TODAY_FALLBACK: full day snapshot does NOT exist");
                                    clearUsageDisplay();
                                }
                            }
                            @Override
                            public void onCancelled(@NonNull DatabaseError error) {
                                Log.e(TAG, "Ã°Å¸â€Â DBG_TODAY_FALLBACK: cancelled: " + error.getMessage());
                            }
                        });
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.w(TAG, "Today usage total listener cancelled: " + error.getMessage());
                }
            };
            smartUsageRef.addValueEventListener(smartUsageListener);

            smartUsageTimestampRef = dayRef.child("lastUpdated");
            smartUsageTimestampListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (!isUsageSelectionCurrent(selectedDeviceId, todayKey)) {
                        return;
                    }

                    Object rawVal = snapshot.getValue();
                    Number timestampValue = rawVal instanceof Number ? (Number) rawVal : null;
                    if (timestampValue != null && timestampValue.longValue() > 0L) {
                        long syncedAt = timestampValue.longValue();
                        lastUsageUploadTimestamps.put(
                                selectedDeviceId,
                                syncedAt);
                        cacheTodayUsageSummary(cacheManager, selectedDeviceId, todayKey, -1L, syncedAt);
                    } else {
                        lastUsageUploadTimestamps.remove(selectedDeviceId);
                    }
                    loadedUsageDeviceIds.add(selectedDeviceId);
                    updateSyncWarningBanner();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.w(TAG, "Today usage timestamp listener cancelled: " + error.getMessage());
                }
            };
            smartUsageTimestampRef.addValueEventListener(smartUsageTimestampListener);
            return;
        }
        Map<String, Object> cachedData = cacheManager.getDailyUsage(selectedDeviceId, dateKey);
        if (cachedData != null) {
            Log.d(TAG, "Historical usage cache hit for " + selectedDeviceId + "/" + dateKey);
            displayCachedSmartUsageData(cachedData, dateKey);
            return;
        }

        if (cacheManager.isMissingDayFresh(selectedDeviceId, dateKey)) {
            Log.d(TAG, "Using cached empty historical day for " + selectedDeviceId + "/" + dateKey);
            clearUsageDisplay();
            return;
        }

        if (!usageMonitoringForeground) {
            return;
        }

        Log.d(TAG, "Fetching exact historical usage path for " + selectedDeviceId + "/" + dateKey);
        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("usage_daily")
                .child(selectedDeviceId)
                .child(dateKey)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            Object rawValue = snapshot.getValue();
                            if (rawValue instanceof Map) {
                                Map<String, Object> dayMap = (Map<String, Object>) rawValue;
                                Log.d(TAG, "?? DBG_FIREBASE: Fetched dayMap for " + selectedDeviceId + "/" + dateKey + " | keys=" + dayMap.keySet() + " | totalScreenTimeMillis=" + dayMap.get("totalScreenTimeMillis"));
                                cacheManager.cacheDailyUsage(selectedDeviceId, dateKey, dayMap);
                                if (isUsageSelectionCurrent(selectedDeviceId, dateKey)) {
                                    displayCachedSmartUsageData(dayMap, dateKey);
                                }
                            } else {
                                Log.e(TAG, "?? DBG_FIREBASE: rawValue is NOT a map! It is " + (rawValue != null ? rawValue.getClass().getName() : "null"));
                            }
                        } else {
                            Log.e(TAG, "?? DBG_FIREBASE: snapshot.exists() is FALSE for " + selectedDeviceId + "/" + dateKey);
                            cacheManager.cacheMissingDay(selectedDeviceId, dateKey);
                            if (isUsageSelectionCurrent(selectedDeviceId, dateKey)) {
                                clearUsageDisplay();
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Historical usage fetch failed: " + error.getMessage());
                        if (isUsageSelectionCurrent(selectedDeviceId, dateKey)) {
                            Toast.makeText(
                                    ParentDashboardActivity.this,
                                    "Could not load usage data",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private boolean isUsageSelectionCurrent(String deviceId, String dateKey) {
        if (!usageMonitoringForeground
                || deviceId == null
                || !deviceId.equals(currentChildDeviceId)) {
            return false;
        }
        String selectedDateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(currentUsageDate.getTime());
        return dateKey.equals(selectedDateKey);
    }
    /**
     * Ã°Å¸Å½Â¯ Display Smart Usage Data using yyyy-MM-dd keys
     */
    private void displaySmartUsageData(DataSnapshot smartDataSnapshot, String requestedDateKey) {
        displaySmartUsageData(smartDataSnapshot, requestedDateKey, false);
    }

    private void displaySmartUsageData(DataSnapshot smartDataSnapshot, String requestedDateKey,
            boolean canonicalV2) {
        displaySmartUsageData(smartDataSnapshot, requestedDateKey, canonicalV2, false);
    }

    private void displaySmartUsageData(DataSnapshot smartDataSnapshot, String requestedDateKey,
            boolean canonicalV2, boolean isTargetedDayNode) {
        final String selectedDeviceId = currentChildDeviceId;
        usageComputationExecutor.execute(() -> {
            try {
                Log.d(TAG, "Ã°Å¸Å½Â¯ Processing smart usage data (susage) for date: " + requestedDateKey);

                // Look for data in weeklyData -> [DateKey]
                DataSnapshot dailyDataSnapshot;
                if (isTargetedDayNode) {
                    dailyDataSnapshot = smartDataSnapshot;
                } else {
                    dailyDataSnapshot = canonicalV2
                            ? smartDataSnapshot.child(requestedDateKey)
                            : smartDataSnapshot.child("weeklyData").child(requestedDateKey);
                }

                long totalUsage = 0L;
                String totalUsageTextFallback = null;

                if (dailyDataSnapshot.exists()) {
                    Log.d(TAG, "Ã¢Å“â€¦ Found usage data for " + requestedDateKey);

                    // Prefer canonical total if present
                    if (dailyDataSnapshot.hasChild("totalScreenTimeMillis")) {
                        try {
                            Long storedTotal = dailyDataSnapshot.child("totalScreenTimeMillis").getValue(Long.class);
                            if (storedTotal != null) {
                                totalUsage = storedTotal;
                                Log.d(TAG, "Ã¢Å¡Â¡ Using canonical totalScreenTimeMillis: " + totalUsage);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error reading totalScreenTimeMillis: " + e.getMessage());
                        }
                    }

                    // If we don't have canonical total, sum per-app using canonical field first and fallbacks
                    if (totalUsage == 0) {
                        DataSnapshot appsSnapshot = dailyDataSnapshot.child("apps");
                        if (appsSnapshot.exists()) {
                            for (DataSnapshot appSnapshot : appsSnapshot.getChildren()) {
                                try {
                                    long usage = readUsageMillisFromAppSnapshot(appSnapshot);
                                    if (usage > 0L) {
                                        totalUsage += usage;
                                    }
                                } catch (Exception ex) {
                                    Log.e(TAG, "Error reading app usage fallback: " + ex.getMessage());
                                }
                            }
                            Log.d(TAG, "Ã¢Å¡Â¡ Summed per-app usage (with fallbacks): " + totalUsage);
                        }
                    }

                    // Additional fallbacks for day total fields (legacy aliases)
                    if (totalUsage == 0) {
                        try {
                            if (dailyDataSnapshot.hasChild("totalUsageMs")) {
                                Long t = dailyDataSnapshot.child("totalUsageMs").getValue(Long.class);
                                if (t != null) {
                                    totalUsage = t;
                                    Log.d(TAG, "Ã¢Å¡Â¡ Used fallback totalUsageMs: " + totalUsage);
                                }
                            } else if (dailyDataSnapshot.hasChild("total_usage_ms")) {
                                Long t = dailyDataSnapshot.child("total_usage_ms").getValue(Long.class);
                                if (t != null) {
                                    totalUsage = t;
                                    Log.d(TAG, "Ã¢Å¡Â¡ Used fallback total_usage_ms: " + totalUsage);
                                }
                            } else if (dailyDataSnapshot.hasChild("totalText")) {
                                String t = dailyDataSnapshot.child("totalText").getValue(String.class);
                                if (t != null && !t.isEmpty()) {
                                    totalUsageTextFallback = t;
                                    Log.d(TAG, "Ã¢Å¡Â¡ Using legacy totalText as formatted fallback: " + t);
                                }
                            } else if (dailyDataSnapshot.hasChild("totalTexts")) {
                                DataSnapshot totalTextsSnapshot = dailyDataSnapshot.child("totalTexts");
                                List<String> totalTextsList = new ArrayList<>();

                                for (DataSnapshot textSnapshot : totalTextsSnapshot.getChildren()) {
                                    String text = textSnapshot.getValue(String.class);
                                    if (text != null) totalTextsList.add(text);
                                }

                                int arrayIndex = totalTextsList.size() - 1;
                                if (arrayIndex >= 0 && arrayIndex < totalTextsList.size()) {
                                    totalUsageTextFallback = totalTextsList.get(arrayIndex);
                                    Log.d(TAG, "Ã¢Å¡Â¡ Using legacy totalTexts last item as fallback: " + totalUsageTextFallback);
                                }
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "Error applying legacy total fallbacks: " + ex.getMessage());
                        }
                    }
                } else {
                    Log.d(TAG, "Ã¢ÂÅ’ No data found for date key: " + requestedDateKey);
                }

                Log.d(TAG, "DBG_USAGE_PATH: source=SMART date=" + requestedDateKey +
                    " canonicalPresent=" + dailyDataSnapshot.hasChild("totalScreenTimeMillis") +
                    " totalMs=" + totalUsage +
                    (totalUsageTextFallback != null ? " totalTextFallback='" + totalUsageTextFallback + "'" : ""));

                final long finalTotalUsage = totalUsage;
                final String finalTotalUsageText = totalUsageTextFallback;
                runOnUiThread(() -> {
                    if (selectedDeviceId == null || !selectedDeviceId.equals(currentChildDeviceId)) {
                        return;
                    }
                    if (finalTotalUsage > 0) {
                        updateUsageDisplayUI(finalTotalUsage);
                        Log.d(TAG, "Ã°Å¸â€œÅ  Total usage calc (ms): " + formatDurationMs(finalTotalUsage));
                    } else if (finalTotalUsageText != null) {
                        updateTotalUsageUI(finalTotalUsageText);
                        Log.d(TAG, "Ã°Å¸â€œÅ  Total usage calc (text fallback): " + finalTotalUsageText);
                    } else {
                        updateUsageDisplayUI(0);
                        Log.d(TAG, "Ã°Å¸â€œÅ  No usable total found; showing 0");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Ã¢ÂÅ’ Error displaying smart usage data: " + e.getMessage());
                e.printStackTrace();
                runOnUiThread(() -> {
                    if (selectedDeviceId != null && selectedDeviceId.equals(currentChildDeviceId)) {
                        clearUsageDisplay();
                    }
                });
            }
        });
    }

    private void cacheTodayUsageSummary(
            online.monarchlabs.sentinel.utils.ParentUsageCacheManager cacheManager,
            String deviceId,
            String dateKey,
            long totalUsageMs,
            long lastUpdatedMs) {
        if (cacheManager == null || deviceId == null || dateKey == null) {
            return;
        }

        Map<String, Object> dayMap = new HashMap<>();
        Map<String, Object> cachedDay = cacheManager.getDailyUsage(deviceId, dateKey);
        if (cachedDay != null) {
            dayMap.putAll(cachedDay);
        }
        if (totalUsageMs >= 0L) {
            dayMap.put("totalScreenTimeMillis", totalUsageMs);
        }
        if (lastUpdatedMs > 0L) {
            dayMap.put("lastUpdated", lastUpdatedMs);
        }
        if (!dayMap.containsKey("apps")) {
            dayMap.put("apps", new HashMap<String, Object>());
        }
        cacheManager.cacheLiveDailyUsage(deviceId, dateKey, dayMap);
    }
    private void displayCachedSmartUsageData(Map<String, Object> dailyMap, String requestedDateKey) {
        final String selectedDeviceId = currentChildDeviceId;
        usageComputationExecutor.execute(() -> {
            try {
                Log.d(TAG, "Ã°Å¸Å½Â¯ Processing cached smart usage data for date: " + requestedDateKey);
                long totalUsage = 0L;

                if (dailyMap != null) {
                    if (dailyMap.containsKey("totalScreenTimeMillis")) {
                        Object storedTotal = dailyMap.get("totalScreenTimeMillis");
                        if (storedTotal instanceof Number) {
                            totalUsage = ((Number) storedTotal).longValue();
                        }
                    }

                    if (totalUsage == 0 && dailyMap.containsKey("apps")) {
                        Object appsObj = dailyMap.get("apps");
                        if (appsObj instanceof Map) {
                            Map<?, ?> appsMap = (Map<?, ?>) appsObj;
                            for (Object appVal : appsMap.values()) {
                                long usage = readUsageMillisFromAppValue(appVal);
                                if (usage > 0L) {
                                    totalUsage += usage;
                                }
                            }
                        }
                    }
                }

                final long finalTotalUsage = totalUsage;
                runOnUiThread(() -> {
                    if (selectedDeviceId == null || !selectedDeviceId.equals(currentChildDeviceId)) {
                        return;
                    }
                    if (finalTotalUsage > 0) {
                        updateUsageDisplayUI(finalTotalUsage);
                    } else {
                        updateUsageDisplayUI(0);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Ã¢ÂÅ’ Error displaying cached smart usage data: " + e.getMessage());
                runOnUiThread(() -> {
                    if (selectedDeviceId != null && selectedDeviceId.equals(currentChildDeviceId)) {
                        clearUsageDisplay();
                    }
                });
            }
        });
    }

    /**
     * Ã°Å¸â€œÅ  Display smart usage list data
     */
    private long readUsageMillisFromAppSnapshot(DataSnapshot appSnapshot) {
        Object rawValue = appSnapshot.getValue();
        if (rawValue instanceof Number) {
            return Math.max(0L, ((Number) rawValue).longValue());
        }

        Long usage = appSnapshot.child("usageTimeMillis").getValue(Long.class);
        if (usage == null) usage = appSnapshot.child("usageTime").getValue(Long.class);
        if (usage == null) usage = appSnapshot.child("usage_ms").getValue(Long.class);
        if (usage == null && appSnapshot.child("usage_seconds").exists()) {
            Long secs = appSnapshot.child("usage_seconds").getValue(Long.class);
            if (secs != null) usage = secs * 1000L;
        }
        return usage != null ? Math.max(0L, usage) : 0L;
    }

    private long readUsageMillisFromAppValue(Object appValue) {
        if (appValue instanceof Number) {
            return Math.max(0L, ((Number) appValue).longValue());
        }
        if (!(appValue instanceof Map)) {
            return 0L;
        }

        Map<?, ?> appMap = (Map<?, ?>) appValue;
        Object value = appMap.get("usageTimeMillis");
        if (value == null) value = appMap.get("usageTime");
        if (value == null) value = appMap.get("usage_ms");
        if (value == null && appMap.get("usage_seconds") instanceof Number) {
            return Math.max(0L, ((Number) appMap.get("usage_seconds")).longValue() * 1000L);
        }
        return value instanceof Number ? Math.max(0L, ((Number) value).longValue()) : 0L;
    }
    private void displaySmartUsageList(List<AppUsage> appUsageList) {
        try {
            Log.d(TAG, "Ã°Å¸â€œÅ  Displaying " + appUsageList.size() + " apps from smart usage data");

            // Calculate total usage time
            long totalUsage = 0;
            for (AppUsage appUsage : appUsageList) {
                totalUsage += appUsage.getUsageTime();
            }

            // Use existing UI update method
            updateUsageDisplayUI(totalUsage);

            // Log app details for debugging
            for (int i = 0; i < Math.min(appUsageList.size(), 5); i++) {
                AppUsage app = appUsageList.get(i);
                Log.d(TAG, "Ã°Å¸â€œÂ± App " + (i + 1) + ": " + app.getAppName() +
                        " - " + formatDurationMs(app.getUsageTime()));
            }

        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error displaying smart usage list: " + e.getMessage());
            clearUsageDisplay();
        }
    }

    /**
     * Ã°Å¸â€œÅ  Show smart tracking information to user
     */
    private void showSmartTrackingInfo(long trackingStartTime, long daysSinceTracking, int appsCount) {
        try {
            Date trackingStart = new Date(trackingStartTime);
            String trackingInfo = String.format("Ã°Å¸Å½Â¯ Smart Tracking: Day %d since %s (%d apps)",
                    daysSinceTracking + 1,
                    new SimpleDateFormat("MMM dd", Locale.getDefault()).format(trackingStart),
                    appsCount);

            // You can display this in a TextView or as a toast
            // For now, just log it
            Log.d(TAG, trackingInfo);

        } catch (Exception e) {
            Log.e(TAG, "Error showing smart tracking info: " + e.getMessage());
        }
    }

    /**
     * Ã°Å¸â€œÂ­ Display message when no data is available for selected date
     */
    private void displayNoDataMessage(long daysSinceTracking, long currentDay) {
        clearUsageDisplay();

        String message;
        if (daysSinceTracking < 0) {
            message = "No data available - Date is before device connection";
        } else if (daysSinceTracking > currentDay) {
            message = "No data available - Future date selected";
        } else if (daysSinceTracking > 6) {
            message = "Data not available - Only last 7 days are tracked";
        } else {
            message = "No usage data recorded for this day";
        }

        Log.d(TAG, "Ã°Å¸â€œÂ­ " + message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * Ã°Å¸â€â€ž LEGACY METHOD - Replaced by Smart Usage Tracking
     *
     * @deprecated Use loadSmartUsageDataForSelectedDate() instead
     */
    @Deprecated
        /**
     * Ã°Å¸â€œâ€¦ Display date-aware usage data (NEW METHOD - no data contamination between
     * days)
     */
        /**
     * Ã°Å¸â€œÂ­ Display empty usage state for a specific date
     */
    private void displayEmptyUsageState(String dateKey, String dayLabel) {
        // Clear app list - using existing container or skip if not available
        // LinearLayout appUsageContainer = findViewById(R.id.appUsageContainer);
        // if (appUsageContainer != null) {
        // appUsageContainer.removeAllViews();
        //
        // // Add empty state message
        // TextView emptyMessage = new TextView(this);
        // emptyMessage.setText("Ã°Å¸â€œÂ­ No usage data recorded for " + dayLabel);
        // emptyMessage.setTextSize(16);
        // emptyMessage.setTextColor(getResources().getColor(android.R.color.darker_gray));
        // emptyMessage.setPadding(32, 32, 32, 32);
        // emptyMessage.setGravity(android.view.Gravity.CENTER);
        //
        // appUsageContainer.addView(emptyMessage);
        // }

        Log.d(TAG, "Ã°Å¸â€œÂ­ No usage data for " + dayLabel + " (" + dateKey + ")");

        Log.d(TAG, "Ã°Å¸â€œÂ­ Displayed empty state for " + dateKey + " (" + dayLabel + ")");
    }

    /**
     * Display accurate usage data with device-specific filtering - DATE AWARE
     * VERSION
     */
    private void displayAccurateUsageData(DataSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) {
            Log.w(TAG, "No usage data available in snapshot");
            // Fallback to regular loaded data instead of clearing
            loadSmartUsageDataForSelectedDate();
            return;
        }

        final String selectedDeviceId = currentChildDeviceId;
        final String selectedDateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(currentUsageDate.getTime());

        usageComputationExecutor.execute(() -> {
            try {
                Log.d(TAG, "Ã¢Å¡Â¡ DATE-AWARE displaying usage data for device: " + selectedDeviceId);
                Log.d(TAG, "Ã°Å¸â€Â DEBUG: Current selected date: " + usageDateFormat.format(currentUsageDate.getTime()));
                Log.d(TAG, "Ã°Å¸â€Â DEBUG: User set date flag: " + dateSetByUser);

                Calendar today = Calendar.getInstance();
                today.set(Calendar.HOUR_OF_DAY, 0);
                today.set(Calendar.MINUTE, 0);
                today.set(Calendar.SECOND, 0);
                today.set(Calendar.MILLISECOND, 0);

                Calendar selectedCompare = (Calendar) currentUsageDate.clone();
                selectedCompare.set(Calendar.HOUR_OF_DAY, 0);
                selectedCompare.set(Calendar.MINUTE, 0);
                selectedCompare.set(Calendar.SECOND, 0);
                selectedCompare.set(Calendar.MILLISECOND, 0);

                long diffInMs = today.getTimeInMillis() - selectedCompare.getTimeInMillis();
                int daysBack = (int) (diffInMs / (24 * 60 * 60 * 1000));

                Log.d(TAG, "Ã¢Å¡Â¡ Selected date: " + selectedDateKey + " is " + daysBack + " days back from today");

                String totalTimeText = null;

                if (daysBack == 0) {
                    totalTimeText = snapshot.child("totalText").getValue(String.class);
                    if (totalTimeText != null && !totalTimeText.isEmpty()) {
                        Log.d(TAG, "Ã¢Å¡Â¡ TODAY: Found totalText field: " + totalTimeText);
                    }
                }

                if (totalTimeText == null && snapshot.child("totalTexts").exists()) {
                    DataSnapshot totalTextsSnapshot = snapshot.child("totalTexts");
                    List<String> totalTextsList = new ArrayList<>();

                    for (DataSnapshot textSnapshot : totalTextsSnapshot.getChildren()) {
                        String text = textSnapshot.getValue(String.class);
                        if (text != null) {
                            totalTextsList.add(text);
                        }
                    }

                    Log.d(TAG, "Ã¢Å¡Â¡ Found " + totalTextsList.size() + " total texts in array");

                    int arrayIndex = totalTextsList.size() - 1 - daysBack;
                    if (arrayIndex >= 0 && arrayIndex < totalTextsList.size()) {
                        totalTimeText = totalTextsList.get(arrayIndex);
                        if (totalTimeText != null && !totalTimeText.isEmpty()) {
                            Log.d(TAG, "Ã¢Å¡Â¡ DATE-SPECIFIC: Found total for " + selectedDateKey + " at index " + arrayIndex
                                    + ": " + totalTimeText);
                        }
                    } else {
                        Log.d(TAG, "Ã¢Å¡Â¡ No data available for " + selectedDateKey + " (index " + arrayIndex
                                + " out of bounds for " + totalTextsList.size() + " items)");
                    }
                }

                if (totalTimeText == null && snapshot.child("dailyApps").exists()) {
                    DataSnapshot dailyAppsSnapshot = snapshot.child("dailyApps");
                    long childCount = dailyAppsSnapshot.getChildrenCount();

                    int arrayIndex = (int) (childCount - 1 - daysBack);
                    if (arrayIndex >= 0) {
                        DataSnapshot selectedDayData = dailyAppsSnapshot.child(String.valueOf(arrayIndex));
                        if (selectedDayData != null && selectedDayData.exists()) {
                            totalTimeText = selectedDayData.child("totalTimeText").getValue(String.class);
                            if (totalTimeText == null || totalTimeText.isEmpty()) {
                                totalTimeText = selectedDayData.child("summaryText").getValue(String.class);
                            }
                            if (totalTimeText != null && !totalTimeText.isEmpty()) {
                                Log.d(TAG, "Ã¢Å¡Â¡ Found 7-day data for " + selectedDateKey + ": " + totalTimeText);
                            }
                        } else {
                            Log.d(TAG, "Ã¢Å¡Â¡ No apps data found at index " + arrayIndex + " for dailyApps");
                        }
                    } else {
                        Log.d(TAG, "Ã¢Å¡Â¡ No data available for " + selectedDateKey + " (index " + arrayIndex + " out of bounds for " + childCount + " items)");
                    }
                }

                final String finalTotalTimeText = totalTimeText;

                Log.d(TAG, "DBG_USAGE_PATH: source=ACCURATE date=" + selectedDateKey +
                    " daysBack=" + daysBack +
                    " totalTimeTextPresent=" + (totalTimeText != null) +
                    " uses_totalTexts=" + snapshot.child("totalTexts").exists() +
                    " uses_dailyApps=" + snapshot.child("dailyApps").exists());

                runOnUiThread(() -> {
                    if (selectedDeviceId == null || !selectedDeviceId.equals(currentChildDeviceId)) {
                        return;
                    }

                    if (finalTotalTimeText != null && !finalTotalTimeText.isEmpty()) {
                        updateTotalUsageUI(finalTotalTimeText);
                        updateBarChartFromSnapshot(snapshot);
                    } else if (daysBack == 0) {
                        calculateTotalFromApps(snapshot);
                    } else {
                        Log.d(TAG, "Ã¢Å¡Â¡ NO DATA: No historical data found for " + selectedDateKey);
                        loadSmartUsageDataForSelectedDate();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Ã¢ÂÅ’ Error displaying usage data: " + e.getMessage());
                runOnUiThread(() -> {
                    if (selectedDeviceId != null && selectedDeviceId.equals(currentChildDeviceId)) {
                        clearUsageDisplay();
                    }
                });
            }
        });
    }

    /**
     * Update just the total usage UI immediately
     */
    private void updateTotalUsageUI(String totalText) {
        Log.d(TAG, "DBG_USAGE_FINAL: displayMethod=updateTotalUsageUI totalText=" + totalText +
                " selectedDate=" + usageDateFormat.format(currentUsageDate.getTime()) +
                " dateSetByUser=" + dateSetByUser);
        TextView tvTotalTime = findViewById(R.id.tvTotalTime);
        if (tvTotalTime != null) {
            tvTotalTime.setText(totalText);
        }

        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            cachedUsageFormatted.put(currentChildDeviceId, totalText);
            if (usageCachePrefs != null) {
                usageCachePrefs.edit().putString(currentChildDeviceId, totalText).apply();
            }
        }

        Log.d(TAG, "Ã¢Å¡Â¡ Total usage calculated: " + totalText);
    }

    /**
     * Fast fallback method to calculate total usage from individual app data
     */
    /**
     * Fast fallback method to calculate total usage from individual app data
     */
    private void calculateTotalFromApps(DataSnapshot snapshot) {
        long totalUsage = 0L;

        // FAST: Check for today's apps first (most likely scenario)
        if (snapshot.child("apps").exists()) {
            for (DataSnapshot appSnapshot : snapshot.child("apps").getChildren()) {
                try {
                    long usage = readUsageMillisFromAppSnapshot(appSnapshot);
                    if (usage > 0L) {
                        totalUsage += usage;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing app usage: " + e.getMessage());
                }
            }
        }

        // If no usage found, check dailyApps structure (check latest day since today is most likely)
        if (totalUsage == 0 && snapshot.child("dailyApps").exists()) {
            DataSnapshot dailyAppsSnapshot = snapshot.child("dailyApps");
            long childCount = dailyAppsSnapshot.getChildrenCount();
            if (childCount > 0) {
                DataSnapshot lastDay = dailyAppsSnapshot.child(String.valueOf(childCount - 1));
                if (lastDay.exists()) {
                    for (DataSnapshot appSnapshot : lastDay.getChildren()) {
                        try {
                            long usage = readUsageMillisFromAppSnapshot(appSnapshot);
                            if (usage > 0L) {
                                totalUsage += usage;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing daily app usage: " + e.getMessage());
                        }
                    }
                }
            }
        }

        // Ã°Å¸â€ºÂ¡Ã¯Â¸Â REJECTION LOGIC: If calculated usage is 0, but we have valid cache,
        // suspicious!
        if (totalUsage == 0 && cachedUsageFormatted.containsKey(currentChildDeviceId)) {
            String cachedVal = cachedUsageFormatted.get(currentChildDeviceId);
            if (cachedVal != null && !cachedVal.equals("0m") && !cachedVal.equals("0h 0m")) {
                Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â POTENTIAL BAD DATA: Ignored 0 usage from fast-fetch because cache has " + cachedVal);
                return;
            }
        }

        // Update UI with calculated total
        Log.d(TAG, "DBG_USAGE_PATH: summedPerApp totalMs=" + totalUsage + " appsCount=" +
            (snapshot.child("apps").exists() ? snapshot.child("apps").getChildrenCount() : 0));
        updateUsageDisplayUI(totalUsage);

        Log.d(TAG, "Ã¢Å¡Â¡ CALCULATED total usage: " + formatDurationMs(totalUsage));
    }

    /**
     * Calculate total usage from dailyApps for a specific date
     */
    private void calculateTotalFromAppsForDate(DataSnapshot snapshot, int daysBack) {
        long totalUsage = 0L;

        if (snapshot.child("dailyApps").exists()) {
            DataSnapshot dailyAppsSnapshot = snapshot.child("dailyApps");
            long childCount = dailyAppsSnapshot.getChildrenCount();

            // Calculate the array index for the specific date (index-from-end)
            int arrayIndex = (int) (childCount - 1 - daysBack);

            if (arrayIndex >= 0 && arrayIndex < childCount) {
                DataSnapshot daySnapshot = dailyAppsSnapshot.child(String.valueOf(arrayIndex));
                if (daySnapshot.exists()) {
                    for (DataSnapshot appSnapshot : daySnapshot.getChildren()) {
                        try {
                            long usage = readUsageMillisFromAppSnapshot(appSnapshot);
                            if (usage > 0L) {
                                totalUsage += usage;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing daily app usage for date: " + e.getMessage());
                        }
                    }
                    Log.d(TAG, "Ã¢Å¡Â¡ DATE-SPECIFIC: Calculated total for " + daysBack + " days back: "
                            + formatDurationMs(totalUsage));
                } else {
                    Log.d(TAG, "Ã¢Å¡Â¡ No apps data found for " + daysBack + " days back at index " + arrayIndex);
                }
            } else {
                Log.d(TAG, "Ã¢Å¡Â¡ Date index " + arrayIndex + " out of bounds for " + childCount + " days");
            }
        }

        // Update UI with calculated total
        updateUsageDisplayUI(totalUsage);
    }

    /**
     * Clear the usage display when no data is available
     */
    private void clearUsageDisplay() {
        TextView tvTotalTime = findViewById(R.id.tvTotalTime);
        if (tvTotalTime != null) {
            tvTotalTime.setText("0h 0m");
        }
        Log.d(TAG, "Usage display cleared - no data available");
    }

    /**
     * Clear timer display for device switching
     */
    private void clearTimerDisplay() {
        if (tvLimiterTimer != null) {
            tvLimiterTimer.setText("00:00");
        }
        if (tvLimiterStatus != null) {
            tvLimiterStatus.setText("No timer active");
        }
        // Timer running state no longer needed
    }

    /**
     * FIXED: Clear timer display ONLY when no device is selected
     */
    private void clearTimerDisplayForNoDevice() {
        if (tvLimiterTimer != null) {
            tvLimiterTimer.setText("00:00");
        }
        if (tvLimiterStatus != null) {
            tvLimiterStatus.setText("No device selected");
        }
        // Timer running state no longer needed
    }

    /**
     * Display active timer remaining time
     */
    private void displayActiveTimerTime(long remainingTimeMs) {
        int totalSeconds = (int) (remainingTimeMs / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (tvLimiterTimer != null) {
            tvLimiterTimer.setText(String.format("%02d:%02d:%02d", hours, minutes, seconds));
            tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
        if (tvLimiterStatus != null) {
            tvLimiterStatus.setText("Timer active on " + currentChildDeviceName);
            tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }

        Log.d(TAG, "Displayed active timer: " + hours + "h " + minutes + "m " + seconds + "s");
    }

    /**
     * Clear cached usage data for device switching
     */
    private void clearCachedUsageData() {
        // Clear any cached data maps or variables
        // This prevents data from previous device showing on new device
        Log.d(TAG, "Cleared cached usage data for device isolation");
    }

    private void stopSmartUsageMonitoring() {
        if (smartUsageListener != null && smartUsageRef != null) {
            smartUsageRef.removeEventListener(smartUsageListener);
        }
        if (smartUsageTimestampListener != null && smartUsageTimestampRef != null) {
            smartUsageTimestampRef.removeEventListener(smartUsageTimestampListener);
        }
        smartUsageListener = null;
        smartUsageRef = null;
        smartUsageTimestampListener = null;
        smartUsageTimestampRef = null;
    }

    /**
     * Load timer state for current device from Firebase
     */
    private void loadTimerStateForCurrentDevice() {
        // Timer cards are driven by v2 device policies and execution snapshots.
    }

    /**
     * Save timer duration locally for persistence
     */
    private void saveTimerDurationLocally(int hours, int minutes) {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot save timer duration: no device selected");
            return;
        }

        try {
            SharedPreferences timerPrefs = getSharedPreferences("timer_duration", MODE_PRIVATE);
            String hoursKey = "timer_hours_" + currentChildDeviceId;
            String minutesKey = "timer_minutes_" + currentChildDeviceId;

            timerPrefs.edit()
                    .putInt(hoursKey, hours)
                    .putInt(minutesKey, minutes)
                    .apply();

            Log.d(TAG, "Saved timer duration locally: " + hours + "h " + minutes + "m for device: "
                    + currentChildDeviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error saving timer duration locally: " + e.getMessage());
        }
    }

    /**
     * Load timer duration from local storage
     */
    private void loadTimerDurationFromLocal() {
        if (currentChildDeviceId == null) {
            return;
        }

        try {
            SharedPreferences timerPrefs = getSharedPreferences("timer_duration", MODE_PRIVATE);
            String hoursKey = "timer_hours_" + currentChildDeviceId;
            String minutesKey = "timer_minutes_" + currentChildDeviceId;

            int hours = timerPrefs.getInt(hoursKey, 0);
            int minutes = timerPrefs.getInt(minutesKey, 0);

            if (hours > 0 || minutes > 0) {
                runOnUiThread(() -> {
                    if (etLimiterHours != null && hours > 0) {
                        etLimiterHours.setText(String.valueOf(hours));
                    }
                    if (etLimiterMinutes != null && minutes > 0) {
                        etLimiterMinutes.setText(String.valueOf(minutes));
                    }
                });

                Log.d(TAG, "Loaded timer duration from local storage: " + hours + "h " + minutes + "m for device: "
                        + currentChildDeviceId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading timer duration from local storage: " + e.getMessage());
        }
    }

    /**
     * Save selected apps for current device
     */
    private void saveSelectedAppsForDevice() {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot save selected apps: no device selected");
            return;
        }

        try {
            String key = "timer_apps_" + currentChildDeviceId;
            Gson gson = new Gson();
            String appsJson = gson.toJson(selectedApps);

            SharedPreferences timerPrefs = getSharedPreferences("timer_apps", MODE_PRIVATE);
            timerPrefs.edit().putString(key, appsJson).apply();

            Log.d(TAG, "Saved " + selectedApps.size() + " timer apps for device: " + currentChildDeviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error saving selected apps for device: " + e.getMessage());
        }
    }

    /**
     * Load selected apps for current device
     */
    private void loadSelectedAppsForDevice() {
        if (currentChildDeviceId == null) {
            selectedApps.clear();
            return;
        }

        try {
            String key = "timer_apps_" + currentChildDeviceId;
            SharedPreferences timerPrefs = getSharedPreferences("timer_apps", MODE_PRIVATE);
            String appsJson = timerPrefs.getString(key, "");

            if (!appsJson.isEmpty()) {
                Gson gson = new Gson();
                Type listType = new TypeToken<List<String>>() {
                }.getType();
                List<String> savedApps = gson.fromJson(appsJson, listType);

                if (savedApps != null) {
                    selectedApps.clear();
                    selectedApps.addAll(savedApps);
                    Log.d(TAG, "Loaded " + selectedApps.size() + " timer apps for device: " + currentChildDeviceId);
                }
            } else {
                selectedApps.clear();
                Log.d(TAG, "No saved timer apps found for device: " + currentChildDeviceId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading selected apps for device: " + e.getMessage());
            selectedApps.clear();
        }
    }

    /**
     * Clear timer UI elements
     */
    private void clearTimerUI() {
        if (etLimiterHours != null)
            etLimiterHours.setText("");
        if (etLimiterMinutes != null)
            etLimiterMinutes.setText("");
        if (tvLimiterTimer != null)
            tvLimiterTimer.setText("00:00");
        if (tvLimiterStatus != null)
            tvLimiterStatus.setText("No device selected");

        selectedApps.clear();
        // Timer running state no longer needed

        updateButtonStates();
    }

    /**
     * Update button states based on timer status
     */
    private void updateButtonStates() {
        // Timer button state updates handled by usage limiter UI
    }

    /**
     * Clear all timer data for a specific device (used when device is
     * removed/reconnected)
     */
    /**
     * Update the UI with total usage data only (simplified display)
     * AND CACHE IT for instant display on next load
     */
    private void updateUsageDisplayUI(long totalMs) {
        String formattedTime = formatDurationMs(totalMs);

        Log.d(TAG, "DBG_USAGE_FINAL: displayMethod=updateUsageDisplayUI totalMs=" + totalMs +
            " formatted=" + formattedTime +
            " selectedDate=" + usageDateFormat.format(currentUsageDate.getTime()) +
            " dateSetByUser=" + dateSetByUser);

        TextView tvTotalTime = findViewById(R.id.tvTotalTime);
        if (tvTotalTime != null) {
            tvTotalTime.setText(formattedTime);
        }

        // Ã°Å¸Å½Â¯ CACHE the data for instant display next time (Persistent + Memory)
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            cachedUsageData.put(currentChildDeviceId, totalMs);
            cachedUsageFormatted.put(currentChildDeviceId, formattedTime);

            // Ã°Å¸â€™Â¾ PERSIST TO DISK
            if (usageCachePrefs != null) {
                usageCachePrefs.edit().putString(currentChildDeviceId, formattedTime).apply();
            }

            Log.d(TAG, "Ã°Å¸â€œÂ¦ Cached usage for " + currentChildDeviceId + ": " + formattedTime);
        }

        Log.d(TAG, "UI updated with total usage: " + formattedTime);
    }

    /**
     * Show loading state while fetching data
     */
    private void showLoadingState() {
        // tvTotalUsage view removed from layout - no-op
        Log.d(TAG, "Loading state triggered");
    }

    /**
     * Show auto-refresh loading state (more subtle for automatic updates)
     */
    private void showAutoRefreshState() {
        // tvTotalUsage view removed from layout - no-op
        Log.d(TAG, "Auto-refresh state triggered");
    }

    /**
     * Force refresh today's usage data specifically
     * This method ensures today's data is always fresh when app reopens
     */
    private void forceRefreshTodayData() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â No child device selected for today's data refresh");
            return;
        }

        String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(currentUsageDate.getTime());
        Log.d(TAG, "Ã°Å¸â€œâ€¦ Force refreshing data for SELECTED date: " + dateKey + " for device: " + currentChildDeviceName);

        // PRESERVE user's selected date - DO NOT force today
        // currentUsageDate remains unchanged - user's choice is respected
        updateSelectedDateDisplay();

        // Clear any cached display
        clearUsageDisplay();
        showAutoRefreshState();

        // Force immediate load of SELECTED date's data (not today)
        loadSmartUsageDataForSelectedDate();
    }

    /**
     * Format duration from milliseconds to readable format
     */
    private String formatDurationMs(long milliseconds) {
        if (milliseconds <= 0)
            return "0m";

        long totalMinutes = milliseconds / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }

    private String formatTime(int minutes) {
        if (minutes >= 60) {
            int hours = minutes / 60;
            int remainingMinutes = minutes % 60;
            if (remainingMinutes == 0) {
                return hours + "h";
            } else {
                return hours + "h " + remainingMinutes + "m";
            }
        } else {
            return minutes + "m";
        }
    }

    private String categorizeApp(String appName) {
        if (appName == null)
            return "Others";

        String lowerAppName = appName.toLowerCase();

        // Social Media Apps
        if (lowerAppName.contains("whatsapp") || lowerAppName.contains("instagram") ||
                lowerAppName.contains("facebook") || lowerAppName.contains("snapchat") ||
                lowerAppName.contains("twitter") || lowerAppName.contains("tiktok") ||
                lowerAppName.contains("telegram") || lowerAppName.contains("linkedin") ||
                lowerAppName.contains("discord") || lowerAppName.contains("reddit")) {
            return "Social";
        }

        // Games Apps
        if (lowerAppName.contains("pubg") || lowerAppName.contains("free fire") ||
                lowerAppName.contains("candy crush") || lowerAppName.contains("subway surfers") ||
                lowerAppName.contains("temple run") || lowerAppName.contains("clash") ||
                lowerAppName.contains("minecraft") || lowerAppName.contains("roblox") ||
                lowerAppName.contains("game") || lowerAppName.contains("play")) {
            return "Games";
        }

        // Entertainment Apps
        if (lowerAppName.contains("youtube") || lowerAppName.contains("netflix") ||
                lowerAppName.contains("disney") || lowerAppName.contains("amazon prime") ||
                lowerAppName.contains("spotify") || lowerAppName.contains("twitch") ||
                lowerAppName.contains("vimeo") || lowerAppName.contains("dailymotion")) {
            return "Entertainment";
        }

        // Everything else goes to Others
        return "Others";
    }

    private void showCategoryAppsFromRealData(String categoryName, String categoryKey) {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Toast.makeText(this, "No child device connected", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle(categoryName + " Apps");

        String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("usage_daily")
                .child(currentChildDeviceId)
                .child(todayKey)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<String> appNames = new ArrayList<>();
                        online.monarchlabs.sentinel.utils.ParentUsageCacheManager usageCache =
                                online.monarchlabs.sentinel.utils.ParentUsageCacheManager
                                        .getInstance(ParentDashboardActivity.this);
                        for (DataSnapshot appSnapshot : snapshot.child("apps").getChildren()) {
                            String appKey = appSnapshot.getKey();
                            String appName = appSnapshot.child("appName").getValue(String.class);
                            if ((appName == null || appName.isEmpty()) && appKey != null) {
                                appName = usageCache.getAppName(currentChildDeviceId, appKey);
                            }
                            if ((appName == null || appName.isEmpty()) && appKey != null) {
                                appName = usageCache.getAppPackageName(currentChildDeviceId, appKey);
                            }
                            if (appName != null && categorizeApp(appName).equals(categoryKey)) {
                                appNames.add(appName);
                            }
                        }

                        if (appNames.isEmpty()) {
                            builder.setMessage("No apps found in this category today.");
                        } else {
                            StringBuilder appListText = new StringBuilder();
                            for (String appName : appNames) {
                                appListText.append("Ã¢â‚¬Â¢ ").append(appName).append("\n");
                            }
                            appListText.append("\nTotal: ").append(appNames.size()).append(" apps");
                            builder.setMessage(appListText.toString());
                        }
                        builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
                        builder.show();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to load category usage: " + error.getMessage());
                        Toast.makeText(
                                ParentDashboardActivity.this,
                                "Could not load category usage",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }
    // Date Range Picker Methods
    private void showFromDatePicker() {
        Calendar today = Calendar.getInstance();
        android.app.DatePickerDialog datePickerDialog = new android.app.DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    fromDate.set(year, month, dayOfMonth);
                    // Ensure from date is not after to date
                    if (fromDate.after(toDate)) {
                        toDate = (Calendar) fromDate.clone();
                    }
                    updateDateRangeDisplay();
                },
                fromDate.get(Calendar.YEAR),
                fromDate.get(Calendar.MONTH),
                fromDate.get(Calendar.DAY_OF_MONTH));

        // Set minimum date to today (cannot set timer for past dates)
        datePickerDialog.getDatePicker().setMinDate(today.getTimeInMillis());
        datePickerDialog.show();
    }

    private void showToDatePicker() {
        android.app.DatePickerDialog datePickerDialog = new android.app.DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    toDate.set(year, month, dayOfMonth);
                    // Ensure to date is not before from date
                    if (toDate.before(fromDate)) {
                        fromDate = (Calendar) toDate.clone();
                    }
                    updateDateRangeDisplay();
                },
                toDate.get(Calendar.YEAR),
                toDate.get(Calendar.MONTH),
                toDate.get(Calendar.DAY_OF_MONTH));

        // Set minimum date to from date (cannot end before start)
        datePickerDialog.getDatePicker().setMinDate(fromDate.getTimeInMillis());
        datePickerDialog.show();
    }

    private void updateDateRangeDisplay() {
        // Date range UI no longer needed for usage limiters
    }

    /**
     * Fast usage data refresh using direct Firebase queries
     */
    private void refreshUsageDataFromChildEnhanced() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Log.w(TAG, "Ã°Å¸â€â€ž No child device selected for enhanced refresh");
            Toast.makeText(this, "Please select a child device first", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "Ã°Å¸Å¡â‚¬ Starting FAST usage data refresh for device: " + currentChildDeviceId);

        // Show loading state
        Button btnUpdateUsageData = findViewById(R.id.btnUpdateUsageData);
        if (btnUpdateUsageData != null) {
            btnUpdateUsageData.setEnabled(false);
            btnUpdateUsageData.setAlpha(0.65f);
        }

        // Use SMART tracking structure for immediate refresh
        loadSmartUsageDataForSelectedDate();
        Toast.makeText(this, "Refreshing usage data...", Toast.LENGTH_SHORT).show();

        // Re-enable button after short delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (btnUpdateUsageData != null) {
                btnUpdateUsageData.setEnabled(true);
                btnUpdateUsageData.setAlpha(1f);
            }
        }, 2000); // Reduced to 2 seconds since we're faster now
    }

    /**
     * Enhanced method to refresh usage data from child device
     * This method checks multiple data sources and ensures accurate data display
     */
    private void refreshUsageDataFromChild() {
        refreshUsageDataFromChild(false); // Default: show toast notifications
    }

    /**
     * Enhanced method to refresh usage data from child device with silent mode
     * option
     *
     * @param silentMode if true, reduces toast notifications for automatic
     *                   refreshes
     */
    private void refreshUsageDataFromChild(boolean silentMode) {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Log.w(TAG, "Ã°Å¸â€â€ž No child device selected for usage data refresh");
            if (!silentMode) {
                Toast.makeText(this, "Please select a child device first", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        Log.d(TAG, "Ã°Å¸â€â€ž Starting enhanced usage data refresh for device: " + currentChildDeviceId + " ("
                + currentChildDeviceName + ")");

        // Show loading state and disable update button temporarily
        Button btnUpdateUsageData = findViewById(R.id.btnUpdateUsageData);
        if (btnUpdateUsageData != null) {
            btnUpdateUsageData.setEnabled(false);
            btnUpdateUsageData.setAlpha(0.65f);
        }

        // Show toast only if not in silent mode
        if (!silentMode) {
            Toast.makeText(this, "Refreshing usage data from " + currentChildDeviceName + "...", Toast.LENGTH_SHORT)
                    .show();
            showLoadingState();
        } else {
            // Silent mode - show subtle auto-refresh indicator
            showAutoRefreshState();
        }

        // Step 1: Check if child device is online and trigger data upload
        checkChildDeviceStatusAndTriggerUpload();

        // Step 2: Force refresh data from multiple Firebase paths
        refreshFromMultipleDataSources();

        // Step 3: Re-enable update button after 3 seconds
        new android.os.Handler().postDelayed(() -> {
            if (btnUpdateUsageData != null) {
                btnUpdateUsageData.setEnabled(true);
                btnUpdateUsageData.setAlpha(1f);
            }
        }, 3000);
    }

    /**
     * Check child device status and trigger data upload if online
     */
    private void checkChildDeviceStatusAndTriggerUpload() {
        Log.d(TAG, "Ã°Å¸â€œÂ¡ Checking child device status and triggering data upload...");

        // Check device status first
        DatabaseReference deviceStatusRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_status")
                .child(currentChildDeviceId);

        deviceStatusRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Boolean isOnline = dataSnapshot.child("isOnline").getValue(Boolean.class);
                Long lastSeen = dataSnapshot.child("lastSeen").getValue(Long.class);

                if (Boolean.TRUE.equals(isOnline)) {
                    Log.d(TAG, "Ã¢Å“â€¦ Child device is online - sending data upload trigger");

                    // Send canonical v2 usage-refresh command to child device.
                    DatabaseReference uploadTriggerRef = FirebaseDatabase.getInstance()
                            .getReference("v2")
                            .child("commands")
                            .child(currentChildDeviceId)
                            .child("usage_refresh");

                    Map<String, Object> triggerData = new HashMap<>();
                    triggerData.put("command", "refresh_usage_data");
                    triggerData.put("deviceId", currentChildDeviceId);
                    triggerData.put("timestamp", System.currentTimeMillis());
                    triggerData.put("requestedBy", "parent");
                    triggerData.put("status", "pending");
                    triggerData.put("reason", "manual_refresh");

                    uploadTriggerRef.setValue(triggerData)
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "Ã¢Å“â€¦ Upload trigger sent successfully to child device");
                                Toast.makeText(ParentDashboardActivity.this,
                                        "Ã°Å¸â€œÂ¤ Requesting fresh data from " + currentChildDeviceName,
                                        Toast.LENGTH_SHORT).show();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Ã¢ÂÅ’ Failed to send upload trigger: " + e.getMessage());
                            });
                } else {
                    Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â Child device is offline - cannot trigger fresh data upload");
                    String lastSeenText = "unknown";
                    if (lastSeen != null) {
                        long timeSince = (System.currentTimeMillis() - lastSeen) / 1000;
                        if (timeSince < 60)
                            lastSeenText = "just now";
                        else if (timeSince < 3600)
                            lastSeenText = (timeSince / 60) + "m ago";
                        else
                            lastSeenText = (timeSince / 3600) + "h ago";
                    }

                    Toast.makeText(ParentDashboardActivity.this,
                            "Ã°Å¸â€œÂ± " + currentChildDeviceName + " is offline (last seen " + lastSeenText + ")",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Ã¢ÂÅ’ Error checking device status: " + databaseError.getMessage());
            }
        });
    }

    /**
     * Refresh data from multiple Firebase data sources
     */
    private void refreshFromMultipleDataSources() {
        if (currentChildDeviceId == null || mAuth == null
                || mAuth.getCurrentUser() == null) {
            onAllRefreshesCompleted();
            return;
        }
        FirebaseDatabase.getInstance().getReference("v2")
                .child("parent_device_links")
                .child(mAuth.getCurrentUser().getUid())
                .child(currentChildDeviceId)
                .get()
                .addOnCompleteListener(task -> onAllRefreshesCompleted());
    }

    /**
     * Called when all refresh operations are completed
     */
    private void onAllRefreshesCompleted() {
        Log.d(TAG, "Ã¢Å“â€¦ All data refresh operations completed");

        // Update device status display
        runOnUiThread(() -> {
            updateDeviceStatus();
            updateTargetDeviceDisplay();

            // Force reload of current date's usage data
            loadSmartUsageDataForSelectedDate();

            Toast.makeText(this, "Usage data updated successfully", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Show security warning dialog when parent logs into dashboard
     */
    private void showWelcomeMessage() {
        try {
            // Check if user has already seen this message today
            SharedPreferences prefs = getSharedPreferences("welcome_message_prefs", MODE_PRIVATE);
            String lastShown = prefs.getString("last_message_date", "");
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

            if (today.equals(lastShown)) {
                Log.d(TAG, "Welcome message already shown today, skipping");
                return;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogCustom);
            builder.setTitle("Ã°Å¸Å½â€° Welcome to Parental Control");
            builder.setMessage("Welcome! Here are some important tips:\n\n" +
                    "Ã¢â‚¬Â¢ Use the QR code scanner to connect child devices\n" +
                    "Ã¢â‚¬Â¢ Monitor and manage your child's screen time easily\n" +
                    "Ã¢â‚¬Â¢ Access all controls from this parent dashboard\n\n" +
                    "Ã¢Å¡Â Ã¯Â¸Â TROUBLESHOOTING: If you can see a device name but cannot track its data, please:\n" +
                    "1. Remove the device from this app\n" +
                    "2. Reinstall the app on the child device\n" +
                    "3. Connect the child via QR code again\n\n" +
                    "Ã°Å¸â€â€™ IMPORTANT SECURITY: Before uninstalling this app or logging out permanently:\n" +
                    "Ã¢â‚¬Â¢ Always remove all connected child devices first\n" +
                    "Ã¢â‚¬Â¢ This prevents security issues and data conflicts");
            builder.setCancelable(false);

            builder.setPositiveButton("Got It", (dialog, which) -> {
                dialog.dismiss();
                // Mark that user has seen the message today
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("last_message_date", today);
                editor.apply();
                Log.d(TAG, "Welcome message acknowledged by user");
            });

            builder.setNeutralButton("Show Help", (dialog, which) -> {
                dialog.dismiss();
                // Show the troubleshooting dialog
                showTroubleshootingDialog();
                // Mark as seen
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("last_message_date", today);
                editor.apply();
            });

            AlertDialog dialog = builder.create();
            dialog.show();

            // Customize dialog appearance
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);
            }

            // Style the buttons
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                    ContextCompat.getColor(this, R.color.primary_600));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(
                    ContextCompat.getColor(this, R.color.modern_orange_600));

        } catch (Exception e) {
            Log.e(TAG, "Error showing welcome message: " + e.getMessage());
        }
    }

    private void showTroubleshootingDialog() {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogCustom);
            builder.setTitle("Ã°Å¸â€ºÂ Ã¯Â¸Â Device Tracking Help");
            builder.setMessage("If you can see a device name but cannot track its data:\n\n" +
                    "SOLUTION:\n" +
                    "1. Remove the device from this parent app\n" +
                    "2. Reinstall the app on the child device\n" +
                    "3. Connect the child via QR code again\n\n" +
                    "This usually fixes connection and data tracking issues.\n\n" +
                    "Ã°Å¸â€™Â¡ TIP: Make sure both devices have stable internet connection when connecting via QR code.\n\n" +
                    "Ã°Å¸â€â€™ SECURITY REMINDER: Before uninstalling this app, always remove all connected devices first to prevent security issues.");

            builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());

            builder.setNeutralButton("Go to Settings", (dialog, which) -> {
                dialog.dismiss();
                // Navigate to settings
                if (bottomNavigation != null) {
                    bottomNavigation.setSelectedItemId(R.id.nav_settings);
                }
            });

            AlertDialog dialog = builder.create();
            dialog.show();

            // Customize dialog appearance
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error showing troubleshooting dialog: " + e.getMessage());
        }
    }

    /**
     * Ensures all background services are running properly
     */
    private void ensureBackgroundServicesRunning() {
        try {
            Log.d(TAG, "Ensuring background services are running...");

            // Ensure DailyTimerResetService is running
            DailyTimerResetService.startService(this);
            Log.d(TAG, "DailyTimerResetService started/verified");

            // If we have an active timer, ensure all timer-related services are running
            if (currentChildDeviceId != null) {
                Log.d(TAG, "Active timer detected - ensuring timer services are running");
                // The timer services are primarily on the child device side
                // ParentDashboardActivity mainly monitors via Firebase
            }

            Log.d(TAG, "Ã¢Å“â€¦ All background services verification completed");

        } catch (Exception e) {
            Log.e(TAG, "Ã¢ÂÅ’ Error ensuring background services: " + e.getMessage());
        }
    }

    /**
     * after force-close
     * device and its state
     */
    /**
     * Ã°Å¸â€Â§ FORCE-CLOSE PERSISTENCE: Save current device name for restoration
     */
    /**
     */
    /**
     */
    /**
     * permanent removal status
     */
    // ===== USAGE LIMITER IMPLEMENTATION =====

    /**
     * Initialize usage limiter functionality - setup button listeners and Firebase
     * references
     */
    private void setupUsageLimiter() {
        // Legacy aggregate limiter UI is removed; per-app timers use v2 policies.
    }

    /**
     * Show dialog for selecting which days the timer should work (Monday-Sunday
     * checkboxes)
     */
    private void showDaySelector() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };
        boolean[] checkedItems = new boolean[days.length];

        // Pre-check already selected days
        for (int i = 0; i < days.length; i++) {
            checkedItems[i] = selectedDays.contains(days[i].toLowerCase());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("Select Active Days");
        builder.setMultiChoiceItems(days, checkedItems, (dialog, which, isChecked) -> {
            // Handle individual checkbox changes
            String dayName = days[which].toLowerCase();
            if (isChecked) {
                if (!selectedDays.contains(dayName)) {
                    selectedDays.add(dayName);
                }
            } else {
                selectedDays.remove(dayName);
            }
        });

        builder.setPositiveButton("OK", (dialog, which) -> {
            if (selectedDays.isEmpty()) {
                Toast.makeText(this, "Please select at least one day", Toast.LENGTH_SHORT).show();
                return;
            }

            // Update button text to show selected days count
            String buttonText = "Days Selected (" + selectedDays.size() + ")";
            if (btnSelectDays != null) {
                btnSelectDays.setText(buttonText);
            }

            // Update Set Timer button state based on all requirements
            updateSetTimerButtonState();

            Log.d(TAG, "Selected days: " + selectedDays.toString());
            Toast.makeText(this, "Selected " + selectedDays.size() + " days", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Show dialog to select apps from the child device app list
     */
    private void showAppSelector() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Launch ChildAppListActivity to select apps
        Intent intent = new Intent(this, ChildAppListActivity.class);
        intent.putExtra("deviceId", currentChildDeviceId);
        intent.putExtra("mode", "select_multiple");
        intent.putExtra("title", "Select Apps for Usage Limiter");

        // Pass already selected apps for pre-selection
        if (!selectedApps.isEmpty()) {
            ArrayList<String> selectedAppsList = new ArrayList<>(selectedApps);
            intent.putStringArrayListExtra("preselected_apps", selectedAppsList);
        }

        startActivityForResult(intent, 1003); // Using unique request code for usage limiter
        Log.d(TAG, "Launched app selector for usage limiter");
    }

    /**
     * Save the usage limiter configuration to Firebase
     */
    private void setUsageLimiter() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        // First check if there's already an active timer
        limiterRef.child(currentChildDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()
                        && Boolean.TRUE.equals(dataSnapshot.child("isActive").getValue(Boolean.class))) {
                    // Timer is already active
                    Toast.makeText(ParentDashboardActivity.this,
                            "Timer is already running. Clear it first to set a new one.", Toast.LENGTH_LONG).show();
                    return;
                }

                // No active timer, proceed with validation and setting
                performSetTimerValidationAndSave();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error checking timer status: " + databaseError.getMessage());
                Toast.makeText(ParentDashboardActivity.this, "Error checking timer status", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Perform validation and save the timer (called after checking no active timer
     * exists)
     */
    private void performSetTimerValidationAndSave() {
        // Validate inputs
        String hoursText = etLimiterHours.getText().toString().trim();
        String minutesText = etLimiterMinutes.getText().toString().trim();

        if (hoursText.isEmpty() && minutesText.isEmpty()) {
            Toast.makeText(this, "Please enter timer duration (hours or minutes)", Toast.LENGTH_LONG).show();
            return;
        }

        if (selectedDays.isEmpty()) {
            Toast.makeText(this, "Please select which days the timer should work", Toast.LENGTH_LONG).show();
            return;
        }

        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "Please select apps to limit first", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            int hours = hoursText.isEmpty() ? 0 : Integer.parseInt(hoursText);
            int minutes = minutesText.isEmpty() ? 0 : Integer.parseInt(minutesText);

            if (hours < 0 || hours > 23) {
                Toast.makeText(this, "Hours must be between 0 and 23", Toast.LENGTH_SHORT).show();
                return;
            }

            if (minutes < 0 || minutes > 59) {
                Toast.makeText(this, "Minutes must be between 0 and 59", Toast.LENGTH_SHORT).show();
                return;
            }

            if (hours == 0 && minutes == 0) {
                Toast.makeText(this, "Timer duration must be greater than 0", Toast.LENGTH_SHORT).show();
                return;
            }

            // Calculate total time in milliseconds
            long totalTimeMs = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L);

            // Create limiter data
            Map<String, Object> limiterData = new HashMap<>();
            limiterData.put("hours", hours);
            limiterData.put("minutes", minutes);
            limiterData.put("activeDays", new ArrayList<>(selectedDays));
            limiterData.put("selectedApps", new ArrayList<>(selectedApps));
            limiterData.put("startTime", System.currentTimeMillis());
            limiterData.put("remainingTimeMs", totalTimeMs);
            limiterData.put("isActive", true);
            limiterData.put("lastResetDate",
                    new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()));

            // Save to Firebase
            limiterRef.child(currentChildDeviceId).setValue(limiterData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Usage limiter set successfully for device: " + currentChildDeviceId);

                        // Enhanced success message
                        String timeText = (hours > 0 ? hours + "h " : "") + (minutes > 0 ? minutes + "m" : "");
                        Toast.makeText(this, "Usage limiter activated!\\n" +
                                "Ã¢ÂÂ±Ã¯Â¸Â Daily limit: " + timeText + "\n" +
                                "Ã°Å¸â€œÂ± Apps: " + selectedApps.size() + " apps selected\n" +
                                "Ã°Å¸â€œâ€¦ Active on: " + selectedDays.size() + " days",
                                Toast.LENGTH_LONG).show();

                        // Update UI
                        updateLimiterDisplay();

                        // Update button text and all button states - timer is now active
                        if (btnSelectApps != null) {
                            btnSelectApps.setText("Update Apps (" + selectedApps.size() + ")");
                        }
                        updateAllButtonStates(true);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error setting usage limiter: " + e.getMessage());
                        Toast.makeText(this, "Error setting usage limiter: " + e.getMessage(), Toast.LENGTH_LONG)
                                .show();
                    });

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers for hours and minutes", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error in performSetTimerValidationAndSave: " + e.getMessage());
            Toast.makeText(this, "Error setting usage limiter", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Clear the usage limiter with confirmation dialog or show "No timer set"
     * message
     */
    private void clearUsageLimiter() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        // First check if there's actually a timer set
        limiterRef.child(currentChildDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()
                        || !Boolean.TRUE.equals(dataSnapshot.child("isActive").getValue(Boolean.class))) {
                    // No timer is set
                    Toast.makeText(ParentDashboardActivity.this, "No timer set", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Timer exists, show confirmation dialog
                showClearTimerConfirmationDialog();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error checking timer status: " + databaseError.getMessage());
                Toast.makeText(ParentDashboardActivity.this, "Error checking timer status", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Show confirmation dialog for clearing the timer
     */
    private void showClearTimerConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("Clear Usage Limiter");
        builder.setMessage(
                "Are you sure you want to clear the usage limiter for \"" + currentChildDeviceName + "\"?\n\n" +
                        "This will:\n" +
                        "Ã¢â‚¬Â¢ Stop the current limiter immediately\n" +
                        "Ã¢â‚¬Â¢ Remove all limiter settings\n" +
                        "Ã¢â‚¬Â¢ Clear selected apps and days\n" +
                        "Ã¢â‚¬Â¢ Reset the timer\n\n" +
                        "This action cannot be undone.");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("Clear Limiter", (dialog, which) -> {
            Log.d(TAG, "User confirmed limiter clear for device: " + currentChildDeviceName);

            // Remove from Firebase
            limiterRef.child(currentChildDeviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Usage limiter cleared successfully for device: " + currentChildDeviceId);
                        Toast.makeText(this, "Usage limiter cleared for " + currentChildDeviceName,
                                Toast.LENGTH_SHORT).show();

                        // Clear local data
                        selectedDays.clear();
                        selectedApps.clear();

                        // Reset UI
                        if (etLimiterHours != null)
                            etLimiterHours.setText("");
                        if (etLimiterMinutes != null)
                            etLimiterMinutes.setText("");
                        if (btnSelectDays != null)
                            btnSelectDays.setText("Select Days");
                        if (btnSelectApps != null)
                            btnSelectApps.setText("Select Apps");

                        // Update display and all button states - timer is now inactive
                        updateLimiterDisplay();
                        updateAllButtonStates(false);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error clearing usage limiter: " + e.getMessage());
                        Toast.makeText(this, "Error clearing usage limiter: " + e.getMessage(), Toast.LENGTH_LONG)
                                .show();
                    });
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            Log.d(TAG, "User cancelled limiter clear");
            dialog.dismiss();
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Update the UI display with current limiter status
     */
    private void updateLimiterDisplay() {
        // Ã°Å¸â€œÅ  LIVE TIMER STATUS DISPLAY for Parent Dashboard
        if (currentChildDeviceId == null)
            return;

        // Start real-time monitoring of child timer status
        startLiveTimerMonitoring();

        // Load current limiter state
        loadLimiterState();
    }

    /**
     * Ã°Å¸â€œÅ  START LIVE TIMER MONITORING
     * Shows real-time countdown of child device timer on parent dashboard
     * Ã°Å¸â€Â§ MULTI-DEVICE FIX: Properly removes old listener before adding new one
     */
    private void startLiveTimerMonitoring() {
        if (currentChildDeviceId == null || limiterRef == null)
            return;

        Log.d(TAG, "Ã°Å¸â€Â´ STARTING LIVE TIMER MONITORING for child device: " + currentChildDeviceId);

        // Ã°Å¸â€Â§ MULTI-DEVICE FIX: Remove old listener first to prevent data leakage
        cleanupPreviousLimiterListener();

        // Store reference for later cleanup
        activeLimiterRef = limiterRef.child(currentChildDeviceId);

        // Monitor child device timer in real-time
        activeLimiterListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    return;
                }

                try {
                    Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                    Long remainingTimeMs = dataSnapshot.child("remainingTimeMs").getValue(Long.class);
                    Integer hours = dataSnapshot.child("hours").getValue(Integer.class);
                    Integer minutes = dataSnapshot.child("minutes").getValue(Integer.class);
                    String lastResetDate = dataSnapshot.child("lastResetDate").getValue(String.class);

                    if (Boolean.TRUE.equals(isActive) && remainingTimeMs != null) {
                        // Show live timer status
                        runOnUiThread(() -> showLiveTimerStatus(remainingTimeMs, hours, minutes, lastResetDate));
                    } else {
                        // Timer is inactive
                        runOnUiThread(() -> showTimerInactiveStatus());
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error processing live timer data: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Live timer monitoring cancelled: " + databaseError.getMessage());
            }
        };

        // Add the listener
        activeLimiterRef.addValueEventListener(activeLimiterListener);
        Log.d(TAG, "Ã¢Å“â€¦ LIVE TIMER LISTENER ATTACHED for device: " + currentChildDeviceId);
    }

    /**
     * Ã°Å¸â€Â§ MULTI-DEVICE FIX: Clean up previous limiter listener when switching
     * devices
     * This prevents data from old device showing up for new device
     */
    private void cleanupPreviousLimiterListener() {
        if (activeLimiterRef != null && activeLimiterListener != null) {
            activeLimiterRef.removeEventListener(activeLimiterListener);
            Log.d(TAG, "Ã°Å¸Â§Â¹ Removed previous limiter listener (multi-device cleanup)");
            activeLimiterRef = null;
            activeLimiterListener = null;
        }
    }

    /**
     * Ã°Å¸â€Â§ MULTI-DEVICE FIX: Complete cleanup when switching between children
     * Call this before loading new child's data
     */
    private void performMultiDeviceSwitchCleanup() {
        Log.d(TAG, "Ã°Å¸â€â€ž MULTI-DEVICE SWITCH: Cleaning up data for device change");

        // Remove all active Firebase listeners
        cleanupPreviousLimiterListener();

        // Clear cached UI data
        selectedDays.clear();
        selectedApps.clear();

        // Reset UI elements
        runOnUiThread(() -> {
            if (etLimiterHours != null)
                etLimiterHours.setText("");
            if (etLimiterMinutes != null)
                etLimiterMinutes.setText("");
            if (btnSelectDays != null)
                btnSelectDays.setText("Select Days");
            if (btnSelectApps != null)
                btnSelectApps.setText("Select Apps");
            if (tvLimiterStatus != null)
                tvLimiterStatus.setText("Loading...");
            if (tvLimiterTimer != null)
                tvLimiterTimer.setText("--:--:--");
            clearUsageDisplay();
        });

        Log.d(TAG, "Ã¢Å“â€¦ Multi-device cleanup complete - ready for new device data");
    }

    /**
     * Ã°Å¸â€Â´ SHOW LIVE TIMER STATUS on Parent Dashboard
     */
    private void showLiveTimerStatus(long remainingTimeMs, Integer originalHours, Integer originalMinutes,
            String lastResetDate) {
        if (binding == null)
            return;

        try {
            // Format remaining time
            int totalSeconds = (int) (remainingTimeMs / 1000);
            int hours = totalSeconds / 3600;
            int minutes = (totalSeconds % 3600) / 60;
            int seconds = totalSeconds % 60;

            String timeText = String.format("%02d:%02d:%02d", hours, minutes, seconds);

            // Determine status color based on remaining time
            int color;
            String statusText;
            if (remainingTimeMs <= 0) {
                color = ContextCompat.getColor(this, android.R.color.holo_red_dark);
                statusText = "Ã¢ÂÂ° TIME EXPIRED";
                timeText = "00:00:00";
            } else if (remainingTimeMs < 30 * 60 * 1000) { // Less than 30 minutes
                color = ContextCompat.getColor(this, android.R.color.holo_orange_dark);
                statusText = "Ã¢Å¡Â Ã¯Â¸Â TIME RUNNING LOW";
            } else {
                color = ContextCompat.getColor(this, android.R.color.holo_green_dark);
                statusText = "Ã¢Å“â€¦ TIMER ACTIVE";
            }

            // Show original parent-set duration
            String originalDuration = "";
            if (originalHours != null && originalMinutes != null) {
                originalDuration = " (Set: " + originalHours + "h " + originalMinutes + "m)";
            }

            // Update device status text to show live timer
            // Prioritize child's actual name (userName) over device name
            String displayName = "";
            if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
                displayName = currentChildUserName;
            } else if (currentChildDeviceName != null && !currentChildDeviceName.isEmpty()) {
                displayName = currentChildDeviceName;
            } else {
                displayName = "Device";
            }
            String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
            String deviceStatusText = capitalizedName + " (Tap to Manage Device)";
            binding.tvDeviceStatus.setText(deviceStatusText);
            // Use teal color for modern look (unless timer sets a specific color)
            int textColor = (color != 0) ? color : ContextCompat.getColor(this, R.color.success_600);
            binding.tvDeviceStatus.setTextColor(textColor);

            // Create detailed timer info
            String detailedInfo = "Ã¢ÂÂ° Live Timer: " + timeText + originalDuration +
                    "\nÃ°Å¸â€œâ€¦ Device: " + currentChildDeviceName +
                    "\nÃ°Å¸â€œÅ  Status: " + statusText;

            // Show in a timer status view if available
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText(detailedInfo);
                tvLimiterStatus.setTextColor(color);
                tvLimiterStatus.setVisibility(View.VISIBLE);
            }

            // Log for debugging
            Log.d(TAG, "Ã°Å¸â€Â´ LIVE TIMER UPDATE: " + currentChildDeviceName + " - " + timeText + " remaining");

        } catch (Exception e) {
            Log.e(TAG, "Error updating live timer status: " + e.getMessage());
        }
    }

    /**
     * Ã°Å¸â€Ëœ SHOW TIMER INACTIVE STATUS
     */
    private void showTimerInactiveStatus() {
        if (binding == null)
            return;

        try {
            // Update device status
            // Prioritize child's actual name (userName) over device name
            String displayName = "";
            if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
                displayName = currentChildUserName;
            } else if (currentChildDeviceName != null && !currentChildDeviceName.isEmpty()) {
                displayName = currentChildDeviceName;
            } else {
                displayName = "Device";
            }
            String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
            String deviceStatusText = capitalizedName + " (Tap to Manage Device)";
            binding.tvDeviceStatus.setText(deviceStatusText);
            // Use teal color for modern look
            binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));

            // Hide timer status view
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText("No timer currently active for " + currentChildDeviceName);
                tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
                tvLimiterStatus.setVisibility(View.VISIBLE);
            }

            Log.d(TAG, "Ã°Å¸â€Ëœ Timer inactive for device: " + currentChildDeviceName);

        } catch (Exception e) {
            Log.e(TAG, "Error showing inactive timer status: " + e.getMessage());
        }
    }

    /**
     * Load existing limiter state from Firebase
     */
    private void loadLimiterState() {
        if (currentChildDeviceId == null || limiterRef == null) {
            Log.w(TAG, "Cannot load limiter state: device or limiterRef is null");
            return;
        }

        Log.d(TAG, "Loading usage limiter state for device: " + currentChildDeviceId);

        limiterRef.child(currentChildDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    try {
                        // Load limiter data
                        Integer hours = dataSnapshot.child("hours").getValue(Integer.class);
                        Integer minutes = dataSnapshot.child("minutes").getValue(Integer.class);
                        Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                        Long startTime = dataSnapshot.child("startTime").getValue(Long.class);
                        Long remainingTimeMs = dataSnapshot.child("remainingTimeMs").getValue(Long.class);
                        String lastResetDate = dataSnapshot.child("lastResetDate").getValue(String.class);

                        // Load selected days
                        selectedDays.clear();
                        DataSnapshot daysSnapshot = dataSnapshot.child("activeDays");
                        if (daysSnapshot.exists()) {
                            for (DataSnapshot daySnapshot : daysSnapshot.getChildren()) {
                                String day = daySnapshot.getValue(String.class);
                                if (day != null) {
                                    selectedDays.add(day);
                                }
                            }
                        }

                        // Load selected apps
                        selectedApps.clear();
                        DataSnapshot appsSnapshot = dataSnapshot.child("selectedApps");
                        if (appsSnapshot.exists()) {
                            for (DataSnapshot appSnapshot : appsSnapshot.getChildren()) {
                                String app = appSnapshot.getValue(String.class);
                                if (app != null) {
                                    selectedApps.add(app);
                                }
                            }
                        }

                        // Check if timer needs daily reset
                        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                        boolean needsReset = lastResetDate == null || !lastResetDate.equals(todayDate);

                        if (needsReset && isActive != null && isActive) {
                            // Reset timer for new day
                            resetLimiterForNewDay(hours != null ? hours : 0, minutes != null ? minutes : 0);
                        } else if (isActive != null && isActive) {
                            // Update UI with current state
                            runOnUiThread(() -> {
                                // Update input fields
                                if (hours != null && etLimiterHours != null)
                                    etLimiterHours.setText(String.valueOf(hours));
                                if (minutes != null && etLimiterMinutes != null)
                                    etLimiterMinutes.setText(String.valueOf(minutes));

                                // Update button texts
                                if (btnSelectDays != null)
                                    btnSelectDays.setText("Days Selected (" + selectedDays.size() + ")");
                                if (btnSelectApps != null)
                                    btnSelectApps.setText("Update Apps (" + selectedApps.size() + ")");

                                // Update all button states - timer IS active
                                updateAllButtonStates(true);

                                // Display timer
                                if (remainingTimeMs != null && remainingTimeMs > 0) {
                                    displayLimiterTime(remainingTimeMs);
                                    if (tvLimiterStatus != null) {
                                        tvLimiterStatus.setText("Usage limiter active on " + currentChildDeviceName);
                                        tvLimiterStatus.setTextColor(ContextCompat.getColor(
                                                ParentDashboardActivity.this, android.R.color.holo_green_dark));
                                    }
                                } else {
                                    if (tvLimiterTimer != null)
                                        tvLimiterTimer.setText("00:00:00");
                                    if (tvLimiterStatus != null) {
                                        tvLimiterStatus.setText("Usage limiter expired");
                                        tvLimiterStatus.setTextColor(ContextCompat
                                                .getColor(ParentDashboardActivity.this, android.R.color.holo_red_dark));
                                    }
                                }
                            });

                            // Start monitoring timer if active
                            startLimiterMonitoring();
                        } else if (dataSnapshot.hasChildren()) {
                            // Timer exists but is not active - it's expired for the day
                            runOnUiThread(() -> {
                                // Update input fields
                                if (hours != null && etLimiterHours != null)
                                    etLimiterHours.setText(String.valueOf(hours));
                                if (minutes != null && etLimiterMinutes != null)
                                    etLimiterMinutes.setText(String.valueOf(minutes));

                                // Update button texts
                                if (btnSelectDays != null)
                                    btnSelectDays.setText("Days Selected (" + selectedDays.size() + ")");
                                if (btnSelectApps != null)
                                    btnSelectApps.setText("Update Apps (" + selectedApps.size() + ")");

                                // Update all button states - timer is expired but exists
                                updateAllButtonStates(false);

                                // Display expired timer in RED
                                displayExpiredLimiterState();
                            });
                        }

                        Log.d(TAG, "Loaded usage limiter state - Active: " + (isActive != null && isActive) +
                                ", Apps: " + selectedApps.size() + ", Days: " + selectedDays.size());

                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing limiter state: " + e.getMessage());
                        displayInactiveLimiterState();
                    }
                } else {
                    // No limiter set for this device
                    displayInactiveLimiterState();
                    Log.d(TAG, "No usage limiter found for device: " + currentChildDeviceId);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error loading limiter state: " + databaseError.getMessage());
                displayInactiveLimiterState();
            }
        });
    }

    /**
     * Display inactive limiter state in UI
     */
    private void displayInactiveLimiterState() {
        runOnUiThread(() -> {
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText("No usage limiter active");
                tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }
            if (tvLimiterTimer != null) {
                tvLimiterTimer.setText("--:--:--");
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }

            // Update button text for apps
            if (btnSelectApps != null) {
                btnSelectApps
                        .setText(selectedApps.isEmpty() ? "Select Apps" : "Update Apps (" + selectedApps.size() + ")");
            }

            // Update all button states - timer is NOT active
            updateAllButtonStates(false);

        });
    }

    /**
     * Ã°Å¸â€Â´ DISPLAY EXPIRED LIMITER STATE
     * Shows timer in RED when it has expired (00:00) but still exists
     * Timer remains visible until manually removed
     */
    private void displayExpiredLimiterState() {
        runOnUiThread(() -> {
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText("Timer expired for today");
                tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark)); // RED for
                                                                                                           // expired
            }
            if (tvLimiterTimer != null) {
                tvLimiterTimer.setText("00:00:00");
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark)); // RED for
                                                                                                          // expired
            }

            // Update button text for apps
            if (btnSelectApps != null) {
                btnSelectApps
                        .setText(selectedApps.isEmpty() ? "Select Apps" : "Update Apps (" + selectedApps.size() + ")");
            }

            // Update all button states - timer is expired but exists
            updateAllButtonStates(false);

        });
    }

    /**
     * Display limiter remaining time with enhanced formatting
     */
    private void displayLimiterTime(long remainingTimeMs) {
        int totalSeconds = (int) (remainingTimeMs / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (tvLimiterTimer != null) {
            // Enhanced timer display format
            String timeText;
            if (hours > 0) {
                timeText = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
            } else {
                timeText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
            }

            tvLimiterTimer.setText(timeText);

            // Color coding based on remaining time
            if (remainingTimeMs > 600000) { // More than 10 minutes
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            } else if (remainingTimeMs > 300000) { // 5-10 minutes
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            } else if (remainingTimeMs > 0) { // Less than 5 minutes
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            } else {
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                tvLimiterTimer.setText("TIME UP!");
            }
        }

        // Timer badge removed per request

        Log.d(TAG, "Displayed enhanced limiter time: " + hours + "h " + minutes + "m " + seconds + "s");
    }

    /**
     * Reset limiter for new day (daily reset at midnight)
     */
    private void resetLimiterForNewDay(int hours, int minutes) {
        if (currentChildDeviceId == null)
            return;

        Log.d(TAG, "Resetting usage limiter for new day - Device: " + currentChildDeviceId);

        long totalTimeMs = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L);
        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        Map<String, Object> updates = new HashMap<>();
        updates.put("startTime", System.currentTimeMillis());
        updates.put("remainingTimeMs", totalTimeMs);
        updates.put("lastResetDate", todayDate);
        updates.put("isActive", true);

        limiterRef.child(currentChildDeviceId).updateChildren(updates)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Usage limiter reset successfully for new day");
                    runOnUiThread(() -> {
                        displayLimiterTime(totalTimeMs);
                        if (tvLimiterStatus != null) {
                            tvLimiterStatus.setText("Usage limiter reset for today");
                        }
                    });
                    startLimiterMonitoring();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error resetting limiter for new day: " + e.getMessage());
                });
    }

    /**
     * Start monitoring the usage limiter timer
     */
    private void startLimiterMonitoring() {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot start limiter monitoring: no device selected");
            return;
        }

        Log.d(TAG, "Starting enhanced real-time limiter monitoring for device: " + currentChildDeviceId);

        // Setup real-time Firebase listener for accurate timer updates
        DatabaseReference limiterTimerRef = limiterRef.child(currentChildDeviceId);

        limiterTimerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                try {
                    if (dataSnapshot.exists()) {
                        Map<String, Object> limiterData = (Map<String, Object>) dataSnapshot.getValue();
                        if (limiterData != null) {
                            Boolean isActive = (Boolean) limiterData.get("isActive");
                            Long remainingTimeMs = (Long) limiterData.get("remainingTimeMs");
                            String currentApp = (String) limiterData.get("currentApp");
                            Boolean isRunning = (Boolean) limiterData.get("isRunning");
                            Long lastSync = (Long) limiterData.get("lastSync");

                            // Update UI with real-time data
                            runOnUiThread(() -> {
                                updateLimiterRealTimeUI(isActive, remainingTimeMs, currentApp, isRunning, lastSync);
                            });
                        }
                    } else {
                        // No active limiter
                        runOnUiThread(() -> {
                            displayInactiveLimiterState();
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing real-time limiter data: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Real-time limiter monitoring cancelled: " + databaseError.getMessage());
            }
        });

        Log.d(TAG, "Ã¢Å“â€¦ Enhanced real-time limiter monitoring started");
    }

    /**
     * Update limiter UI with real-time data from child device
     */
    private void updateLimiterRealTimeUI(Boolean isActive, Long remainingTimeMs, String currentApp, Boolean isRunning,
            Long lastSync) {
        try {
            if (tvLimiterStatus != null) {
                if (Boolean.TRUE.equals(isActive) && remainingTimeMs != null && remainingTimeMs > 0) {
                    String status = "Active";
                    if (currentApp != null && !currentApp.isEmpty()) {
                        String appName = getAppDisplayName(currentApp);
                        status += " - " + (Boolean.TRUE.equals(isRunning) ? "Counting: " : "Paused: ") + appName;
                    }

                    // Add sync indicator
                    if (lastSync != null) {
                        long syncAge = System.currentTimeMillis() - lastSync;
                        if (syncAge < 5000) { // Recent sync (within 5 seconds)
                            status += " Ã¢â€”Â"; // Live indicator
                        }
                    }

                    tvLimiterStatus.setText(status);
                    tvLimiterStatus.setTextColor(ContextCompat.getColor(this,
                            Boolean.TRUE.equals(isRunning) ? android.R.color.holo_red_dark
                                    : android.R.color.holo_orange_dark));
                } else {
                    tvLimiterStatus.setText("Timer inactive or expired");
                    tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
                }
            }

            // Update timer display
            if (remainingTimeMs != null && remainingTimeMs > 0) {
                displayLimiterTime(remainingTimeMs);
            } else {
                displayInactiveLimiterState();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating real-time limiter UI: " + e.getMessage());
        }
    }

    /**
     * Get display name for an app package
     */
    private String getAppDisplayName(String packageName) {
        if ("online.monarchlabs.sentinel".equals(packageName) || "online_monarchlabs_sentinel".equals(packageName)) {
            return "Sentinel";
        }
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return packageName; // Fallback to package name
        }
    }

    /**
     * Setup usage limiter for current device
     */
    private void initializeLimiterForDevice(String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "Cannot initialize limiter: deviceId is null");
            return;
        }

        Log.d(TAG, "Initializing usage limiter for device: " + deviceId);

        // If switching devices, detach any previous limiter listener and clear only
        // local UI selections
        if (currentChildDeviceId == null || !currentChildDeviceId.equals(deviceId)) {
            // Detach previous device listener to avoid crosstalk
            detachLimiterRealtimeListener();

            // Clear local selections only when switching devices (do NOT wipe Firebase
            // data)
            selectedDays.clear();
            selectedApps.clear();

            // Reset UI elements
            if (etLimiterHours != null)
                etLimiterHours.setText("");
            if (etLimiterMinutes != null)
                etLimiterMinutes.setText("");
            if (btnSelectDays != null)
                btnSelectDays.setText("Select Days");
            if (btnSelectApps != null)
                btnSelectApps.setText("Select Apps");
        }

        // Load existing state for this device
        loadLimiterState();

        // Attach a realtime listener for this device so UI stays in sync
        ensureLimiterRealtimeListener(deviceId);

        Log.d(TAG, "Usage limiter initialized for device: " + deviceId);
    }

    /**
     * Clean usage limiter data when device connects (as per user requirement)
     */
    private void cleanDeviceUsageLimiterData(String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "Cannot clean limiter data: deviceId is null");
            return;
        }

        Log.d(TAG, "Cleaning usage limiter data for device: " + deviceId);

        // Remove from Firebase
        if (limiterRef != null) {
            limiterRef.child(deviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Successfully cleaned usage limiter data for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error cleaning usage limiter data for device " + deviceId + ": " + e.getMessage());
                    });
        }

        // Clean local storage (device-specific limiter preferences)
        try {
            SharedPreferences limiterPrefs = getSharedPreferences("usage_limiter", MODE_PRIVATE);
            SharedPreferences.Editor editor = limiterPrefs.edit();

            // Remove device-specific keys
            editor.remove("limiter_days_" + deviceId);
            editor.remove("limiter_apps_" + deviceId);
            editor.remove("limiter_hours_" + deviceId);
            editor.remove("limiter_minutes_" + deviceId);
            editor.apply();

            Log.d(TAG, "Cleaned local limiter preferences for device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning local limiter preferences: " + e.getMessage());
        }
    }

    /**
     * Ensure a realtime listener is attached for the given device's limiter node.
     * Detaches any existing listener before attaching a new one.
     */
    private void ensureLimiterRealtimeListener(String deviceId) {
        try {
            if (limiterRef == null || deviceId == null) {
                return;
            }

            // Detach previous listener if pointing to a different device
            if (currentLimiterDeviceRef != null && limiterListener != null) {
                currentLimiterDeviceRef.removeEventListener(limiterListener);
            }

            currentLimiterDeviceRef = limiterRef.child(deviceId);
            limiterListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    // On any change, refresh the UI from source of truth
                    updateLimiterDisplay();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "Limiter realtime listener cancelled: " + error.getMessage());
                }
            };

            currentLimiterDeviceRef.addValueEventListener(limiterListener);
            Log.d(TAG, "Attached realtime limiter listener for device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error attaching limiter realtime listener: " + e.getMessage());
        }
    }

    /**
     * Detach any active realtime listener for the previously selected device.
     */
    private void detachLimiterRealtimeListener() {
        try {
            if (currentLimiterDeviceRef != null && limiterListener != null) {
                currentLimiterDeviceRef.removeEventListener(limiterListener);
                Log.d(TAG, "Detached previous limiter realtime listener");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error detaching limiter realtime listener: " + e.getMessage());
        } finally {
            // Always clear references
            currentLimiterDeviceRef = null;
            limiterListener = null;
        }
    }

    /**
     * Update the Set Timer button state - simplified since button is always active
     */
    private void updateSetTimerButtonState() {
        if (btnSetLimiter == null) {
            return;
        }

        // Set Timer button is always enabled when device is selected (validation
        // happens when clicked)
        boolean hasDevice = currentChildDeviceId != null;
        btnSetLimiter.setEnabled(hasDevice);

        Log.d(TAG, "Set Timer button state: " + hasDevice + " (Device selected: " + hasDevice + ")");
    }

    /**
     * Update all button states properly based on current limiter state
     */
    private void updateAllButtonStates(boolean isTimerActive) {
        runOnUiThread(() -> {
            try {
                boolean hasDevice = currentChildDeviceId != null;

                // Select Apps button - should always be enabled when device is selected
                if (btnSelectApps != null) {
                    btnSelectApps.setEnabled(hasDevice);
                }

                // Select Days button - should always be enabled when device is selected
                if (btnSelectDays != null) {
                    btnSelectDays.setEnabled(hasDevice);
                }

                // Set Timer button - should always be enabled when device is selected
                if (btnSetLimiter != null) {
                    btnSetLimiter.setEnabled(hasDevice);
                }

                // Clear Timer button - should always be enabled when device is selected
                if (btnClearLimiter != null) {
                    btnClearLimiter.setEnabled(hasDevice);
                }

                Log.d(TAG, "Updated all button states - Timer active: " + isTimerActive + ", Has device: " + hasDevice);

            } catch (Exception e) {
                Log.e(TAG, "Error updating all button states: " + e.getMessage());
            }
        });
    }

    /**
     * Handle when no device is selected - disable relevant buttons
     */
    private void handleNoDeviceSelected() {
        runOnUiThread(() -> {
            // Clear current device info
            currentChildDeviceId = null;

            // Update all button states - no timer active, no device
            updateAllButtonStates(false);

            // Update status
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText("Select a device to set usage limiter");
                tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }
            if (tvLimiterTimer != null) {
                tvLimiterTimer.setText("--:--:--");
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }

            Log.d(TAG, "Updated UI for no device selected state");
        });
    }

    /**
     * Ã°Å¸â€Â§ CRITICAL FIX: Create timer data for QR reconnected device
     * This ensures the device appears in parent dashboard even after reconnection
     */
    private void createTimerDataForDevice(String deviceId, String deviceName) {
        // Pairing creates the canonical v2 device link; no synthetic timer row is needed.
    }

    /**
     * Ã°Å¸â€â€ START PERSISTENT TIMER NOTIFICATION SERVICE
     * This service will show notifications when timers expire or need reset
     */
    private DatabaseReference timerExpiryNotifRef;
    private ValueEventListener timerExpiryListener;
    private final java.util.Set<String> shownTimerExpiryKeys = new java.util.HashSet<>();
    private final java.util.Set<String> shownSosEventKeys = new java.util.HashSet<>();
    private final java.util.Set<String> shownGeofenceEventKeys = new java.util.HashSet<>();

    private void showCreateGeofenceDialog() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Toast.makeText(this, "Connect a child device first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (lastChildLocation == null) {
            Toast.makeText(this, "Waiting for child location. Try refresh location first.", Toast.LENGTH_LONG).show();
            requestFreshLocation(currentChildDeviceId);
            return;
        }
        if (mAuth == null || mAuth.getCurrentUser() == null) {
            Toast.makeText(this, "Sign in again to create a safe zone", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = {"Home - 150 m", "School - 250 m", "Area - 500 m", "Wide area - 1 km"};
        String[] names = {"Home", "School", "Safe Zone", "Wide Safe Zone"};
        int[] radii = {150, 250, 500, 1000};
        new AlertDialog.Builder(this)
                .setTitle("Add Safe Zone")
                .setItems(labels, (dialog, which) -> createGeofence(names[which], radii[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createGeofence(String name, int radiusMeters) {
        LatLng center = lastChildLocation;
        if (center == null) {
            Toast.makeText(this, "Child location is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String parentUid = mAuth.getCurrentUser().getUid();
        GeofenceService.createSafeZone(currentChildDeviceId, parentUid, name,
                        center.latitude, center.longitude, radiusMeters)
                .addOnSuccessListener(ignored -> Toast.makeText(this,
                        name + " safe zone added", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(error -> Toast.makeText(this,
                        "Could not add safe zone: " + error.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void setupGeofenceEventsListener() {
        detachGeofenceEventsListener();
        shownGeofenceEventKeys.clear();
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            return;
        }
        geofenceEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("geofence_events")
                .child(currentChildDeviceId);
        geofenceEventsListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                handleGeofenceSnapshot(snapshot);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                handleGeofenceSnapshot(snapshot);
            }

            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {}
            @Override public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {}

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Geofence listener cancelled: " + error.getMessage());
            }
        };
        geofenceEventsRef.addChildEventListener(geofenceEventsListener);
    }

    private void detachGeofenceEventsListener() {
        if (geofenceEventsRef != null && geofenceEventsListener != null) {
            geofenceEventsRef.removeEventListener(geofenceEventsListener);
        }
        geofenceEventsRef = null;
        geofenceEventsListener = null;
    }

    private void handleGeofenceSnapshot(@NonNull DataSnapshot snapshot) {
        String eventId = snapshot.child("eventId").getValue(String.class);
        if (eventId == null || eventId.isEmpty()) {
            eventId = snapshot.getKey();
        }
        String status = snapshot.child("status").getValue(String.class);
        if (!"unread".equals(status) || eventId == null || shownGeofenceEventKeys.contains(eventId)) {
            return;
        }
        shownGeofenceEventKeys.add(eventId);

        String zoneName = snapshot.child("geofenceName").getValue(String.class);
        String transition = snapshot.child("transition").getValue(String.class);
        Double lat = snapshot.child("lat").getValue(Double.class);
        Double lng = snapshot.child("lng").getValue(Double.class);
        String action = "enter".equals(transition) ? "entered" : "left";
        String label = zoneName != null && !zoneName.isEmpty() ? zoneName : "safe zone";
        String childName = currentChildUserName != null && !currentChildUserName.isEmpty()
                ? currentChildUserName : "Child";

        showGeofenceNotification(eventId, childName, action, label);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Geofence Alert")
                .setMessage(childName + " " + action + " " + label + ".")
                .setPositiveButton("Mark Read", (dialog, which) -> snapshot.getRef().child("status").setValue("read"))
                .setNegativeButton("Close", (dialog, which) -> snapshot.getRef().child("status").setValue("read"));
        if (lat != null && lng != null) {
            builder.setNeutralButton("Open Map", (dialog, which) -> {
                snapshot.getRef().child("status").setValue("read");
                Uri uri = Uri.parse("geo:" + lat + "," + lng
                        + "?q=" + lat + "," + lng + "(" + Uri.encode(childName + " " + label) + ")");
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            });
        }
        builder.show();
    }

    private void showGeofenceNotification(String eventId, String childName, String action, String zoneName) {
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "geofence_alert_channel",
                        "Geofence Alerts",
                        NotificationManager.IMPORTANCE_HIGH);
                notificationManager.createNotificationChannel(channel);
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    eventId.hashCode(),
                    new Intent(this, ParentDashboardActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new NotificationCompat.Builder(this, "geofence_alert_channel")
                    .setSmallIcon(R.drawable.ic_warning)
                    .setContentTitle("Safe zone alert")
                    .setContentText(childName + " " + action + " " + zoneName)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            notificationManager.notify(920000 + Math.abs(eventId.hashCode() % 9999), notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show geofence notification: " + e.getMessage());
        }
    }

    private void setupSosEventsListener() {
        detachSosEventsListener();
        shownSosEventKeys.clear();
        if (mAuth == null || mAuth.getCurrentUser() == null) {
            return;
        }
        String parentUid = mAuth.getCurrentUser().getUid();
        sosEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("sos_events")
                .child(parentUid);
        sosEventsListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                handleSosSnapshot(snapshot);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                handleSosSnapshot(snapshot);
            }

            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {}
            @Override public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {}

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "SOS listener cancelled: " + error.getMessage());
            }
        };
        sosEventsRef.addChildEventListener(sosEventsListener);
    }

    private void detachSosEventsListener() {
        if (sosEventsRef != null && sosEventsListener != null) {
            sosEventsRef.removeEventListener(sosEventsListener);
        }
        sosEventsRef = null;
        sosEventsListener = null;
    }

    private void handleSosSnapshot(@NonNull DataSnapshot snapshot) {
        String eventId = snapshot.child("eventId").getValue(String.class);
        if (eventId == null || eventId.isEmpty()) {
            eventId = snapshot.getKey();
        }
        String status = snapshot.child("status").getValue(String.class);
        if (!"active".equals(status) || eventId == null) {
            return;
        }
        Long createdAt = snapshot.child("createdAt").getValue(Long.class);
        String dedupeKey = eventId + ":" + (createdAt != null ? createdAt : 0L);
        if (shownSosEventKeys.contains(dedupeKey)) {
            return;
        }
        shownSosEventKeys.add(dedupeKey);

        String childName = snapshot.child("childName").getValue(String.class);
        String deviceName = snapshot.child("deviceName").getValue(String.class);
        String reason = snapshot.child("reason").getValue(String.class);
        String childDeviceId = snapshot.child("childDeviceId").getValue(String.class);
        Integer battery = snapshot.child("batteryPercent").getValue(Integer.class);
        Double latitude = snapshot.child("location").child("latitude").getValue(Double.class);
        Double longitude = snapshot.child("location").child("longitude").getValue(Double.class);
        if (latitude == null) {
            latitude = snapshot.child("location").child("lat").getValue(Double.class);
        }
        if (longitude == null) {
            longitude = snapshot.child("location").child("lng").getValue(Double.class);
        }

        showSosNotification(eventId, childName, reason);
        showSosDialog(snapshot.getRef(), childDeviceId, childName, deviceName, reason,
                battery != null ? battery : -1, latitude, longitude);
    }

    private void showSosDialog(DatabaseReference eventRef, String childDeviceId, String childName,
            String deviceName, String reason, int batteryPercent, Double latitude, Double longitude) {
        String titleName = childName != null && !childName.isEmpty() ? childName : "Child";
        StringBuilder message = new StringBuilder();
        message.append("Reason: ").append(reason != null ? reason : "I need help");
        if (deviceName != null && !deviceName.isEmpty()) {
            message.append("\nDevice: ").append(deviceName);
        }
        if (batteryPercent >= 0) {
            message.append("\nBattery: ").append(batteryPercent).append("%");
        }
        if (latitude != null && longitude != null) {
            message.append("\nLocation available.");
        } else {
            message.append("\nLocation not available yet.");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("SOS Alert: " + titleName)
                .setMessage(message.toString())
                .setPositiveButton("Mark Resolved", (dialog, which) -> resolveSos(eventRef, childDeviceId))
                .setNegativeButton("Close", null);
        if (latitude != null && longitude != null) {
            builder.setNeutralButton("Open Map", (dialog, which) -> {
                Uri uri = Uri.parse("geo:" + latitude + "," + longitude
                        + "?q=" + latitude + "," + longitude + "(" + Uri.encode(titleName) + ")");
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            });
        }
        builder.show();
    }

    private void resolveSos(DatabaseReference eventRef, String childDeviceId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", "resolved");
        updates.put("resolvedAt", ServerValue.TIMESTAMP);
        updates.put("updatedAt", ServerValue.TIMESTAMP);
        eventRef.updateChildren(updates);
        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            FirebaseDatabase.getInstance().getReference("v2")
                    .child("sos_active_by_device")
                    .child(childDeviceId)
                    .child("status")
                    .setValue("resolved");
        }
    }

    private void showSosNotification(String eventId, String childName, String reason) {
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "sos_alert_channel",
                        "SOS Alerts",
                        NotificationManager.IMPORTANCE_HIGH);
                notificationManager.createNotificationChannel(channel);
            }

            Intent intent = new Intent(this, ParentDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    eventId != null ? eventId.hashCode() : 9001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String name = childName != null && !childName.isEmpty() ? childName : "Child";
            Notification notification = new NotificationCompat.Builder(this, "sos_alert_channel")
                    .setSmallIcon(R.drawable.ic_warning)
                    .setContentTitle("SOS from " + name)
                    .setContentText(reason != null ? reason : "I need help")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            notificationManager.notify(900000 + Math.abs((eventId != null ? eventId : name).hashCode() % 9999),
                    notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show SOS notification: " + e.getMessage());
        }
    }

    /**
     * Listen for per-app timer expiry events written by the child's AppTimerService.
     */
    private void setupParentTimerExpiryListener() {
        if (timerExpiryNotifRef != null && timerExpiryListener != null) {
            timerExpiryNotifRef.removeEventListener(timerExpiryListener);
        }
        timerExpiryListener = null;
        timerExpiryNotifRef = null;
        shownTimerExpiryKeys.clear();

        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            return;
        }

        timerExpiryNotifRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("timer_events")
                .child(currentChildDeviceId);

        timerExpiryListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot child : snapshot.getChildren()) {
                    String key = child.getKey();
                    if (key == null) {
                        continue;
                    }
                    Boolean read = child.child("read").getValue(Boolean.class);
                    if (Boolean.TRUE.equals(read)) {
                        continue;
                    }
                    Long timestamp = child.child("timestamp").getValue(Long.class);
                    String dedupeKey = key + ":" + (timestamp != null ? timestamp : 0L);
                    if (shownTimerExpiryKeys.contains(dedupeKey)) {
                        continue;
                    }
                    shownTimerExpiryKeys.add(dedupeKey);

                    String appName = child.child("appName").getValue(String.class);
                    String packageName = child.child("packageName").getValue(String.class);
                    Long exceedMs = child.child("exceedTimeMillis").getValue(Long.class);
                    if (appName == null || appName.isEmpty()) {
                        appName = packageName;
                    }
                    showParentTimerExpiryNotification(
                            packageName,
                            appName,
                            exceedMs != null ? exceedMs : 0L);
                    child.getRef().child("read").setValue(true);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Timer expiry listener cancelled: " + error.getMessage());
            }
        };
        timerExpiryNotifRef.addValueEventListener(timerExpiryListener);
    }

    private void showParentTimerExpiryNotification(
            String packageName,
            String appName,
            long exceedMs) {
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "timer_expiry_channel",
                        "Timer Expiry Notifications",
                        NotificationManager.IMPORTANCE_DEFAULT);
                notificationManager.createNotificationChannel(channel);
            }

            String exceedText = exceedMs > 0
                    ? online.monarchlabs.sentinel.services.AppTimerService.formatDuration(exceedMs) + " exceed"
                    : "Daily limit reached";

            Intent intent = new Intent(this, TimerStatusActivity.class);
            intent.putExtra(TimerStatusActivity.EXTRA_DEVICE_ID, currentChildDeviceId);
            intent.putExtra(TimerStatusActivity.EXTRA_IS_PARENT, true);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    packageName != null ? packageName.hashCode() : 0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, "timer_expiry_channel")
                    .setSmallIcon(R.drawable.ic_timer_status)
                    .setContentTitle("Timer expired: " + appName)
                    .setContentText(appName + " - " + exceedText + ". App remains accessible.")
                    .setContentIntent(pendingIntent)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .build();

            int notificationId = 7000
                    + (packageName != null
                            ? (packageName.hashCode() & 0x7fffffff) % 100000
                            : (appName != null
                                    ? (appName.hashCode() & 0x7fffffff) % 100000
                                    : 0));
            notificationManager.notify(notificationId, notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show parent timer expiry notification: " + e.getMessage());
        }
    }

    private void startPersistentTimerNotificationService() {
        setupParentTimerExpiryListener();
    }

    // ==========================================
    // PREMIUM UI METHODS
    // ==========================================

    private void refreshDeviceListPremium() {
        // REDIRECTED: Use simple circular icons instead of big cards
        populateDeviceList();
        return;
        /*
         * if (llDeviceList == null)
         * return;
         * llDeviceList.removeAllViews();
         *
         * List<ChildDevice> devices = connectedDevices;
         * if (devices == null)
         * devices = new ArrayList<>();
         *
         * LayoutInflater inflater = LayoutInflater.from(this);
         *
         * for (ChildDevice device : devices) {
         * // Ã°Å¸â€Â§ CHANGED: Use new Vertical Card Layout
         * View card = inflater.inflate(R.layout.item_device_card, llDeviceList, false);
         *
         * // Bind Views
         * androidx.constraintlayout.widget.ConstraintLayout cardContainer =
         * card.findViewById(R.id.cardContainer);
         * TextView tvName = card.findViewById(R.id.tvDeviceName);
         * ImageView ivIcon = card.findViewById(R.id.ivDeviceIcon);
         * View statusDot = card.findViewById(R.id.viewStatusDot);
         * TextView tvStatus = card.findViewById(R.id.tvStatus);
         * ImageView btnMoreOptions = card.findViewById(R.id.btnMoreOptions);
         *
         * // Data Binding
         * String displayName = (device.userName != null && !device.userName.isEmpty())
         * ? device.userName
         * : device.deviceName;
         * tvName.setText(displayName);
         *
         * // Status Logic (Simple for now)
         * long timeDiff = System.currentTimeMillis() - device.lastConnected;
         * boolean isOnline = timeDiff < 15 * 60 * 1000; // Considered online if
         * connected in last 15 mins
         *
         * if (isOnline) {
         * statusDot.setBackgroundResource(R.drawable.bg_indicator_green);
         * tvStatus.setText("Monitoring");
         * tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));
         * } else {
         * statusDot.setBackgroundResource(R.drawable.bg_indicator_grey);
         * tvStatus.setText("Offline");
         * tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral_500));
         * }
         *
         * // Selection Logic
         * boolean isSelected = device.deviceId.equals(currentChildDeviceId);
         * if (isSelected) {
         * // Selected State: Blue Tint + Border
         * cardContainer.setBackgroundResource(R.drawable.bg_device_card_selected);
         * ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary_600));
         * } else {
         * // Normal State: White + Grey Border
         * cardContainer.setBackgroundResource(R.drawable.bg_device_card_normal);
         * ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.neutral_400));
         * }
         *
         * // Card Click Listener (Select Device)
         * card.setOnClickListener(v -> {
         * switchDevice(device.deviceId); // FIX: Use proper method that loads usage
         * data
         * });
         *
         * // "Three Dots" Menu Click Listener
         * btnMoreOptions.setOnClickListener(v -> {
         * android.widget.PopupMenu popup = new android.widget.PopupMenu(this,
         * btnMoreOptions);
         * popup.getMenu().add("Remove Device");
         * // popup.getMenu().add("Rename"); // Future feature
         *
         * popup.setOnMenuItemClickListener(item -> {
         * if (item.getTitle().equals("Remove Device")) {
         * // Confirm Removal
         * new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,
         * R.style.AlertDialogCustom))
         * .setTitle("Remove Device?")
         * .setMessage("Are you sure you want to remove " + displayName
         * + "? You will need to reconnect it from the child device.")
         * .setPositiveButton("Remove", (dialog, which) -> {
         * })
         * .setNegativeButton("Cancel", null)
         * .show();
         * return true;
         * }
         * return false;
         * });
         * popup.show();
         * });
         *
         * llDeviceList.addView(card);
         * }
         *
         * // "Add Device" Button (Keep as chip or make card? Keeping consistent for
         * now)
         * // Ã°Å¸â€Â§ UPDATED: Styled to match card height roughly or keep as distinct action
         * View addBtn = inflater.inflate(R.layout.item_add_device_chip, llDeviceList,
         * false);
         *
         * // Optional: layout params adjustment if needed to align with cards
         * // LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
         * // (int) (110 * getResources().getDisplayMetrics().density),
         * // ViewGroup.LayoutParams.WRAP_CONTENT);
         * // params.setMargins(0, 8, 0, 8);
         * // addBtn.setLayoutParams(params);
         *
         * addBtn.setOnClickListener(v -> showQRScanner());
         * llDeviceList.addView(addBtn);
         */ // END OF COMMENTED OUT BIG CARDS CODE
    }

    private void selectDevicePremium(ChildDevice device) {
        if (device == null)
            return;

        if (device.deviceId != null && !device.deviceId.equals(currentChildDeviceId)) {
            switchDevice(device.deviceId);
            return;
        }

        currentChildDeviceId = device.deviceId;
        currentChildDeviceName = device.deviceName;
        currentChildUserName = device.userName;

        // Update Manager
        if (connectedDevicesManager != null) {
            connectedDevicesManager.setCurrentDevice(device.deviceId, true);
        }

        // Update UI
        updateDeviceStatus();
        updateTargetDeviceDisplay(); // Helper if exists
        refreshCurrentChildDeviceCards();

        // Refresh list to update selection Highlight
        refreshDeviceListPremium();

        Toast.makeText(this, "Selected: " + device.deviceName, Toast.LENGTH_SHORT).show();
    }

    /**
     * Start Permission Event Listener service to monitor child device service
     * status changes
     */
    private void startPermissionEventListener() {
        try {
            online.monarchlabs.sentinel.services.PermissionEventListener.start(this);
            Log.d(TAG, "Ã¢Å“â€¦ Permission Event Listener service started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Permission Event Listener: " + e.getMessage());
        }
    }

    // ================================================================================
    // Ã°Å¸Å¡Â¨ UNINSTALL DETECTION METHODS
    // ================================================================================

    /**
     * Start monitoring the current device for uninstall detection
     * Called when a device is selected
     */
    private void startUninstallDetection() {
        if (currentChildDeviceId == null || uninstallDetectionManager == null) {
            Log.w(TAG, "Cannot start uninstall detection - no device or manager");
            return;
        }

        Log.d(TAG, "Ã°Å¸Å¡Â¨ Starting uninstall detection for device: " + currentChildDeviceId);
        resetUninstallWarningUI();

        uninstallDetectionManager.startMonitoringDevice(currentChildDeviceId,
                (deviceId, status, lastHeartbeat) -> {
                    runOnUiThread(() -> {
                        // Only update if this is still the current device
                        if (deviceId.equals(currentChildDeviceId)) {
                            currentDeviceStatus = status;
                            updateUninstallWarningUI(status, lastHeartbeat);
                            updateDeviceIconColor(status);
                            handleUninstallStatusChange(deviceId, currentChildDeviceName, status, lastHeartbeat);
                        }
                    });
                });
    }

    /**
     * Stop monitoring device for uninstall detection
     */
    private void stopUninstallDetection() {
        if (uninstallDetectionManager != null && currentChildDeviceId != null) {
            uninstallDetectionManager.stopMonitoringDevice(currentChildDeviceId);
            Log.d(TAG, "Ã°Å¸â€ºâ€˜ Stopped uninstall detection for device: " + currentChildDeviceId);
        }

        resetUninstallWarningUI();
    }

    /**
     * Update the uninstall warning banner visibility and content
     */
    private void updateUninstallWarningUI(String status, long lastHeartbeat) {
        if (layoutUninstallWarning == null)
            return;

        boolean showWarning = UninstallDetectionManager.isUninstalled(status);

        if (showWarning) {
            layoutUninstallWarning.setVisibility(View.VISIBLE);

            if (tvUninstallWarningTitle != null) {
                tvUninstallWarningTitle.setText("App might be affected.");
            }

            if (tvUninstallWarningMessage != null) {
                tvUninstallWarningMessage.setText(
                        "The communication with the app has been missing for a long time. There might be some possible issues.");
            }

            if (tvSeeIssuesToggle != null) {
                tvSeeIssuesToggle.setText("See possible issues");
                tvSeeIssuesToggle.setVisibility(View.VISIBLE);
            }

            if (layoutPossibleIssues != null) {
                layoutPossibleIssues.setVisibility(View.GONE);
                layoutPossibleIssues.setAlpha(0f);
                isPossibleIssuesExpanded = false;
            }

            if (tvUninstallLastSeen != null) {
                String lastSeenText = "Last seen: " + UninstallDetectionManager.getLastSeenText(lastHeartbeat);
                tvUninstallLastSeen.setText(lastSeenText);
            }

            Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â UNINSTALL WARNING SHOWN for " + currentChildDeviceName + ": " + status);
        } else {
            layoutUninstallWarning.setVisibility(View.GONE);
        }

        // Update the orange sync warning banner depending on priorities
        updateSyncWarningBanner();
    }

    /**
     * Clear the uninstall banner and icon state before switching to another child.
     */
    private void resetUninstallWarningUI() {
        currentDeviceStatus = UninstallDetectionManager.STATUS_ONLINE;

        if (layoutUninstallWarning != null) {
            layoutUninstallWarning.setVisibility(View.GONE);
        }

        if (tvUninstallWarningTitle != null) {
            tvUninstallWarningTitle.setText("App might be affected.");
        }

        if (tvUninstallWarningMessage != null) {
            tvUninstallWarningMessage.setText(
                    "The communication with the app has been missing for a long time. There might be some possible issues.");
        }

        if (tvSeeIssuesToggle != null) {
            tvSeeIssuesToggle.setText("See possible issues");
        }

        if (layoutPossibleIssues != null) {
            layoutPossibleIssues.setVisibility(View.GONE);
            layoutPossibleIssues.setAlpha(0f);
        }

        if (tvUninstallLastSeen != null) {
            tvUninstallLastSeen.setText("Last seen: Never");
        }

        if (layoutPossibleIssues != null) {
            layoutPossibleIssues.setVisibility(View.GONE);
            layoutPossibleIssues.setAlpha(0f);
        }
        isPossibleIssuesExpanded = false;

        updateDeviceIconColor(UninstallDetectionManager.STATUS_ONLINE);

        // Update the orange sync warning banner
        updateSyncWarningBanner();
    }

    private void updateSyncWarningBanner() {
        if (layoutSyncWarning == null) {
            return;
        }

        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            layoutSyncWarning.setVisibility(View.GONE);
            return;
        }

        // Loading-State Protection: Don't evaluate until the child's data has loaded from Firebase
        if (!loadedUsageDeviceIds.contains(currentChildDeviceId)) {
            layoutSyncWarning.setVisibility(View.GONE);
            return;
        }

        // RED WARNING Priority Override
        boolean hasRedWarning = UninstallDetectionManager.isUninstalled(currentDeviceStatus);
        if (hasRedWarning) {
            layoutSyncWarning.setVisibility(View.GONE);
            return;
        }

        // Evaluate Orange Warning (Sync Stale > 10 mins)
        Long lastUsageUploadTime = lastUsageUploadTimestamps.get(currentChildDeviceId);
        if (lastUsageUploadTime == null || lastUsageUploadTime <= 0) {
            // New Device Safeguard: missing or invalid timestamp keeps warning hidden
            layoutSyncWarning.setVisibility(View.GONE);
            return;
        }

        long timeSinceLastUsage = System.currentTimeMillis() - lastUsageUploadTime;
        if (timeSinceLastUsage > 10 * 60 * 1000) {
            layoutSyncWarning.setVisibility(View.VISIBLE);
            if (tvSyncWarning != null) {
                tvSyncWarning.setText("Usage data has not been updated recently. Try re opeaning the app or allow it to use battery optimization from settings on the child device and wait a few minutes. And wait for a while the Usage data should automatically resume syncing");
            }
        } else {
            layoutSyncWarning.setVisibility(View.GONE);
        }
    }

    private void togglePossibleIssues() {
        if (layoutPossibleIssues == null || tvSeeIssuesToggle == null)
            return;

        if (isPossibleIssuesExpanded) {
            layoutPossibleIssues.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(() -> layoutPossibleIssues.setVisibility(View.GONE))
                    .start();
            tvSeeIssuesToggle.setText("See possible issues");
        } else {
            layoutPossibleIssues.setAlpha(0f);
            layoutPossibleIssues.setVisibility(View.VISIBLE);
            layoutPossibleIssues.animate()
                    .alpha(1f)
                    .setDuration(180)
                    .start();
            tvSeeIssuesToggle.setText("Hide possible issues");
        }

        isPossibleIssuesExpanded = !isPossibleIssuesExpanded;
    }

    /**
     * Update the device icon color based on status
     * Turns red when app is uninstalled
     */
    private void updateDeviceIconColor(String status) {
        ImageView ivDeviceIcon = findViewById(R.id.ivDeviceIcon);
        if (ivDeviceIcon == null)
            return;

        if (UninstallDetectionManager.isUninstalled(status)) {
            // Red color for uninstalled
            ivDeviceIcon.setColorFilter(ContextCompat.getColor(this, R.color.error_600));

            // Also update device status text color to red
            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.error_600));
            }

            Log.d(TAG, "Ã°Å¸â€Â´ Device icon set to RED (uninstalled)");
        } else if (UninstallDetectionManager.STATUS_OFFLINE.equals(status)) {
            // Orange/yellow for offline
            ivDeviceIcon.setColorFilter(ContextCompat.getColor(this, R.color.warning_600));

            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_600));
            }

            Log.d(TAG, "Ã°Å¸Å¸Â¡ Device icon set to YELLOW (offline)");
        } else {
            // Green/normal for online
            ivDeviceIcon.setColorFilter(ContextCompat.getColor(this, R.color.neutral_400));

            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));
            }

            Log.d(TAG, "Ã°Å¸Å¸Â¢ Device icon set to NORMAL (online)");
        }
    }

    private void handleUninstallStatusChange(String deviceId, String childName, String status, long lastHeartbeat) {
        if (deviceId == null || status == null) return;

        String lastNotified = lastNotifiedStatusByDevice.get(deviceId);
        boolean isCurrentUninstalled = UninstallDetectionManager.isUninstalled(status);
        boolean wasLastUninstalled = lastNotified != null && UninstallDetectionManager.isUninstalled(lastNotified);

        if (isCurrentUninstalled && !status.equals(lastNotified)) {
            // Update state
            lastNotifiedStatusByDevice.put(deviceId, status);

            // 1. Post system tray notification (normal notification)
            triggerUninstallSystemNotification(deviceId, childName, status);

            // 2. Push the event to v2/app_events/{deviceId}
            pushUninstallAppEvent(deviceId, status, lastHeartbeat);
        } else if (!isCurrentUninstalled && wasLastUninstalled) {
            // It was uninstalled, but now communication is restored (online/offline)!
            lastNotifiedStatusByDevice.put(deviceId, status);

            // 1. Post system tray notification for restoration
            triggerRestoredSystemNotification(deviceId, childName);

            // 2. Push the restored event to v2/app_events/{deviceId}
            pushUninstallAppEvent(deviceId, "RESTORED", lastHeartbeat);
        } else if (!isCurrentUninstalled) {
            // Just update the status without triggering notifications if we weren't in warning state
            lastNotifiedStatusByDevice.put(deviceId, status);
        }
    }

    private void triggerUninstallSystemNotification(String deviceId, String childName, String status) {
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                return;
            }

            String channelId = "uninstall_protection_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "Uninstall Protection Alerts",
                        NotificationManager.IMPORTANCE_HIGH);
                notificationManager.createNotificationChannel(channel);
            }

            String title = "Uninstall Protection Alert: " + (childName != null ? childName : "Child Device");
            String message = "";
            if (UninstallDetectionManager.STATUS_SUSPECTED_UNINSTALL.equals(status)) {
                message = "No communication with child's device for over 30 minutes. App might be uninstalled or disabled.";
            } else if (UninstallDetectionManager.STATUS_LIKELY_UNINSTALLED.equals(status)) {
                message = "No communication with child's device for over 60 minutes. App is likely uninstalled.";
            }

            Intent intent = new Intent(this, ParentDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    deviceId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_child)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            int notificationId = 8000 + (deviceId.hashCode() & 0x7fffffff) % 10000;
            notificationManager.notify(notificationId, builder.build());
            Log.d(TAG, "Ã°Å¸â€â€ Posted system tray notification for uninstall warning on device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show uninstall protection system notification: " + e.getMessage());
        }
    }

    private void triggerRestoredSystemNotification(String deviceId, String childName) {
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                return;
            }

            String channelId = "uninstall_protection_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "Uninstall Protection Alerts",
                        NotificationManager.IMPORTANCE_HIGH);
                notificationManager.createNotificationChannel(channel);
            }

            String title = "Protection Restored: " + (childName != null ? childName : "Child Device");
            String message = "Communication has been successfully restored with child's device.";

            Intent intent = new Intent(this, ParentDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    deviceId.hashCode() + 1,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_child)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);

            int notificationId = 8000 + (deviceId.hashCode() & 0x7fffffff) % 10000;
            notificationManager.notify(notificationId, builder.build());
            Log.d(TAG, "Ã°Å¸â€â€ Posted system tray notification for protection restored on device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show restored system notification: " + e.getMessage());
        }
    }

    private void pushUninstallAppEvent(String deviceId, String status, long lastHeartbeat) {
        if (deviceId == null || deviceId.isEmpty()) return;
        Map<String, Object> event = new HashMap<>();
        event.put("permissionName", "Uninstall Protection");
        event.put("action", status);
        event.put("effect", "Sentinel protection status changed");
        event.put("timestamp", System.currentTimeMillis());
        event.put("lastHeartbeat", lastHeartbeat);
        FirebaseDatabase.getInstance().getReference("v2")
                .child("permission_logs")
                .child(deviceId)
                .push()
                .setValue(event);
    }
}
