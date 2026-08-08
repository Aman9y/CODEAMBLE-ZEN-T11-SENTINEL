package online.monarchlabs.sentinel;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.auth.FirebaseAuth;
import online.monarchlabs.sentinel.data.StudyModeContract;
import online.monarchlabs.sentinel.data.StudyModePolicyRepository;
import online.monarchlabs.sentinel.models.StudyModePolicy;
import online.monarchlabs.sentinel.utils.StudyModeScheduleEvaluator;
import online.monarchlabs.sentinel.utils.AppCategorizer;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.app.PendingIntent;
import androidx.annotation.NonNull;
import android.os.PowerManager;
import com.google.android.gms.tasks.CancellationTokenSource;
import online.monarchlabs.sentinel.services.PersistentConnectionService;
import online.monarchlabs.sentinel.utils.SUsageDataManager;
import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.services.GeofenceService;

public class RemoteBlockService extends Service {
    private static final String TAG = "RemoteBlockService";
    private static final String PREF_NAME = "blocked_apps";
    private static final String PREF_STUDY_MODE_BLOCKS = "study_mode_blocks";
    private static final long STUDY_MODE_MIN_TICK_MS = 1_000L;
    private static final long STUDY_MODE_BOUNDARY_GRACE_MS = 750L;
    private static final long STUDY_MODE_FALLBACK_TICK_MS = 15 * 60_000L;

    // ðŸ”§ OEM COMPATIBILITY: Wake lock for aggressive OEMs
    private PowerManager.WakeLock wakeLock;
    private OEMCompatibilityManager oemManager;

    // === Usage-snapshot constants ===
    private static final int DAYS_WINDOW = 7; // today + previous 6 days
    private static final long LIMIT_MILLIS = 150 * 60 * 1000L; // 2.5 h threshold for trimming
    private static final long SNAPSHOT_INTERVAL_MS = 30 * 1000L; // every 30 seconds for faster updates

    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.as",
            "com.google.android.permissioncontroller",
            "com.miui.home",
            "com.miui.systemui",
            "com.mi.android.globallauncher",
            "com.miui.securitycenter",
            "com.miui.cleanmaster",
            "com.miui.securityadd",
            "com.miui.miservice"));

    private static final String CHANNEL_ID = "child_monitoring_status_v2";

    private SharedPreferences blockedAppsPrefs;
    private String myDeviceId;
    private DatabaseReference blockPoliciesRef;
    private ChildEventListener blockPolicyListener;
    private DeviceStatusManager deviceStatusManager;
    private SessionManager sessionManager;
    private String boundConnectionId;

    private Handler usageHandler;
    private Runnable usageRunnable;

    // NEW: Logout listener variables
    private DatabaseReference logoutRef;
    private ValueEventListener logoutListener;
    private String parentUserId;

    // References and listeners for dynamic re-binding
    private DatabaseReference usageRefreshRef;
    private ValueEventListener usageRefreshListener;
    private DatabaseReference susageRequestRef;
    private ValueEventListener susageUpdateListener;
    private com.google.firebase.database.Query v2CommandsRef;
    private ValueEventListener v2CommandsListener;
    private FirebaseAuth.AuthStateListener authStateListener;

    // Location tracking
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private DatabaseReference locationRequestRef;
    private ValueEventListener locationRequestListener;
    private boolean locationRequestInFlight;
    private android.location.LocationListener nativeLocationListener;
    private BroadcastReceiver gpsProviderReceiver;

    // Real-time blocked apps sync down
    private DatabaseReference blockedAppsListenerRef;
    private ValueEventListener blockedAppsListener;

    private DatabaseReference studyModeListenerRef;
    private ValueEventListener studyModeListener;
    private Handler studyModeHandler;
    private Runnable studyModeRunnable;
    private BroadcastReceiver appInventoryChangeReceiver;
    private StudyModePolicy currentStudyModePolicy;

    private void promoteToForeground() {
        try {
            createNotificationChannel();

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Sentinel child monitoring is active")
                    .setContentText("Your linked parent can view app usage and location when enabled")
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentIntent(createMonitoringDisclosureIntent())
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setGroup("online.monarchlabs.sentinel.SERVICE_REMOTE_BLOCK")
                    .build();

            boolean hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                int serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (hasFine || hasCoarse) {
                    serviceType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                    Log.d(TAG, "ðŸ“ Including LOCATION type in foreground service");
                } else {
                    Log.w(TAG, "Location permission not granted - using parental-control service type only");
                }
                startForeground(1, notification, serviceType);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (hasFine || hasCoarse)) {
                startForeground(1, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1, notification);
            }

            Log.d(TAG, "âœ… High-priority foreground service promoted/updated");
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to promote/update foreground: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!ChildMonitoringDisclosureActivity.hasAcceptedDisclosure(this)) {
            Log.w(TAG, "Monitoring disclosure has not been accepted; stopping service");
            stopSelf();
            return;
        }
        Log.d(TAG, "ðŸ›¡ï¸ RemoteBlockService created - BULLETPROOF MODE");

        // ðŸ›¡ï¸ BULLETPROOF: Wrap everything in try-catch to prevent crashes
        try {
            // ðŸ”§ OEM COMPATIBILITY: Initialize OEM manager and wake lock
            oemManager = new OEMCompatibilityManager(this);
            oemManager.logOEMInfo();
            acquireOEMWakeLock();
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to initialize OEM manager: " + e.getMessage());
        }

        // ðŸ›¡ï¸ DEVICE OWNER: Protections removed as requested
        Log.d(TAG, "ðŸ›¡ï¸ Device Owner checks disabled");

        // Create notification channel & promote to foreground (CRITICAL)
        promoteToForeground();

        try {
            blockedAppsPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            blockedAppsPrefs.edit()
                    .remove(AppBlockingPolicy.ANDROID_SETTINGS_PACKAGE)
                    .apply();
            myDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            Log.d(TAG, "RemoteBlockService myDeviceId: " + myDeviceId);

            // Initialize session manager
            sessionManager = new SessionManager(this);
            ChildDisconnectionCoordinator.validateCurrentOwnership(
                    this, "remote_service_create");
            String sessionDeviceId = sessionManager.getChildDeviceId();
            if (sessionDeviceId != null && !sessionDeviceId.isEmpty()) {
                myDeviceId = sessionDeviceId;
            }

            // Reboot recovery: check and recover scheduled blocks
            checkAndRecoverScheduledBlocks();
            // Push the current block list to Firebase so the parent dashboard is immediately up-to-date
            syncBlockedAppsToFirebase();
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to initialize core components: " + e.getMessage());
        }

        // ðŸš« DISABLED: SmartUsageTracker - now using BulletproofUsageTracker in
        // ChildDashboardActivity
        Log.d(TAG, "â„¹ï¸ SmartUsageTracker DISABLED - using BulletproofUsageTracker instead");

        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance();
            recoverAuthoritativeParentOwnership(database);
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to get Firebase reference: " + e.getMessage());
        }

        // Initialize device status manager with error handling
        try {
            deviceStatusManager = new DeviceStatusManager(this);
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to start device status manager: " + e.getMessage());
        }

        // Setup AuthStateListener to handle auth state transitions and dynamically bind listeners
        authStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                if (firebaseAuth.getCurrentUser() != null) {
                    Log.d(TAG, "ðŸ”’ Firebase Auth State Changed: USER SIGNED IN");
                    updateDeviceIdAndListeners();
                } else {
                    Log.d(TAG, "ðŸ”’ Firebase Auth State Changed: USER SIGNED OUT");
                    removeAllListeners();
                }
            }
        };
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener);

        // Delay non-critical operations
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // AUTO-REFRESH: Upload latest app list to Firebase
                refreshDeviceAppList();
                Log.d(TAG, "âœ… Device app list refreshed");
            } catch (Exception e) {
                Log.e(TAG, "âŒ Failed to refresh device app list: " + e.getMessage());
            }

            try {
                // ðŸ”§ DB CONNECTION FIX: Enable Firebase persistence and keepAlive
                enableFirebaseConnectionStability();
                Log.d(TAG, "âœ… Firebase connection stability enabled");
            } catch (Exception e) {
                Log.e(TAG, "âŒ Failed to enable Firebase stability: " + e.getMessage());
            }

            try {
                registerGpsProviderReceiver();
            } catch (Exception e) {
                Log.e(TAG, "âŒ Failed to register GPS provider receiver: " + e.getMessage());
            }
        }, 3000); // 3 second delay

        Log.d(TAG, "âœ… RemoteBlockService onCreate completed - BULLETPROOF");
    }

    // â”€â”€ Location tracking â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void recoverAuthoritativeParentOwnership(FirebaseDatabase database) {
        if (myDeviceId == null || myDeviceId.isEmpty() || sessionManager == null) {
            return;
        }

        database.getReference("v2")
                .child("device_owners")
                .child(myDeviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String status = snapshot.child("status").getValue(String.class);
                        String ownerId = snapshot.child("parentUid").getValue(String.class);
                        boolean activeOwner = snapshot.exists()
                                && (status == null || status.isEmpty() || "active".equalsIgnoreCase(status));
                        if (activeOwner && ownerId != null && !ownerId.isEmpty()) {
                            sessionManager.saveParentUserId(ownerId);
                            Log.d(TAG, "Recovered parent ownership from v2/device_owners");
                        } else {
                            Log.d(TAG, "No active v2 owner found; skipping legacy ownership recovery");
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "v2 ownership recovery failed: " + error.getMessage());
                    }
                });
    }
    private static final String CHILD_LOCATION_PREFS = "child_location_prefs";
    private static final String KEY_LAST_LAT = "last_lat";
    private static final String KEY_LAST_LNG = "last_lng";

    private double getLastUploadedLat() {
        android.content.SharedPreferences prefs = getSharedPreferences(CHILD_LOCATION_PREFS, Context.MODE_PRIVATE);
        return (double) prefs.getFloat(KEY_LAST_LAT, 0.0f);
    }

    private double getLastUploadedLng() {
        android.content.SharedPreferences prefs = getSharedPreferences(CHILD_LOCATION_PREFS, Context.MODE_PRIVATE);
        return (double) prefs.getFloat(KEY_LAST_LNG, 0.0f);
    }

    private void saveLastUploadedLocation(double lat, double lng) {
        android.content.SharedPreferences prefs = getSharedPreferences(CHILD_LOCATION_PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putFloat(KEY_LAST_LAT, (float) lat)
                .putFloat(KEY_LAST_LNG, (float) lng)
                .putLong("last_upload_at", System.currentTimeMillis())
                .apply();
    }

    private void checkAndUploadLocation(Location location, boolean force) {
        if (location == null) {
            return;
        }

        double currentLat = location.getLatitude();
        double currentLng = location.getLongitude();
        double lastLat = getLastUploadedLat();
        double lastLng = getLastUploadedLng();
        long lastUploadAt = getSharedPreferences(
                CHILD_LOCATION_PREFS, Context.MODE_PRIVATE)
                .getLong("last_upload_at", 0L);
        boolean heartbeatDue = lastUploadAt <= 0L
                || System.currentTimeMillis() - lastUploadAt
                >= 30L * 60L * 1000L;

        if (force || (lastLat == 0.0 && lastLng == 0.0)) {
            uploadLocationToFirebase(location);
            return;
        }

        try {
            float[] results = new float[1];
            Location.distanceBetween(
                    lastLat, lastLng, currentLat, currentLng, results);
            if (results[0] >= 30.0f || heartbeatDue) {
                uploadLocationToFirebase(location);
            } else {
                Log.d(TAG, "Location unchanged; skipping Firebase upload");
            }
        } catch (Exception error) {
            Log.w(TAG, "Distance calculation failed; uploading current fix");
            uploadLocationToFirebase(location);
        }
    }
    private void startLocationUploads() {
        stopLocationUpdates();
        stopLocationRequestListener();
        boolean hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!hasFine && !hasCoarse) {
            Log.d(TAG, "âš ï¸ Location permission not granted â€” skipping location uploads");
            uploadPermissionDeniedToFirebase();
            return;
        }

        // Dynamically update foreground service type to include location if not already
        promoteToForeground();

        // Check whether location services are enabled on the device.
        if (!isGpsEnabled()) {
            Log.d(TAG, "âš ï¸ Location services are disabled on child device");
            uploadGpsOffToFirebase();
            // Still set up the listener so we can react when parent requests location
            if (isGooglePlayServicesAvailable()) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
            }
            startLocationRequestListener();
            return;
        }
        try {
            if (isGooglePlayServicesAvailable()) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

                // Use balanced accuracy if only coarse location is granted
                int priority = hasFine ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;
                LocationRequest locationRequest = new LocationRequest.Builder(
                        priority, 5 * 60 * 1000L)  // passive interval 5 min
                        .setMinUpdateIntervalMillis(60 * 1000L)            // fastest 1 min
                        .setMaxUpdateDelayMillis(10 * 60 * 1000L)
                        .setMinUpdateDistanceMeters(30.0f)                 // displacement filter 30 meters
                        .build();

                locationCallback = new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult result) {
                        if (result == null || result.getLastLocation() == null) return;
                        checkAndUploadLocation(result.getLastLocation(), false);
                    }
                };
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback,
                        Looper.getMainLooper());
                Log.d(TAG, "âœ… Location updates started (displacement: 30m)");

                // Upload the best last-known fix immediately so the parent sees something straight away.
                fusedLocationClient.getLastLocation().addOnSuccessListener(loc -> {
                    if (loc != null) {
                        checkAndUploadLocation(loc, false);
                    } else {
                        Log.d(TAG, "ðŸ“ No cached location yet - requesting fresh fix with timeout");
                        fetchAndUploadCurrentLocationWithTimeout(false);
                    }
                });
            } else {
                startNativeLocationUpdates();
            }

            // Listen for on-demand location requests from parent (blue button press)
            startLocationRequestListener();
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to start location updates: " + e.getMessage());
            // Fallback on failure
            startNativeLocationUpdates();
        }
    }

    private boolean isGooglePlayServicesAvailable() {
        try {
            com.google.android.gms.common.GoogleApiAvailability apiAvailability =
                    com.google.android.gms.common.GoogleApiAvailability.getInstance();
            int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);
            return resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private void startNativeLocationUpdates() {
        Log.d(TAG, "ðŸ”„ Starting native LocationManager updates (Fallback)...");
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;

            // Check if GPS or Network provider is enabled
            String provider = null;
            boolean hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (hasFine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                provider = LocationManager.NETWORK_PROVIDER;
            }

            if (provider == null) {
                Log.d(TAG, "âš ï¸ No location providers enabled for native updates");
                uploadGpsOffToFirebase();
                return;
            }

            if (nativeLocationListener != null) {
                try { lm.removeUpdates(nativeLocationListener); } catch (Exception ignored) {}
            }

            nativeLocationListener = new android.location.LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
                    checkAndUploadLocation(location, false);
                }
                @Override
                public void onProviderDisabled(@NonNull String provider) {
                    uploadGpsOffToFirebase();
                }
                @Override
                public void onProviderEnabled(@NonNull String provider) {
                    clearGpsOffFlag();
                }
                @Override
                public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
            };

            lm.requestLocationUpdates(provider, 5 * 60 * 1000L, 30f, nativeLocationListener, Looper.getMainLooper());
            Log.d(TAG, "âœ… Native LocationManager updates started using provider: " + provider + " (displacement: 30m)");

            Location lastKnown = lm.getLastKnownLocation(provider);
            if (lastKnown != null) {
                checkAndUploadLocation(lastKnown, false);
            } else {
                Log.d(TAG, "ðŸ“ No native cached location, requesting one-time update...");
                lm.requestSingleUpdate(provider, nativeLocationListener, Looper.getMainLooper());
            }
        } catch (SecurityException se) {
            Log.e(TAG, "âŒ Native location permission missing: " + se.getMessage());
            uploadPermissionDeniedToFirebase();
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to start native location updates: " + e.getMessage());
        }
    }

    private boolean isGpsEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return lm.isLocationEnabled();
            }
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes a gps_off marker so the parent UI can show a friendly warning. */
    private void uploadGpsOffToFirebase() {
        if (myDeviceId == null || myDeviceId.isEmpty()) return;
        Map<String, Object> data = new HashMap<>();
        data.put("gps_off", true);
        data.put("timestamp", System.currentTimeMillis());
        online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository
                .patchLocation(myDeviceId, data)
                .addOnFailureListener(error ->
                        Log.w(TAG, "v2 location status update failed: "
                                + error.getMessage()));
        Log.d(TAG, "ðŸ“ Uploaded gps_off=true to Firebase");
    }

    private void uploadPermissionDeniedToFirebase() {
        if (myDeviceId == null || myDeviceId.isEmpty()) return;
        Map<String, Object> data = new HashMap<>();
        data.put("gps_off", false);
        data.put("status", "permission_denied");
        data.put("timestamp", System.currentTimeMillis());
        online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository
                .patchLocation(myDeviceId, data)
                .addOnFailureListener(error ->
                        Log.w(TAG, "v2 location status update failed: "
                                + error.getMessage()));
        Log.d(TAG, "ðŸ“ Uploaded permission_denied status to Firebase");
    }

    /** Clears any GPS-off warning without claiming a valid fix exists yet. */
    private void clearGpsOffFlag() {
        if (myDeviceId == null || myDeviceId.isEmpty()) return;
        Map<String, Object> data = new HashMap<>();
        data.put("gps_off", false);
        data.put("status", "waiting_for_fix");
        data.put("timestamp", System.currentTimeMillis());
        online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository
                .patchLocation(myDeviceId, data)
                .addOnFailureListener(error ->
                        Log.w(TAG, "v2 location status update failed: "
                                + error.getMessage()));
        Log.d(TAG, "ðŸ“ Cleared gps_off flag while waiting for a usable location fix");
    }

    /** Listen for one owner-scoped v2 manual location request. */
    private void startLocationRequestListener() {
        stopLocationRequestListener();
        if (myDeviceId == null || myDeviceId.isEmpty()) {
            return;
        }

        locationRequestRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("commands")
                .child(myDeviceId)
                .child("location_refresh");
        locationRequestListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String status = snapshot.child("status").getValue(String.class);
                String command = snapshot.child("command").getValue(String.class);
                Long issuedAt = snapshot.child("issuedAt").getValue(Long.class);
                if (!"pending".equals(status)
                        || !"refresh_location".equals(command)) {
                    return;
                }
                locationRequestInFlight = true;
                if (issuedAt != null
                        && System.currentTimeMillis() - issuedAt
                        > 10L * 60L * 1000L) {
                    completeLocationRequest("expired");
                    return;
                }

                Map<String, Object> accepted = new HashMap<>();
                accepted.put("status", "processing");
                accepted.put("acceptedAt", System.currentTimeMillis());
                locationRequestRef.updateChildren(accepted);

                boolean hasFine = ActivityCompat.checkSelfPermission(
                        RemoteBlockService.this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
                boolean hasCoarse = ActivityCompat.checkSelfPermission(
                        RemoteBlockService.this,
                        Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
                if (!hasFine && !hasCoarse) {
                    uploadPermissionDeniedToFirebase();
                    completeLocationRequest("permission_denied");
                    return;
                }
                if (!isGpsEnabled()) {
                    uploadGpsOffToFirebase();
                    completeLocationRequest("gps_off");
                    return;
                }
                fetchAndUploadCurrentLocationWithTimeout(true);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "v2 location request listener cancelled: "
                        + error.getMessage());
            }
        };
        locationRequestRef.addValueEventListener(locationRequestListener);
    }

    private void completeLocationRequest(String result) {
        if (locationRequestRef == null || !locationRequestInFlight) {
            return;
        }
        locationRequestInFlight = false;
        Map<String, Object> completion = new HashMap<>();
        completion.put("status", "completed");
        completion.put("result", result);
        completion.put("completedAt", System.currentTimeMillis());
        locationRequestRef.updateChildren(completion);
    }
    private void uploadLocationToFirebase(Location location) {
        if (myDeviceId == null || myDeviceId.isEmpty() || location == null) {
            return;
        }
        Map<String, Object> locationData = new HashMap<>();
        locationData.put("lat", location.getLatitude());
        locationData.put("lng", location.getLongitude());
        locationData.put("accuracy", location.getAccuracy());
        locationData.put("gps_off", false);
        locationData.put("status", "active");
        locationData.put("timestamp", System.currentTimeMillis());
        FirebaseSchemaV2Repository.syncLocation(myDeviceId, locationData)
                .addOnSuccessListener(ignored -> {
                    saveLastUploadedLocation(
                            location.getLatitude(),
                            location.getLongitude());
                    completeLocationRequest("success");
                    GeofenceService.evaluateAndPublish(this, myDeviceId,
                            location.getLatitude(), location.getLongitude());
                    Log.d(TAG, "Location uploaded to v2");
                })
                .addOnFailureListener(error ->
                        Log.w(TAG, "Location upload will retry: "
                                + error.getMessage()));
    }
    private void fetchAndUploadCurrentLocationWithTimeout(boolean force) {
        boolean hasFine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) {
            uploadPermissionDeniedToFirebase();
            return;
        }

        if (!isGooglePlayServicesAvailable() || fusedLocationClient == null) {
            Log.d(TAG, "ðŸ“ Play Services not available or client null â€” fetching native location for timeout");
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    String provider = (hasFine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
                    Location lastKnown = lm.getLastKnownLocation(provider);
                    if (lastKnown != null) {
                        checkAndUploadLocation(lastKnown, force);
                    } else {
                        lm.requestSingleUpdate(provider, new android.location.LocationListener() {
                            @Override
                            public void onLocationChanged(@NonNull Location location) {
                                checkAndUploadLocation(location, force);
                            }
                            @Override
                            public void onProviderDisabled(@NonNull String provider) {}
                            @Override
                            public void onProviderEnabled(@NonNull String provider) {}
                            @Override
                            public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
                        }, Looper.getMainLooper());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "âŒ Failed to fetch native location for timeout: " + e.getMessage());
            }
            return;
        }

        CancellationTokenSource cts = new CancellationTokenSource();
        // Cancel the location request token source after 8 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                cts.cancel();
            } catch (Exception ignored) {}
        }, 8000);

        int priority = hasFine ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;
        fusedLocationClient.getCurrentLocation(priority, cts.getToken())
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        Location loc = task.getResult();
                        checkAndUploadLocation(loc, force);
                        Log.d(TAG, "âœ… High-accuracy location uploaded: " + loc.getLatitude() + ", " + loc.getLongitude());
                    } else {
                        Log.d(TAG, "ðŸ“ High-accuracy location failed or timed out, trying last known location...");
                        try {
                            fusedLocationClient.getLastLocation()
                                    .addOnSuccessListener(l -> {
                                        if (l != null) {
                                            checkAndUploadLocation(l, force);
                                        } else {
                                            clearGpsOffFlag();
                                        }
                                    });
                        } catch (SecurityException ignored) {}
                    }
                });
    }

    /**
     * ðŸ”§ DB CONNECTION FIX: Enable Firebase connection stability features
     * This helps prevent disconnections on OEM devices
     */
    private void enableFirebaseConnectionStability() {
        try {
            // Enable disk persistence for offline support
            FirebaseDatabase database = FirebaseDatabase.getInstance();

            // ðŸ”§ FIX: Don't call keepSynced on .info paths - they don't support it
            // Keep important DATA paths synced instead (not .info paths)
            if (myDeviceId != null && !myDeviceId.isEmpty()) {
                database.getReference("v2").child("device_status").child(myDeviceId).keepSynced(true);
                Log.d(TAG, "âœ… Firebase paths kept synced for device: " + myDeviceId);
            }

            // Setup connection state listener
            DatabaseReference connectedRef = database.getReference(".info/connected");
            connectedRef.addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    Boolean connected = snapshot.getValue(Boolean.class);
                    if (connected != null) {
                        if (connected) {
                            Log.d(TAG, "âœ… Firebase CONNECTED - Database is online");
                            // Device status is automatically updated by DeviceStatusManager's own listener
                        } else {
                            Log.w(TAG, "âš ï¸ Firebase DISCONNECTED - Will auto-reconnect");

                            // Try to force reconnect on aggressive OEMs
                            if (oemManager != null && oemManager.isAggressiveOEM()) {
                                Log.d(TAG, "ðŸ”„ Aggressive OEM detected - scheduling reconnect attempt");
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    database.goOnline();
                                    Log.d(TAG, "ðŸ”„ Forced Firebase reconnection attempt");
                                }, 5000); // 5 second delay
                            }
                        }
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "âŒ Firebase connection listener cancelled: " + error.getMessage());
                }
            });

            Log.d(TAG, "âœ… Firebase connection stability enabled");

        } catch (Exception e) {
            Log.e(TAG, "âŒ Error enabling Firebase stability: " + e.getMessage());
        }
    }

    // Canonical v2 removal listener. Legacy parent child records are cleanup data,
    // not the authoritative child logout signal.
    private void setupLogoutListener() {
        Log.d(TAG, "=== V2 REMOVAL LISTENER SETUP ===");

        if (logoutListener != null && logoutRef != null) {
            try {
                logoutRef.removeEventListener(logoutListener);
                Log.d(TAG, "setupLogoutListener: Removed old v2 removal listener");
            } catch (Exception e) {
                Log.e(TAG, "setupLogoutListener: Error removing old listener: " + e.getMessage());
            }
            logoutListener = null;
        }

        if (sessionManager == null) {
            Log.e(TAG, "SessionManager is null!");
            return;
        }

        if (!sessionManager.isLoggedIn() || !"child".equals(sessionManager.getUserType())) {
            Log.w(TAG, "Skipping v2 removal listener; active session is not child");
            return;
        }

        String childDeviceId = sessionManager.getChildDeviceId();
        parentUserId = sessionManager.getParentUserId();

        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.e(TAG, "Child device ID is null or empty!");
            return;
        }

        Log.d(TAG, "Setting up v2 removal listener: v2/device_removals/" + childDeviceId);

        logoutRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_removals")
                .child(childDeviceId);

        logoutListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Boolean trigger = dataSnapshot.child("trigger").getValue(Boolean.class);
                Boolean removedByParent = dataSnapshot.child("removed_by_parent").getValue(Boolean.class);
                if (!Boolean.TRUE.equals(trigger) && !Boolean.TRUE.equals(removedByParent)) {
                    return;
                }
                ChildDisconnectionCoordinator.processRemovalMarker(
                        RemoteBlockService.this, dataSnapshot, logoutRef);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "V2 removal listener cancelled: " + databaseError.getMessage());
            }
        };

        logoutRef.addValueEventListener(logoutListener);
        Log.d(TAG, "v2 removal listener successfully attached");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Child monitoring status",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Persistent notice that Sentinel child monitoring is active");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
                nm.deleteNotificationChannel("remote_block_bg");
            }
        }
    }

    private PendingIntent createMonitoringDisclosureIntent() {
        Intent intent = new Intent(this, ChildMonitoringDisclosureActivity.class);
        intent.putExtra(ChildMonitoringDisclosureActivity.EXTRA_VIEW_ONLY, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                4101,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Setup listener for usage data refresh commands from parent
     */
    private void setupUsageRefreshListener() {
        Log.d(TAG, "ðŸ”„ Setting up usage refresh command listener for device: " + myDeviceId);

        if (usageRefreshListener != null && usageRefreshRef != null) {
            try {
                usageRefreshRef.removeEventListener(usageRefreshListener);
            } catch (Exception e) {
                Log.e(TAG, "setupUsageRefreshListener: Error removing old listener: " + e.getMessage());
            }
            usageRefreshListener = null;
        }

        usageRefreshRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("commands")
                .child(myDeviceId)
                .child("usage_refresh");

        usageRefreshListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists())
                    return;

                Log.d(TAG, "ðŸ“¥ Usage refresh command received");

                try {
                    String command = dataSnapshot.child("command").getValue(String.class);
                    Long timestamp = dataSnapshot.child("timestamp").getValue(Long.class);
                    String requestedBy = dataSnapshot.child("requestedBy").getValue(String.class);
                    String priority = dataSnapshot.child("priority").getValue(String.class);

                    if ("refresh_usage_data".equals(command) && timestamp != null) {
                        // Check if command is recent (within 5 minutes)
                        long currentTime = System.currentTimeMillis();
                        long commandAge = currentTime - timestamp;

                        if (commandAge < 300000) { // 5 minutes
                            Log.d(TAG, "ðŸš€ Processing usage refresh command (priority: " + priority + ")");

                            // Force immediate usage snapshot upload
                            if (hasUsageStatsPermission()) {
                                try {
                                    SUsageDataManager.getInstance(RemoteBlockService.this)
                                            .uploadToFirebase(myDeviceId, false, null);
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to upload SUSAGE data: " + e.getMessage());
                                }
                                Log.d(TAG, "âœ… Immediate usage snapshot uploaded");
                            } else {
                                Log.w(TAG, "âŒ Cannot upload usage data - missing permission");
                            }

                            // Clear the command to prevent re-processing
                            usageRefreshRef.child("status").setValue("processed")
                                    .addOnSuccessListener(aVoid -> Log.d(TAG, "âœ… Refresh command cleared"))
                                    .addOnFailureListener(
                                            e -> Log.e(TAG, "âŒ Failed to clear refresh command: " + e.getMessage()));
                        } else {
                            Log.w(TAG, "â° Ignoring old refresh command (age: " + (commandAge / 1000) + " seconds)");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "âŒ Error processing refresh command: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "âŒ Usage refresh listener cancelled: " + databaseError.getMessage());
            }
        };

        usageRefreshRef.addValueEventListener(usageRefreshListener);
        Log.d(TAG, "âœ… Usage refresh listener setup complete");
    }

    /**
     * Setup listener for SUSAGE-style update requests from parent
     * When parent clicks Update/Refresh, this triggers immediate usage data
     * collection and upload
     */
    private void setupSUsageUpdateListener() {
        Log.d(TAG, "ðŸ”„ Setting up SUSAGE update request listener for device: " + myDeviceId);

        if (susageUpdateListener != null && susageRequestRef != null) {
            try {
                susageRequestRef.removeEventListener(susageUpdateListener);
            } catch (Exception e) {
                Log.e(TAG, "setupSUsageUpdateListener: Error removing old listener: " + e.getMessage());
            }
            susageUpdateListener = null;
        }

        susageRequestRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("commands")
                .child(myDeviceId)
                .child("usage_refresh");

        susageUpdateListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists())
                    return;

                Boolean requestUpdate = dataSnapshot.child("requestUpdate").getValue(Boolean.class);

                if (Boolean.TRUE.equals(requestUpdate)) {
                    Log.d(TAG, "ðŸ“¥ SUSAGE update request received from parent!");

                    if (hasUsageStatsPermission()) {
                        Log.d(TAG, "ðŸš€ Collecting and uploading SUSAGE data...");

                        // Use SUsageDataManager to collect and upload data
                        try {
                            online.monarchlabs.sentinel.utils.SUsageDataManager usageManager = online.monarchlabs.sentinel.utils.SUsageDataManager
                                    .getInstance(RemoteBlockService.this);

                            usageManager.uploadToFirebase(myDeviceId, false,
                                    new online.monarchlabs.sentinel.utils.SUsageDataManager.OnUploadCompleteListener() {
                                        @Override
                                        public void onSuccess() {
                                            Log.d(TAG, "âœ… SUSAGE data uploaded successfully");
                                            // Clear the request flag
                                            susageRequestRef.child("requestUpdate").setValue(false);
                                        }

                                        @Override
                                        public void onError(String error) {
                                            Log.e(TAG, "âŒ SUSAGE upload failed: " + error);
                                            // Clear the request flag even on error
                                            susageRequestRef.child("requestUpdate").setValue(false);
                                        }
                                    });
                        } catch (Exception e) {
                            Log.e(TAG, "âŒ Error uploading SUSAGE data: " + e.getMessage());
                            susageRequestRef.child("requestUpdate").setValue(false);
                        }
                    } else {
                        Log.w(TAG, "âŒ Cannot upload SUSAGE data - missing UsageStats permission");
                        susageRequestRef.child("requestUpdate").setValue(false);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "âŒ SUSAGE update listener cancelled: " + databaseError.getMessage());
            }
        };

        susageRequestRef.addValueEventListener(susageUpdateListener);
        Log.d(TAG, "âœ… SUSAGE update listener setup complete");
    }

    private void startListeningForBlockCommands() {
        setupCurrentBlockPolicyListener();
    }
    private void setupCurrentBlockPolicyListener() {
        blockPoliciesRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_policies")
                .child(myDeviceId)
                .child("blocked_apps");

        blockPolicyListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot,
                    String previousChildName) {
                applyCurrentBlockPolicy(snapshot);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot,
                    String previousChildName) {
                applyCurrentBlockPolicy(snapshot);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                // Unblock is represented by an explicit blocked=false policy.
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot,
                    String previousChildName) {
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Block policy listener cancelled: " + error.getMessage());
            }
        };
        blockPoliciesRef.addChildEventListener(blockPolicyListener);
    }

    private void applyCurrentBlockPolicy(DataSnapshot snapshot) {
        String packageName = snapshot.child("packageName").getValue(String.class);
        String policyId = snapshot.child("policyId").getValue(String.class);
        Boolean desiredBlocked = snapshot.child("blocked").getValue(Boolean.class);
        if (packageName == null || policyId == null || desiredBlocked == null) {
            return;
        }

        SharedPreferences appliedPolicies = getSharedPreferences(
                "applied_block_policies_" + myDeviceId, MODE_PRIVATE);
        if (policyId.equals(appliedPolicies.getString(packageName, ""))) {
            return;
        }

        String enforcementMode = snapshot.child("enforcementMode").getValue(String.class);
        Long delayDuration = snapshot.child("delayDurationMs").getValue(Long.class);
        boolean blocked = desiredBlocked && !AppBlockingPolicy.isUnblockable(packageName);
        boolean appliedBlocked = blockedAppsPrefs.getBoolean(packageName, false);
        String status;

        if (blocked && "DELAYED".equals(enforcementMode)) {
            cancelDelayedBlock(packageName);
            long triggerTime = System.currentTimeMillis()
                    + Math.max(0L, delayDuration != null ? delayDuration : 0L);
            getSharedPreferences("scheduled_blocks", MODE_PRIVATE)
                    .edit()
                    .putLong(packageName, triggerTime)
                    .apply();
            getSharedPreferences("scheduled_block_policy_ids", MODE_PRIVATE)
                    .edit()
                    .putString(packageName, policyId)
                    .apply();
            scheduleDelayedBlockAlarm(packageName, triggerTime);
            status = "SCHEDULED";
        } else {
            cancelDelayedBlock(packageName);
            getSharedPreferences("scheduled_block_policy_ids", MODE_PRIVATE)
                    .edit()
                    .remove(packageName)
                    .apply();
            blockedAppsPrefs.edit().putBoolean(packageName, blocked).apply();
            appliedBlocked = blocked;
            broadcastBlockedAppsUpdate(packageName);
            showBlockNotification(getAppName(packageName), blocked);
            status = "APPLIED";
        }

        appliedPolicies.edit().putString(packageName, policyId).apply();
        acknowledgeBlockPolicy(
                snapshot.getKey(), packageName, policyId,
                blocked, appliedBlocked, status);
    }

    private void acknowledgeBlockPolicy(String appKey, String packageName,
            String policyId, boolean desiredBlocked, boolean appliedBlocked,
            String status) {
        if (appKey == null) {
            return;
        }
        Map<String, Object> state = new HashMap<>();
        state.put("packageName", packageName);
        state.put("appliedPolicyId", policyId);
        state.put("desiredBlocked", desiredBlocked);
        state.put("blocked", appliedBlocked);
        state.put("status", status);
        state.put("appliedAt", ServerValue.TIMESTAMP);
        FirebaseSchemaV2Repository.syncAppBlockState(myDeviceId, appKey, state)
                .addOnFailureListener(error ->
                        Log.w(TAG, "Block policy acknowledgement deferred: "
                                + error.getMessage()));
    }

    private void scheduleDelayedBlockAlarm(String packageName, long triggerTime) {
        try {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) return;

            Intent intent = new Intent(this, RemoteBlockService.class);
            intent.setAction("online.monarchlabs.sentinel.ENFORCE_DELAYED_BLOCK");
            intent.putExtra("packageName", packageName);
            intent.putExtra("appName", getAppName(packageName));

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getService(
                    this, packageName.hashCode(), intent, flags);

            alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent);
            Log.d(TAG, "â° Delayed block alarm scheduled for: " + packageName + " at triggerTime=" + triggerTime);
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule delayed block alarm: " + e.getMessage());
        }
    }

    private void cancelDelayedBlock(String packageName) {
        try {
            getSharedPreferences("scheduled_blocks", Context.MODE_PRIVATE).edit()
                    .remove(packageName)
                    .apply();

            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                Intent intent = new Intent(this, RemoteBlockService.class);
                intent.setAction("online.monarchlabs.sentinel.ENFORCE_DELAYED_BLOCK");
                int flags = PendingIntent.FLAG_NO_CREATE;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent pendingIntent = PendingIntent.getService(
                        this, packageName.hashCode(), intent, flags);

                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent);
                    pendingIntent.cancel();
                    Log.d(TAG, "ðŸš« Cancelled delayed block alarm for: " + packageName);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to cancel delayed block alarm: " + e.getMessage());
        }
    }

    private void enforceDelayedBlock(String packageName, String appName) {
        try {
            if (AppBlockingPolicy.isUnblockable(packageName)) {
                cancelDelayedBlock(packageName);
                blockedAppsPrefs.edit().remove(packageName).apply();
                broadcastBlockedAppsUpdate(packageName);
                Log.w(TAG, "Ignored delayed block for unblockable app: " + packageName);
                return;
            }

            Log.w(TAG, "â° Delayed block alarm triggered for: " + packageName);

            // Commit block
            blockedAppsPrefs.edit().putBoolean(packageName, true).apply();

            // Clean scheduled_blocks
            getSharedPreferences("scheduled_blocks", Context.MODE_PRIVATE).edit()
                    .remove(packageName)
                    .apply();

            // Broadcast update
            broadcastBlockedAppsUpdate(packageName);

            // Show system notification
            showBlockNotification(appName, true);

            String policyId = getSharedPreferences(
                    "scheduled_block_policy_ids", MODE_PRIVATE)
                    .getString(packageName, "");
            if (!policyId.isEmpty()) {
                acknowledgeBlockPolicy(
                        packageName.replace(".", "_").replace("#", "_")
                                .replace("$", "_").replace("[", "_")
                                .replace("]", "_").replace("/", "_"),
                        packageName, policyId, true, true, "APPLIED");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to enforce delayed block: " + e.getMessage());
        }
    }

    private String getAppName(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private void checkAndRecoverScheduledBlocks() {
        try {
            SharedPreferences scheduledPrefs = getSharedPreferences("scheduled_blocks", Context.MODE_PRIVATE);
            Map<String, ?> allScheduled = scheduledPrefs.getAll();
            long currentTime = System.currentTimeMillis();

            for (Map.Entry<String, ?> entry : allScheduled.entrySet()) {
                if (entry.getValue() instanceof Long) {
                    String packageName = entry.getKey();
                    long triggerTime = (Long) entry.getValue();

                    if (currentTime >= triggerTime) {
                        Log.d(TAG, "Reboot Recovery: Trigger time passed for " + packageName + ". Enforcing block now.");
                        enforceDelayedBlock(packageName, getAppName(packageName));
                    } else {
                        Log.d(TAG, "Reboot Recovery: Rescheduling alarm for " + packageName + " in " + (triggerTime - currentTime) + "ms.");
                        scheduleDelayedBlockAlarm(packageName, triggerTime);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in reboot recovery of scheduled blocks: " + e.getMessage());
        }
    }

    /**
     * ðŸ”§ IMMEDIATE UPDATE: Broadcast to BlockService that blocked apps list changed
     */
    private void broadcastBlockedAppsUpdate() {
        broadcastBlockedAppsUpdate(null);
    }

    private void broadcastBlockedAppsUpdate(String changedPackage) {
        try {
            // Count effective blocks from manual policy plus Study Mode.
            Set<String> effectiveBlocks = readTruePrefs(blockedAppsPrefs);
            effectiveBlocks.addAll(readTruePrefs(
                    getSharedPreferences(PREF_STUDY_MODE_BLOCKS, MODE_PRIVATE)));
            int blockedCount = effectiveBlocks.size();
            // Send broadcast to BlockService
            Intent broadcastIntent = new Intent("online.monarchlabs.sentinel.BLOCKED_APPS_UPDATED");
            broadcastIntent.putExtra("blocked_count", blockedCount);
            if (changedPackage != null && !changedPackage.isEmpty()) {
                broadcastIntent.putExtra("changed_package", changedPackage);
            }
            broadcastIntent.setPackage(getPackageName()); // Explicit package for security
            sendBroadcast(broadcastIntent);

            Log.d(TAG, "ðŸ“¡ Broadcasted blocked apps update - count: " + blockedCount);
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to broadcast update: " + e.getMessage());
        }
    }

    /**
     * ðŸ”” Show system notification for block/unblock (works when app is closed)
     */
    private void showBlockNotification(String appName, boolean blocked) {
        try {
            String title = blocked ? "App Blocked" : "App Unblocked";
            String message = appName + " has been " + (blocked ? "blocked" : "unblocked") + " by parent";

            android.app.NotificationManager notificationManager = (android.app.NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);

            // Create notification channel for Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "block_notifications",
                        "Block Notifications",
                        android.app.NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Notifications for app blocking");
                notificationManager.createNotificationChannel(channel);
            }

            android.app.Notification notification = new android.app.Notification.Builder(this,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "block_notifications" : null)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setAutoCancel(true)
                    .setPriority(android.app.Notification.PRIORITY_HIGH)
                    .build();

            int notificationId = 6000
                    + (appName != null ? (appName.hashCode() & 0x7fffffff) % 100000 : 0);
            notificationManager.notify(notificationId, notification);
            Log.d(TAG, "ðŸ”” Block notification shown: " + message);

        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to show notification: " + e.getMessage());
        }
    }

    /**
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ðŸ›¡ï¸ CRITICAL FIX: Call startForeground IMMEDIATELY to prevent crash
        // This must be done before any other logic
        try {
            promoteToForeground();
            Log.d(TAG, "âœ… startForeground called in onStartCommand");
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to start foreground: " + e.getMessage());
        }

        try {
            Log.d(TAG, "RemoteBlockService started");
            ChildDisconnectionCoordinator.validateCurrentOwnership(
                    this, "remote_service_start");

            // Hook dynamic listener update/refresh
            updateDeviceIdAndListeners();

            // Handle delayed block alarm enforcement
            if (intent != null && "online.monarchlabs.sentinel.ENFORCE_DELAYED_BLOCK".equals(intent.getAction())) {
                String packageName = intent.getStringExtra("packageName");
                String appName = intent.getStringExtra("appName");
                if (packageName != null) {
                    enforceDelayedBlock(packageName, appName);
                }
            }

            // Handle refresh logout listener request
            if (intent != null && "refresh_logout_listener".equals(intent.getStringExtra("action"))) {
                Log.d(TAG, "ðŸ”„ Received refresh logout listener request");

                try {
                    // Remove existing listener first
                    if (logoutListener != null && logoutRef != null) {
                        logoutRef.removeEventListener(logoutListener);
                        Log.d(TAG, "Removed existing logout listener");
                    }

                    // Setup new listener
                    setupLogoutListener();

                    // Show toast confirmation
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            Toast.makeText(this, "Logout listener refreshed", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Error showing toast: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error refreshing logout listener: " + e.getMessage());
                }
            }

            // Handle immediate usage data collection refresh
            if (intent != null && "refresh_usage_collection".equals(intent.getStringExtra("action"))) {
                Log.d(TAG, "ðŸš€ Received immediate SUSAGE collection refresh request");

                try {
                    // Trigger immediate SUSAGE upload if permission is available
                    if (hasUsageStatsPermission()) {
                        Log.d(TAG, "âœ… Usage permission available - triggering SUSAGE upload");

                        // Use SUSAGE data manager for upload
                        new Thread(() -> {
                            try {
                                online.monarchlabs.sentinel.utils.SUsageDataManager susageManager = online.monarchlabs.sentinel.utils.SUsageDataManager
                                        .getInstance(this);

                                susageManager.uploadToFirebase(myDeviceId, false,
                                        new online.monarchlabs.sentinel.utils.SUsageDataManager.OnUploadCompleteListener() {
                                            @Override
                                            public void onSuccess() {
                                                Log.d(TAG, "âœ… SUSAGE data refreshed successfully");
                                                new Handler(Looper.getMainLooper()).post(() -> {
                                                    try {
                                                        Toast.makeText(RemoteBlockService.this,
                                                                "Usage data refreshed", Toast.LENGTH_SHORT).show();
                                                    } catch (Exception e) {
                                                        Log.e(TAG, "Error showing toast: " + e.getMessage());
                                                    }
                                                });
                                            }

                                            @Override
                                            public void onError(String error) {
                                                Log.e(TAG, "âŒ SUSAGE refresh failed: " + error);
                                            }
                                        });
                            } catch (Exception e) {
                                Log.e(TAG, "Exception in SUSAGE refresh: " + e.getMessage());
                            }
                        }).start();
                    } else {
                        Log.w(TAG, "âŒ Usage permission not available");
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                Toast.makeText(this, "Usage permission required",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Log.e(TAG, "Error showing toast: " + e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error refreshing SUSAGE collection: " + e.getMessage());
                }
            }

            // Handle parent-triggered upload request
            if (intent != null && "UPLOAD_USAGE_DATA".equals(intent.getAction())) {
                Log.d(TAG, "ðŸ”„ Received parent-triggered SUSAGE upload request");

                try {
                    // Trigger immediate SUSAGE upload if permission is available
                    if (hasUsageStatsPermission()) {
                        Log.d(TAG, "âœ… Usage permission available - triggering parent-requested SUSAGE upload");

                        // Use SUSAGE data manager for upload
                        new Thread(() -> {
                            try {
                                online.monarchlabs.sentinel.utils.SUsageDataManager susageManager = online.monarchlabs.sentinel.utils.SUsageDataManager
                                        .getInstance(this);

                                susageManager.uploadToFirebase(myDeviceId, false,
                                        new online.monarchlabs.sentinel.utils.SUsageDataManager.OnUploadCompleteListener() {
                                            @Override
                                            public void onSuccess() {
                                                Log.d(TAG, "âœ… Parent-requested SUSAGE upload successful");
                                                new Handler(Looper.getMainLooper()).post(() -> {
                                                    try {
                                                        Toast.makeText(RemoteBlockService.this,
                                                                "Data uploaded for parent", Toast.LENGTH_SHORT)
                                                                .show();
                                                    } catch (Exception e) {
                                                        Log.e(TAG, "Error showing toast: " + e.getMessage());
                                                    }
                                                });
                                            }

                                            @Override
                                            public void onError(String error) {
                                                Log.e(TAG, "âŒ Parent-requested SUSAGE upload failed: " + error);
                                            }
                                        });
                            } catch (Exception e) {
                                Log.e(TAG, "Exception in parent-requested upload: " + e.getMessage());
                            }
                        }).start();
                    } else {
                        Log.w(TAG, "âŒ Usage permission not available");
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                Toast.makeText(this, "Usage permission required for data upload", Toast.LENGTH_SHORT)
                                        .show();
                            } catch (Exception e) {
                                Log.e(TAG, "Error showing toast: " + e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling parent upload request: " + e.getMessage());
                }
            }

            // Re-check location permission and resume/update uploads if needed
            try {
                startLocationUploads();
            } catch (Exception e) {
                Log.e(TAG, "Error starting location uploads in onStartCommand: " + e.getMessage());
            }

            // ðŸ›¡ï¸ BULLETPROOF: Always return START_STICKY to auto-restart
            return START_STICKY;
        } catch (Exception e) {
            Log.e(TAG, "Critical error in onStartCommand: " + e.getMessage());
            // Don't stop - schedule restart instead
            scheduleServiceRestart();
            return START_STICKY;
        }
    }

    /**
     * ðŸ”§ BULLETPROOF: Schedule service restart if it crashes
     */
    private void scheduleServiceRestart() {
        try {
            Log.d(TAG, "ðŸ”„ Scheduling service restart...");

            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null)
                return;

            Intent restartIntent = new Intent(this, RemoteBlockService.class);
            restartIntent.setAction("RESTART_SERVICE");

            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getService(
                    this, 9999, restartIntent, flags);

            // Restart in 3 seconds
            long restartTime = android.os.SystemClock.elapsedRealtime() + 3000;

            alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    restartTime,
                    pendingIntent);

            Log.d(TAG, "âœ… Service restart scheduled in 3 seconds");

        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to schedule restart: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "âš ï¸ RemoteBlockService being destroyed!");

        if (authStateListener != null) {
            try {
                FirebaseAuth.getInstance().removeAuthStateListener(authStateListener);
            } catch (Exception e) {
                Log.e(TAG, "Error removing authStateListener: " + e.getMessage());
            }
        }

        removeAllListeners();

        // Unregister GPS provider change receiver
        try {
            unregisterGpsProviderReceiver();
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to unregister GPS provider receiver: " + e.getMessage());
        }

        // Stop location updates
        if (fusedLocationClient != null && locationCallback != null) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback);
            } catch (Exception ignored) {}
        }
        if (nativeLocationListener != null) {
            try {
                LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    lm.removeUpdates(nativeLocationListener);
                }
            } catch (Exception ignored) {}
        }

        // Stop periodic uploads
        if (usageHandler != null && usageRunnable != null) {
            usageHandler.removeCallbacks(usageRunnable);
        }

        // Stop device status tracking
        if (deviceStatusManager != null) {
            try {
                deviceStatusManager.stopStatusTracking();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping status tracking: " + e.getMessage());
            }
        }

        // ðŸ”§ OEM COMPATIBILITY: Release wake lock
        releaseOEMWakeLock();

        // ðŸ›¡ï¸ BULLETPROOF: Schedule service restart when destroyed
        // This ensures the service comes back even if killed by OEM
        try {
            SessionManager sm = new SessionManager(this);
            if (sm.isLoggedIn() && "child".equals(sm.getUserType())) {
                Log.d(TAG, "ðŸ”„ Service destroyed - scheduling automatic restart...");
                scheduleServiceRestart();

                // Also notify the watchdog
                ServiceWatchdog.schedulePeriodicChecks(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling restart: " + e.getMessage());
        }
    }



    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private long adjustUsage(long millis) {
        // Don't count usage sessions shorter than 1 second (likely system transitions)
        if (millis < 1000) {
            return 0;
        }

        // Cap maximum usage per session to 3 hours to avoid unrealistic values
        long maxSessionTime = 3 * 60 * 60 * 1000; // 3 hours
        if (millis > maxSessionTime) {
            Log.d(TAG, "âš ï¸ Capping unrealistic usage session from " + formatDuration(millis) + " to "
                    + formatDuration(maxSessionTime));
            millis = maxSessionTime;
        }

        // For usage over 1 hour, apply a small adjustment to account for possible
        // inaccuracies
        // This is more conservative than the previous 90% reduction
        if (millis > 3600000) { // 1 hour
            return (millis * 95) / 100; // 5% reduction for very long sessions
        }

        // For reasonable usage times (1 second to 1 hour), use the actual time
        return millis;
    }

    private long startOfDayMillis(int daysAgo) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return cal.getTimeInMillis();
    }

    private String formatDuration(long millis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        long hours = minutes / 60;
        long rem = minutes % 60;
        return hours > 0 ? hours + " hr " + rem + " min" : rem + " min";
    }

    private boolean shouldSkipPackage(String pkgName) {
        if (pkgName == null || pkgName.isEmpty())
            return true;

        // Never skip our own app for debugging purposes
        if (pkgName.equals(getPackageName()))
            return false;

        // Allow important apps that users commonly interact with (even if system apps)
        String[] allowedSystemApps = {
                "com.android.chrome", "com.google.android.googlequicksearchbox",
                "com.google.android.youtube", "com.youtube.android",
                "com.android.dialer", "com.android.phone", "com.google.android.dialer", "com.samsung.android.dialer",
                "com.android.contacts", "com.google.android.contacts",
                "com.android.camera", "com.android.camera2", "com.google.android.camera", "com.samsung.android.camera",
                "com.android.gallery3d", "com.google.android.apps.photos",
                "com.android.music", "com.google.android.music", "com.spotify.music",
                "com.whatsapp", "com.facebook.katana", "com.instagram.android", "com.twitter.android",
                "com.google.android.gm", "com.android.email", "com.samsung.android.email.provider",
                "com.android.calculator2", "com.google.android.calculator",
                "com.android.settings", // Settings is user-interactive
            "com.android.vending",
                "com.google.android.maps", "com.google.android.apps.maps"
        };

        for (String allowedApp : allowedSystemApps) {
            if (pkgName.equals(allowedApp)) {
                return false;
            }
        }

        String lower = pkgName.toLowerCase();

        // Skip obvious system components that users don't interact with
        String[] systemComponents = {
                "launcher", "systemui", "wallpaper", "inputmethod", "keyboard",
                "com.android.systemui", "com.miui.home", "com.samsung.android.launcher",
                "com.android.nfc", "com.android.bluetooth", "com.android.providers",
                "com.google.android.syncadapters", "com.google.android.gsf",
                "com.android.packageinstaller", "com.android.permissioncontroller"
        };

        for (String component : systemComponents) {
            if (lower.contains(component)) {
                return true;
            }
        }

        // Skip system processes but be more selective
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkgName, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                // For system apps, check if they're user-facing by looking at common patterns
                if (lower.contains("youtube") || lower.contains("chrome") || lower.contains("music") ||
                        lower.contains("video") || lower.contains("photo") || lower.contains("camera") ||
                        lower.contains("dialer") || lower.contains("phone") || lower.contains("contacts") ||
                        lower.contains("gallery") || lower.contains("player") || lower.contains("browser") ||
                        lower.contains("calculator") || lower.contains("calendar") || lower.contains("clock") ||
                        lower.contains("messenger") || lower.contains("email") || lower.contains("maps")) {
                    Log.d(TAG, "âœ… Allowing system app with user interaction: " + pkgName);
                    return false;
                }

                // Skip other system apps
                Log.v(TAG, "â­ï¸ Skipping system app: " + pkgName);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking app flags for " + pkgName + ": " + e.getMessage());
        }

        // Skip if it's in the ignored packages list
        return IGNORED_PACKAGES.contains(pkgName);
    }

    private Map<String, Long> computeUsageFromEvents(UsageStatsManager usm, long start, long end) {
        Map<String, Long> usage = new HashMap<>();
        if (usm == null)
            return usage;

        try {
            UsageEvents events = usm.queryEvents(start, end);
            if (events == null)
                return usage;

            Map<String, Long> resumeTimes = new HashMap<>();
            UsageEvents.Event ev = new UsageEvents.Event();

            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                String pkg = ev.getPackageName();
                long timestamp = ev.getTimeStamp();
                int eventType = ev.getEventType();

                if (pkg == null || shouldSkipPackage(pkg))
                    continue;

                if (eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    // App came to foreground
                    resumeTimes.put(pkg, timestamp);
                    Log.v(TAG, "ðŸš€ App resumed: " + pkg + " at " + new Date(timestamp));

                } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                    // App left foreground
                    Long resumeTime = resumeTimes.remove(pkg);
                    if (resumeTime != null && timestamp > resumeTime) {
                        long dur = timestamp - resumeTime;
                        // Only count realistic foreground time (minimum 1 second, max 2 hours per
                        // session)
                        if (dur >= 1000 && dur <= 7200000) {
                            usage.put(pkg, dur + usage.getOrDefault(pkg, 0L));
                            Log.v(TAG, "â¸ï¸ App paused: " + pkg + " - " + formatDuration(dur));
                        } else if (dur < 1000) {
                            Log.v(TAG, "âš¡ Skipped very short session: " + pkg + " - " + dur + "ms");
                        } else {
                            Log.v(TAG, "â° Skipped unrealistic session: " + pkg + " - " + formatDuration(dur));
                        }
                    }
                }
            }

            // Handle apps that are still active at the end of the period
            for (Map.Entry<String, Long> entry : resumeTimes.entrySet()) {
                String pkg = entry.getKey();
                Long resumeTime = entry.getValue();
                if (resumeTime != null && end > resumeTime) {
                    long dur = end - resumeTime;
                    // Only count realistic foreground time
                    if (dur >= 1000 && dur <= 7200000) {
                        usage.put(pkg, dur + usage.getOrDefault(pkg, 0L));
                        Log.v(TAG, "â³ Session ended - " + pkg + ": " + formatDuration(dur));
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error computing usage from events: " + e.getMessage());
        }

        return usage;
    }

    private void showUsageAccessNotification() {
        // Create intent for usage access settings
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        // Create a notification to prompt the user
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Permission Required")
                .setContentText("Usage access permission needed to track app usage")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.notify(2, notification);
        }

        // Check again after some delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (hasUsageStatsPermission()) {
                SUsageDataManager.getInstance(RemoteBlockService.this)
                        .uploadToFirebase(myDeviceId, null);
                // Cancel the notification
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null)
                    nm.cancel(2);
            }
        }, 10000); // 10 seconds
    }

    // Also add this method to manually trigger logout listener setup
    public void manualSetupLogoutListener() {
        Log.d(TAG, "ðŸ”§ Manual logout listener setup requested");
        setupLogoutListener();
    }

    private void refreshDeviceAppList() {
        Log.d(TAG, "Auto-refreshing device app list to Firebase");

        try {
            ChildConnectionManager connectionManager = new ChildConnectionManager(this);
            connectionManager.refreshDeviceAppList(myDeviceId, new ChildConnectionManager.OnConnectionListener() {
                @Override
                public void onSuccess(String parentUserId) {
                    Log.d(TAG, "Device app list refreshed successfully");
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Failed to refresh device app list: " + error);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing device app list: " + e.getMessage());
        }
    }

    /**
     * ðŸ”§ OEM COMPATIBILITY: Acquire wake lock based on OEM aggressiveness
     * This helps keep the service alive on MIUI, Vivo, OPPO devices
     */
    private void acquireOEMWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // Release existing wake lock if any
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }

                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Master2:RemoteBlockService");

                // Aggressive OEMs need longer wake lock
                long wakeLockDuration = oemManager.isAggressiveOEM() ? 24 * 60 * 60 * 1000L : // 24 hours for aggressive
                                                                                              // OEMs
                        10 * 60 * 60 * 1000L; // 10 hours for normal OEMs

                wakeLock.acquire(wakeLockDuration);
                Log.d(TAG, "ðŸ”‹ OEM Wake lock acquired for " + (wakeLockDuration / (60 * 60 * 1000)) + " hours");
                Log.d(TAG, "ðŸ”‹ OEM Type: " + oemManager.getOEMType().name() +
                        " (Aggressive: " + oemManager.isAggressiveOEM() + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "âŒ Failed to acquire OEM wake lock: " + e.getMessage());
        }
    }

    /**
     * ðŸ”§ OEM COMPATIBILITY: Release wake lock safely
     */
    private void releaseOEMWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "ðŸ”‹ OEM Wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "âŒ Error releasing wake lock: " + e.getMessage());
        }
    }

    private void stopLocationRequestListener() {
        if (locationRequestRef != null && locationRequestListener != null) {
            locationRequestRef.removeEventListener(locationRequestListener);
        }
        locationRequestListener = null;
        locationRequestRef = null;
        locationRequestInFlight = false;
    }
    private void stopLocationUpdates() {
        try {
            if (fusedLocationClient != null && locationCallback != null) {
                fusedLocationClient.removeLocationUpdates(locationCallback);
                Log.d(TAG, "ðŸ“ Stopped FusedLocationProviderClient updates");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping fused location updates: " + e.getMessage());
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm != null && nativeLocationListener != null) {
                lm.removeUpdates(nativeLocationListener);
                Log.d(TAG, "ðŸ“ Stopped native LocationManager updates");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping native location updates: " + e.getMessage());
        }
    }

    private void registerGpsProviderReceiver() {
        if (gpsProviderReceiver == null) {
            gpsProviderReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (LocationManager.PROVIDERS_CHANGED_ACTION.equals(intent.getAction())) {
                        Log.d(TAG, "ðŸ“ Location provider state changed (GPS toggle detected)");
                        // Re-evaluate location tracking state
                        startLocationUploads();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
            registerReceiver(gpsProviderReceiver, filter);
            Log.d(TAG, "âœ… Registered GPS provider change receiver");
        }
    }

    private void unregisterGpsProviderReceiver() {
        if (gpsProviderReceiver != null) {
            try {
                unregisterReceiver(gpsProviderReceiver);
                Log.d(TAG, "Unregistered GPS provider change receiver");
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister GPS provider change receiver: " + e.getMessage());
            }
            gpsProviderReceiver = null;
        }
    }

    private synchronized void updateDeviceIdAndListeners() {
        if (sessionManager == null) return;
        String currentDeviceId = sessionManager.getChildDeviceId();
        if (currentDeviceId == null || currentDeviceId.isEmpty()) {
            Log.d(TAG, "updateDeviceIdAndListeners: childDeviceId is empty, exiting early.");
            return;
        }

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.d(TAG, "updateDeviceIdAndListeners: Firebase user not authenticated yet. Skipping listener setup.");
            return;
        }

        boolean deviceIdChanged = !currentDeviceId.equals(myDeviceId);
        String currentConnectionId = sessionManager.getConnectionId();
        boolean connectionChanged = boundConnectionId == null
                ? currentConnectionId != null && !currentConnectionId.isEmpty()
                : !boundConnectionId.equals(currentConnectionId);
        boolean listenersMissing = blockPolicyListener == null
                || logoutListener == null
                || usageRefreshListener == null
                || locationRequestListener == null
                || studyModeListener == null;

        if (deviceIdChanged || connectionChanged || listenersMissing) {
            Log.d(TAG, "ðŸ”„ updateDeviceIdAndListeners: deviceIdChanged="
                    + deviceIdChanged + ", connectionChanged=" + connectionChanged
                    + ", listenersMissing=" + listenersMissing
                    + ". Re-binding listeners.");

            // 1. Remove all old listeners safely
            removeAllListeners();

            // 2. Update connection binding
            myDeviceId = currentDeviceId;
            boundConnectionId = currentConnectionId;

            // 3. Keep Synced on paths
            try {
                FirebaseDatabase database = FirebaseDatabase.getInstance();
                database.getReference("v2").child("device_status").child(myDeviceId).keepSynced(true);
            } catch (Exception e) {
                Log.e(TAG, "Error keeping references synced: " + e.getMessage());
            }

            // 4. Child device status is owned by PersistentConnectionService.
            PersistentConnectionService.startService(this);

            // 5. Register all listeners
            try {
                startListeningForBlockCommands();
                setupLogoutListener();
                setupUsageRefreshListener();
                setupV2CommandsListener();
                startLocationUploads();
                setupRealTimeBlockedAppsListener();
                setupStudyModeListener();
                setupAppInventoryChangeReceiver();
                Log.d(TAG, "âœ… All listeners dynamically re-bound to device ID: " + myDeviceId);
            } catch (Exception e) {
                Log.e(TAG, "Error registering listeners: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "updateDeviceIdAndListeners: listeners already active for device " + myDeviceId);
        }
    }

    private void setupRealTimeBlockedAppsListener() {
        if (myDeviceId == null || myDeviceId.isEmpty()) return;

        if (blockedAppsListener != null && blockedAppsListenerRef != null) {
            try { blockedAppsListenerRef.removeEventListener(blockedAppsListener); } catch (Exception ignored) {}
            blockedAppsListener = null;
        }

        blockedAppsListenerRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_policies")
                .child(myDeviceId)
                .child("blocked_apps");

        blockedAppsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (blockedAppsPrefs == null) {
                    blockedAppsPrefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                }
                SharedPreferences.Editor editor = blockedAppsPrefs.edit();
                editor.clear();
                if (snapshot.exists()) {
                    for (DataSnapshot child : snapshot.getChildren()) {
                        String originalPackageName = child.child("packageName").getValue(String.class);
                        Boolean isBlocked = child.child("blocked").getValue(Boolean.class);
                        if (originalPackageName != null && !originalPackageName.trim().isEmpty()) {
                            if (isBlocked != null && isBlocked) {
                                editor.putBoolean(originalPackageName, true);
                            }
                        }
                    }
                }
                editor.apply();
                Log.d(TAG, "ðŸ”„ Synced blocked apps down from Firebase to local prefs: " + snapshot.getChildrenCount());
                broadcastBlockedAppsUpdate(null);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to sync blocked apps down: " + error.getMessage());
            }
        };

        blockedAppsListenerRef.addValueEventListener(blockedAppsListener);
    }

    private void setupStudyModeListener() {
        if (myDeviceId == null || myDeviceId.isEmpty()) {
            return;
        }
        if (studyModeListener != null && studyModeListenerRef != null) {
            try {
                studyModeListenerRef.removeEventListener(studyModeListener);
            } catch (Exception ignored) {
            }
            studyModeListener = null;
        }
        if (studyModeHandler == null) {
            studyModeHandler = new Handler(Looper.getMainLooper());
        }

        studyModeListenerRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_modes")
                .child(myDeviceId)
                .child(StudyModeContract.MODE_ID);

        studyModeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                currentStudyModePolicy = StudyModePolicyRepository.fromSnapshot(snapshot);
                applyStudyModePolicyNow();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Study Mode listener cancelled: " + error.getMessage());
            }
        };
        studyModeListenerRef.addValueEventListener(studyModeListener);
    }

    private void applyStudyModePolicyNow() {
        applyStudyModePolicyNow(null);
    }

    private void applyStudyModePolicyNow(String changedPackage) {
        Set<String> desiredBlocks = new HashSet<>();
        StudyModePolicy policy = currentStudyModePolicy;
        boolean enabled = policy != null && policy.enabled;
        if (enabled && StudyModeScheduleEvaluator.isActiveNow(policy)) {
            Set<String> explicitBlocks = policy.getEffectiveBlockedPackages();
            for (String packageName : explicitBlocks) {
                if (packageName != null && packageName.contains(".")
                        && !AppBlockingPolicy.isUnblockable(packageName)
                        && !isStudyModeSessionAllowed(policy, packageName)) {
                    desiredBlocks.add(packageName);
                }
            }
            addStudyCategoryBlocks(policy, desiredBlocks, explicitBlocks);
        }

        boolean changed = rewriteStudyModeBlocks(desiredBlocks);
        if (changed) {
            broadcastBlockedAppsUpdate(changedPackage);
        }
        scheduleStudyModeTick(enabled);
    }

    private void setupAppInventoryChangeReceiver() {
        if (appInventoryChangeReceiver != null) {
            return;
        }
        appInventoryChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null
                        || !AppInstallUninstallReceiver.ACTION_APP_INVENTORY_CHANGED.equals(intent.getAction())) {
                    return;
                }
                String packageName = intent.getStringExtra(AppInstallUninstallReceiver.EXTRA_PACKAGE_NAME);
                if (currentStudyModePolicy != null) {
                    Log.d(TAG, "App inventory changed; refreshing Study Mode blocks for " + packageName);
                    applyStudyModePolicyNow(packageName);
                }
            }
        };
        IntentFilter filter = new IntentFilter(AppInstallUninstallReceiver.ACTION_APP_INVENTORY_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(appInventoryChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(appInventoryChangeReceiver, filter);
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not register app inventory Study Mode receiver: " + error.getMessage());
            appInventoryChangeReceiver = null;
        }
    }

    private void addStudyCategoryBlocks(StudyModePolicy policy, Set<String> desiredBlocks, Set<String> explicitBlocks) {
        if (policy == null || policy.categories == null || desiredBlocks == null) {
            return;
        }
        boolean social = isStudyCategoryEnabled(policy, StudyModeContract.CATEGORY_SOCIAL);
        boolean games = isStudyCategoryEnabled(policy, StudyModeContract.CATEGORY_GAMES);
        boolean entertainment = isStudyCategoryEnabled(policy, StudyModeContract.CATEGORY_ENTERTAINMENT);
        if (!social && !games && !entertainment
                && (explicitBlocks == null || explicitBlocks.isEmpty())) {
            return;
        }

        PackageManager packageManager = getPackageManager();
        List<ApplicationInfo> installedApps;
        try {
            installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Exception error) {
            Log.w(TAG, "Could not expand Study Mode categories", error);
            return;
        }

        for (ApplicationInfo appInfo : installedApps) {
            if (appInfo == null || appInfo.packageName == null) {
                continue;
            }
            String packageName = appInfo.packageName;
            if (packageName.equals(getPackageName()) || AppBlockingPolicy.isUnblockable(packageName)) {
                continue;
            }
            if (packageManager.getLaunchIntentForPackage(packageName) == null
                    && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }
            if (isStudyAllowedOverride(policy, packageName)) {
                continue;
            }
            if (isStudyModeSessionAllowed(policy, packageName)) {
                continue;
            }
            AppCategorizer.AppCategory category = AppCategorizer.getCategory(this, packageName);
            if (matchesPackageReference(explicitBlocks, packageName)
                    || matchesStudyCategory(category, social, games, entertainment)) {
                desiredBlocks.add(packageName);
            }
        }
    }

    private boolean isStudyModeSessionAllowed(StudyModePolicy policy, String packageName) {
        if (policy == null || packageName == null || policy.sessionAllowedPackages == null) {
            return false;
        }
        String sessionKey = StudyModeScheduleEvaluator.currentSessionKey(policy);
        if (sessionKey == null) {
            return false;
        }
        return sessionKey.equals(policy.sessionAllowedPackages.get(packageName))
                || sessionKey.equals(policy.sessionAllowedPackages.get(sanitizeAppKey(packageName)));
    }

    private boolean isStudyAllowedOverride(StudyModePolicy policy, String packageName) {
        return policy != null && policy.allowedOverrides != null && packageName != null
                && (Boolean.TRUE.equals(policy.allowedOverrides.get(packageName))
                || Boolean.TRUE.equals(policy.allowedOverrides.get(sanitizeAppKey(packageName))));
    }

    private boolean matchesPackageReference(Set<String> references, String packageName) {
        return references != null && packageName != null
                && (references.contains(packageName) || references.contains(sanitizeAppKey(packageName)));
    }

    private String sanitizeAppKey(String packageName) {
        return packageName != null
                ? packageName.replaceAll("[.#$\\[\\]/]", "_")
                : "";
    }
    private boolean isStudyCategoryEnabled(StudyModePolicy policy, String categoryId) {
        StudyModePolicy.CategorySelection selection = policy.categories.get(categoryId);
        return selection != null && selection.enabled;
    }

    private boolean matchesStudyCategory(AppCategorizer.AppCategory category,
            boolean social, boolean games, boolean entertainment) {
        if (category == null) {
            return false;
        }
        switch (category) {
            case SOCIAL:
            case COMMUNICATION:
                return social;
            case GAMES:
                return games;
            case ENTERTAINMENT:
                return entertainment;
            default:
                return false;
        }
    }

    private boolean rewriteStudyModeBlocks(Set<String> desiredBlocks) {
        SharedPreferences studyPrefs = getSharedPreferences(PREF_STUDY_MODE_BLOCKS, MODE_PRIVATE);
        Set<String> currentBlocks = readTruePrefs(studyPrefs);
        if (currentBlocks.equals(desiredBlocks)) {
            return false;
        }
        SharedPreferences.Editor editor = studyPrefs.edit().clear();
        for (String packageName : desiredBlocks) {
            editor.putBoolean(packageName, true);
        }
        editor.apply();
        Log.d(TAG, "Study Mode local blocks updated: " + desiredBlocks.size());
        return true;
    }

    private Set<String> readTruePrefs(SharedPreferences preferences) {
        Set<String> result = new HashSet<>();
        if (preferences == null) {
            return result;
        }
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getValue() instanceof Boolean && (Boolean) entry.getValue()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private void scheduleStudyModeTick(boolean enabled) {
        if (studyModeHandler == null) {
            studyModeHandler = new Handler(Looper.getMainLooper());
        }
        if (studyModeRunnable != null) {
            studyModeHandler.removeCallbacks(studyModeRunnable);
        }
        if (!enabled) {
            return;
        }
        long delayMs = nextStudyModeRefreshDelay(currentStudyModePolicy);
        studyModeRunnable = this::applyStudyModePolicyNow;
        studyModeHandler.postDelayed(studyModeRunnable, delayMs);
        Log.d(TAG, "Next Study Mode refresh in " + delayMs + "ms");
    }

    private long nextStudyModeRefreshDelay(StudyModePolicy policy) {
        long transitionDelay = StudyModeScheduleEvaluator.millisUntilNextTransition(
                policy, System.currentTimeMillis());
        if (transitionDelay >= 0L) {
            return Math.max(STUDY_MODE_MIN_TICK_MS,
                    Math.min(STUDY_MODE_FALLBACK_TICK_MS,
                            transitionDelay + STUDY_MODE_BOUNDARY_GRACE_MS));
        }
        return STUDY_MODE_FALLBACK_TICK_MS;
    }

    private void setupV2CommandsListener() {
        Log.d(TAG, "Setting up v2 commands listener for device: " + myDeviceId);

        if (v2CommandsListener != null && v2CommandsRef != null) {
            try {
                v2CommandsRef.removeEventListener(v2CommandsListener);
            } catch (Exception e) {
                Log.e(TAG, "setupV2CommandsListener: Error removing old listener: " + e.getMessage());
            }
            v2CommandsListener = null;
        }

        v2CommandsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("commands")
                .child(myDeviceId)
                .orderByChild("status")
                .equalTo("pending");

        v2CommandsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    return;
                }

                for (DataSnapshot commandSnapshot : dataSnapshot.getChildren()) {
                    try {
                        String commandId = commandSnapshot.getKey();
                        if (commandId == null || "usage_refresh".equals(commandId)) {
                            continue;
                        }

                        Map<String, Object> commandMap = (Map<String, Object>) commandSnapshot.getValue();
                        if (commandMap == null) {
                            continue;
                        }

                        String status = String.valueOf(commandMap.get("status"));
                        if (!"pending".equals(status)) {
                            continue;
                        }

                        String type = String.valueOf(commandMap.get("type"));
                        Log.d(TAG, "Received v2 assistant command: " + type + " id=" + commandId);

                        processV2AssistantCommand(commandId, type, commandMap, commandSnapshot.getRef());
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing v2 command: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "v2 commands listener cancelled: " + databaseError.getMessage());
            }
        };

        v2CommandsRef.addValueEventListener(v2CommandsListener);
        Log.d(TAG, "v2 commands listener setup complete");
    }

    private void processV2AssistantCommand(String commandId, String type, Map<String, Object> commandMap, DatabaseReference ref) {
        try {
            boolean applied = false;
            String errorMsg = null;

            if ("ASSISTANT_BLOCK_APP".equals(type) || "ASSISTANT_BLOCK_CATEGORY".equals(type)) {
                List<String> packages = (List<String>) commandMap.get("targetPackages");
                if (packages != null && !packages.isEmpty()) {
                    for (String pkg : packages) {
                        if (pkg != null && !pkg.trim().isEmpty()) {
                            if (AppBlockingPolicy.isUnblockable(pkg)) {
                                continue;
                            }
                            blockedAppsPrefs.edit().putBoolean(pkg, true).apply();
                            broadcastBlockedAppsUpdate(pkg);
                        }
                    }
                    syncBlockedAppsToFirebase();
                    applied = true;
                } else {
                    errorMsg = "No packages specified for block command.";
                }
            } else if ("ASSISTANT_UNBLOCK_APP".equals(type)) {
                List<String> packages = (List<String>) commandMap.get("targetPackages");
                if (packages != null && !packages.isEmpty()) {
                    for (String pkg : packages) {
                        if (pkg != null && !pkg.trim().isEmpty()) {
                            blockedAppsPrefs.edit().remove(pkg).apply();
                            broadcastBlockedAppsUpdate(pkg);
                        }
                    }
                    syncBlockedAppsToFirebase();
                    applied = true;
                } else {
                    errorMsg = "No packages specified for unblock command.";
                }
            } else if ("ASSISTANT_UNBLOCK_ALL_APPS".equals(type)) {
                // Clear every manually-blocked app in one shot
                blockedAppsPrefs.edit().clear().apply();
                syncBlockedAppsToFirebase();
                broadcastBlockedAppsUpdate(null);
                applied = true;
            } else if ("ASSISTANT_SET_APP_TIMER".equals(type)) {
                applied = true;
            } else if ("ASSISTANT_REMOVE_APP_TIMER".equals(type)) {
                applied = true;
            } else {
                applied = true; // Auto-ack other unknown/unhandled commands to prevent hanging
            }

            if (applied) {
                ref.child("status").setValue("APPLIED");
                ref.child("completedAt").setValue(System.currentTimeMillis());
                Log.d(TAG, "v2 assistant command marked as APPLIED: " + commandId);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { ref.removeValue(); } catch (Exception ignored) {}
                }, 15000);
            } else {
                ref.child("status").setValue("FAILED");
                ref.child("error").setValue(errorMsg != null ? errorMsg : "Command failed processing.");
                ref.child("failedAt").setValue(System.currentTimeMillis());
                Log.e(TAG, "v2 assistant command marked as FAILED: " + commandId + " error: " + errorMsg);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { ref.removeValue(); } catch (Exception ignored) {}
                }, 30000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed processing v2 command " + commandId + ": " + e.getMessage());
            ref.child("status").setValue("FAILED");
            ref.child("error").setValue(e.getMessage());
            ref.child("failedAt").setValue(System.currentTimeMillis());
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { ref.removeValue(); } catch (Exception ignored) {}
            }, 30000);
        }
    }

    private synchronized void removeAllListeners() {
        Log.d(TAG, "removeAllListeners: clearing all active listeners.");
        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance();
            if (blockPolicyListener != null && blockPoliciesRef != null) {
                blockPoliciesRef.removeEventListener(blockPolicyListener);
                blockPolicyListener = null;
            }
            if (logoutListener != null && logoutRef != null) {
                logoutRef.removeEventListener(logoutListener);
                logoutListener = null;
            }
            if (usageRefreshListener != null && usageRefreshRef != null) {
                usageRefreshRef.removeEventListener(usageRefreshListener);
                usageRefreshListener = null;
            }
            if (susageUpdateListener != null && susageRequestRef != null) {
                susageRequestRef.removeEventListener(susageUpdateListener);
                susageUpdateListener = null;
            }
            if (v2CommandsListener != null && v2CommandsRef != null) {
                v2CommandsRef.removeEventListener(v2CommandsListener);
                v2CommandsListener = null;
            }
            if (locationRequestListener != null && locationRequestRef != null) {
                locationRequestRef.removeEventListener(locationRequestListener);
                locationRequestListener = null;
            }
            if (blockedAppsListener != null && blockedAppsListenerRef != null) {
                blockedAppsListenerRef.removeEventListener(blockedAppsListener);
                blockedAppsListener = null;
            }
            if (studyModeListener != null && studyModeListenerRef != null) {
                studyModeListenerRef.removeEventListener(studyModeListener);
                studyModeListener = null;
            }
            if (studyModeHandler != null && studyModeRunnable != null) {
                studyModeHandler.removeCallbacks(studyModeRunnable);
                studyModeRunnable = null;
            }
            if (appInventoryChangeReceiver != null) {
                try {
                    unregisterReceiver(appInventoryChangeReceiver);
                } catch (Exception ignored) {
                }
                appInventoryChangeReceiver = null;
            }
            currentStudyModePolicy = null;
        } catch (Exception e) {
            Log.e(TAG, "Error in removeAllListeners: " + e.getMessage());
        }
    }

    /**
     * Pushes the complete map of manually-blocked packages to
     * /v2/device_policies/{deviceId}/blocked_apps so that the parent dashboard
     * and AssistantLiveStateRepository can always read the authoritative list
     * from Firebase rather than relying on the parent device's local cache.
     */
    private void syncBlockedAppsToFirebase() {
        // No-op: Only the parent writes to blocked_apps under device_policies to match rules
    }
}
