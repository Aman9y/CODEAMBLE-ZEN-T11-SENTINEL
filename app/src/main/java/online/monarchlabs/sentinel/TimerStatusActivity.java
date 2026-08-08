package online.monarchlabs.sentinel;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.data.ParentAppInventoryCache;

/**
 * Activity that displays all apps with active timers.
 * Shows green for time remaining, red for expired.
 * Works for both parent and child devices.
 */
public class TimerStatusActivity extends BaseActivity {
    private static final String TAG = "TimerStatusActivity";

    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_IS_PARENT = "is_parent";

    private RecyclerView rvTimerStatus;
    private LinearLayout emptyState;
    private LinearLayout loadingState;
    private TimerStatusAdapter adapter;
    private List<TimerAppInfo> timerApps = new ArrayList<>();
    private final Map<String, InstalledAppInfo> installedAppsByPackage = new HashMap<>();
    private final Map<String, Boolean> blockedStateByPackage = new HashMap<>();
    private final Map<String, TimerExecutionInfo> executionByPackage = new HashMap<>();

    private static final long TIMER_REFRESH_FRESHNESS_MS = 60_000L;
    private static final long INVENTORY_REVISION_CHECK_MS = 15 * 60_000L;

    private String deviceId;
    private String parentDeviceId;
    private String parentCacheScope;
    private boolean isParent;
    private boolean timerPoliciesLoaded;
    private boolean timerExecutionLoaded;
    private boolean refreshRequestedThisForeground;
    private boolean appLimitsCapabilityLoaded;
    private DatabaseReference timersRef;
    private ValueEventListener timersListener;
    private DatabaseReference blockPoliciesRef;
    private ValueEventListener blockPoliciesListener;
    private DatabaseReference timerExecutionRef;
    private ValueEventListener timerExecutionListener;
    // Real-time cloud truth for block states
    private DatabaseReference blockedAppsCloudRef;
    private ValueEventListener blockedAppsCloudListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timer_status);

        // Get intent extras
        deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        isParent = getIntent().getBooleanExtra(EXTRA_IS_PARENT, false);

        if (deviceId == null || deviceId.isEmpty()) {
            // Try to get from session
            SessionManager session = new SessionManager(this);
            deviceId = session.getChildDeviceId();
        }

        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "No device ID provided!");
            finish();
            return;
        }

        Log.d(TAG, "📱 Timer Status for device: " + deviceId);

        String androidId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        String parentUid = FirebaseAuth.getInstance().getUid();
        parentCacheScope = parentUid != null ? parentUid : "signed_out";
        parentDeviceId = androidId != null && !androidId.isEmpty()
                ? androidId
                : "parent_" + parentCacheScope;

        setupToolbar();
        setupRecyclerView();
        loadInventoryMetadata();
        loadCachedTimerExecution();
        loadAppLimitsCapability();
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("App Timer Status");
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void setupRecyclerView() {
        rvTimerStatus = findViewById(R.id.rvTimerStatus);
        emptyState = findViewById(R.id.emptyState);
        loadingState = findViewById(R.id.loadingState);
        TextView tvEmptyManageTimers = findViewById(R.id.tvEmptyManageTimers);
        tvEmptyManageTimers.setOnClickListener(v -> openTimerEditor());

        adapter = new TimerStatusAdapter(this, timerApps, isParent, new TimerActionListener() {
            @Override
            public void onBlockApp(TimerAppInfo app) {
                sendBlockCommand(app, !app.blocked);
            }
        }, this::openTimerEditor);
        rvTimerStatus.setLayoutManager(new LinearLayoutManager(this));
        rvTimerStatus.setAdapter(adapter);
    }

    private void setupFirebaseListener() {
        if (timersListener != null) {
            return;
        }
        timersRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_policies")
                .child(deviceId)
                .child("app_timers");

        timersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                timerApps.clear();
                for (DataSnapshot appSnapshot : snapshot.getChildren()) {
                    try {
                        String packageName = appSnapshot.child("packageName")
                                .getValue(String.class);
                        if (packageName == null || packageName.isEmpty()) {
                            packageName = appSnapshot.getKey();
                        }
                        if (packageName == null) {
                            continue;
                        }

                        Long dailyLimit = getSnapshotLong(
                                appSnapshot, "dailyLimitMillis");
                        if (dailyLimit == null) {
                            dailyLimit = getSnapshotLong(appSnapshot, "totalTimeMillis");
                        }
                        if (dailyLimit == null || dailyLimit <= 0L) {
                            continue;
                        }

                        Long policyRemaining = getSnapshotLong(
                                appSnapshot, "remainingTimeMillis");
                        long remaining = policyRemaining != null
                                ? policyRemaining : dailyLimit;
                        long exceed = 0L;
                        boolean expired = false;

                        TimerExecutionInfo execution =
                                executionByPackage.get(packageName);
                        if (execution != null) {
                            remaining = execution.remainingMs;
                            exceed = execution.exceedMs;
                            expired = execution.expired;
                        }

                        InstalledAppInfo metadata =
                                installedAppsByPackage.get(packageName);
                        String appName = metadata != null ? metadata.appName : null;
                        if (appName == null || appName.isEmpty()) {
                            appName = getAppName(packageName);
                        }
                        String iconBase64 = metadata != null
                                ? metadata.iconBase64 : null;

                        timerApps.add(new TimerAppInfo(
                                packageName,
                                appName,
                                iconBase64,
                                remaining,
                                dailyLimit,
                                exceed,
                                expired,
                                isAppBlocked(packageName)));
                    } catch (Exception error) {
                        Log.e(TAG, "Error parsing timer policy", error);
                    }
                }

                timerPoliciesLoaded = true;
                updateUI();
                maybeRequestLatestTimerState();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Timer policy listener error: " + error.getMessage());
                timerPoliciesLoaded = true;
                updateUI();
            }
        };
        timersRef.addValueEventListener(timersListener);
    }
    private void setupTimerExecutionListener() {
        if (timerExecutionListener != null) {
            return;
        }
        timerExecutionRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("timer_execution")
                .child(deviceId);
        timerExecutionListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                executionByPackage.clear();
                for (DataSnapshot timerSnapshot : snapshot.getChildren()) {
                    String packageName = timerSnapshot.child("packageName")
                            .getValue(String.class);
                    Long remaining = getSnapshotLong(
                            timerSnapshot, "remainingTimeMillis");
                    if (packageName == null || remaining == null) {
                        continue;
                    }
                    TimerExecutionInfo info = new TimerExecutionInfo();
                    info.remainingMs = remaining;
                    Long exceed = getSnapshotLong(timerSnapshot, "exceedTimeMillis");
                    Long evaluatedAt = getSnapshotLong(timerSnapshot, "evaluatedAt");
                    info.exceedMs = exceed != null ? exceed : 0L;
                    info.evaluatedAt = evaluatedAt != null ? evaluatedAt : 0L;
                    String state = timerSnapshot.child("state").getValue(String.class);
                    info.expired = "EXPIRED".equalsIgnoreCase(state);
                    executionByPackage.put(packageName, info);
                }
                timerExecutionLoaded = true;
                saveCachedTimerExecution();
                applyExecutionSnapshots();
                maybeRequestLatestTimerState();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Timer execution listener cancelled: " + error.getMessage());
            }
        };
        timerExecutionRef.addValueEventListener(timerExecutionListener);
    }
    private void applyExecutionSnapshots() {
        boolean changed = false;
        for (TimerAppInfo app : timerApps) {
            TimerExecutionInfo execution = executionByPackage.get(app.packageName);
            if (execution == null) {
                continue;
            }
            app.remainingMs = execution.remainingMs;
            app.exceedMs = execution.exceedMs;
            app.expired = execution.expired;
            changed = true;
        }
        if (changed) {
            updateUI();
        }
    }

    private void maybeRequestLatestTimerState() {
        if (!isParent || !appLimitsCapabilityLoaded
                || !timerPoliciesLoaded || !timerExecutionLoaded
                || refreshRequestedThisForeground || timerApps.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean stale = false;
        for (TimerAppInfo app : timerApps) {
            TimerExecutionInfo execution = executionByPackage.get(app.packageName);
            if (execution == null
                    || execution.evaluatedAt <= 0L
                    || now - execution.evaluatedAt > TIMER_REFRESH_FRESHNESS_MS) {
                stale = true;
                break;
            }
        }
        if (!stale) {
            return;
        }

        android.content.SharedPreferences requestPrefs = getSharedPreferences(
                "timer_refresh_requests_" + parentCacheScope, MODE_PRIVATE);
        String requestKey = deviceId + "_" + parentDeviceId;
        if (now - requestPrefs.getLong(requestKey, 0L)
                < TIMER_REFRESH_FRESHNESS_MS) {
            return;
        }

        refreshRequestedThisForeground = true;
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> request = new HashMap<>();
        request.put("requestId", requestId);
        request.put("requestedAt", now);
        request.put("requestedBy", "parent");
        request.put("status", "pending");

        DatabaseReference requestRoot = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("timer_state_requests")
                .child(deviceId);
        Task<Void> requestTask = requestRoot.child(parentDeviceId)
                .child("request").updateChildren(request);
        requestTask
                .addOnSuccessListener(ignored ->
                        requestPrefs.edit().putLong(requestKey, now).apply())
                .addOnFailureListener(error -> {
                    refreshRequestedThisForeground = false;
                    Log.w(TAG, "Could not request latest timer state: "
                            + error.getMessage());
                });
    }
    private void loadInventoryMetadata() {
        ParentAppInventoryCache.Entry cached = ParentAppInventoryCache.load(
                this, parentCacheScope, deviceId);
        if (cached != null) {
            mergeInventoryMap(cached.apps);
        }

        android.content.SharedPreferences metadataPrefs = getSharedPreferences(
                "timer_inventory_checks_" + parentCacheScope, MODE_PRIVATE);
        String checkedAtKey = deviceId + "_checked_at";
        long lastCheckedAt = metadataPrefs.getLong(checkedAtKey, 0L);
        if (cached != null
                && System.currentTimeMillis() - lastCheckedAt
                < INVENTORY_REVISION_CHECK_MS) {
            return;
        }

        FirebaseDatabase.getInstance().getReference("v2")
                .child("device_installs").child(deviceId).child("revisionId")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String revisionId = snapshot.getValue(String.class);
                        if (revisionId != null && cached != null
                                && revisionId.equals(cached.revisionId)) {
                            metadataPrefs.edit()
                                    .putLong(checkedAtKey, System.currentTimeMillis())
                                    .apply();
                            return;
                        }
                        fetchInventoryMetadata(revisionId, metadataPrefs, checkedAtKey);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        if (cached == null) {
                            Log.w(TAG, "v2 timer inventory revision unavailable: " + error.getMessage());
                        }
                    }
                });
    }

    private void fetchInventoryMetadata(String revisionId,
            android.content.SharedPreferences metadataPrefs, String checkedAtKey) {
        FirebaseDatabase.getInstance().getReference("v2")
                .child("device_installs").child(deviceId).child("apps")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Object rawApps = snapshot.getValue();
                        if (!(rawApps instanceof Map) || !snapshot.hasChildren()) {
                            Log.d(TAG, "No v2 timer inventory is available yet");
                            return;
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> apps = (Map<String, Object>) rawApps;
                        mergeInventoryMap(apps);
                        ParentAppInventoryCache.save(
                                TimerStatusActivity.this,
                                parentCacheScope,
                                deviceId,
                                revisionId != null ? revisionId : "",
                                apps);
                        metadataPrefs.edit()
                                .putLong(checkedAtKey, System.currentTimeMillis())
                                .apply();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.w(TAG, "v2 timer inventory unavailable: " + error.getMessage());
                    }
                });
    }

    private void mergeInventoryMap(Map<String, Object> apps) {
        for (Object rawApp : apps.values()) {
            if (!(rawApp instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) rawApp;
            Object rawPackage = app.get("packageName");
            if (!(rawPackage instanceof String)) {
                continue;
            }
            String packageName = (String) rawPackage;
            String appName = app.get("appName") instanceof String
                    ? (String) app.get("appName") : null;
            if (appName == null && app.get("name") instanceof String) {
                appName = (String) app.get("name");
            }
            String icon = app.get("iconBase64") instanceof String
                    ? (String) app.get("iconBase64") : null;
            installedAppsByPackage.put(
                    packageName, new InstalledAppInfo(appName, icon));
        }
        refreshTimerAppMetadata();
    }

    private void loadCachedTimerExecution() {
        android.content.SharedPreferences cache = getSharedPreferences(
                "timer_execution_cache_" + parentCacheScope + "_" + deviceId,
                MODE_PRIVATE);
        Set<String> packages = cache.getStringSet("packages", new HashSet<>());
        for (String packageName : packages) {
            String key = safeAppKey(packageName);
            TimerExecutionInfo info = new TimerExecutionInfo();
            info.remainingMs = cache.getLong(key + "_remaining", 0L);
            info.exceedMs = cache.getLong(key + "_exceed", 0L);
            info.evaluatedAt = cache.getLong(key + "_evaluated", 0L);
            info.expired = cache.getBoolean(key + "_expired", false);
            executionByPackage.put(packageName, info);
        }
    }

    private void saveCachedTimerExecution() {
        android.content.SharedPreferences.Editor editor = getSharedPreferences(
                "timer_execution_cache_" + parentCacheScope + "_" + deviceId,
                MODE_PRIVATE).edit().clear();
        Set<String> packages = new HashSet<>(executionByPackage.keySet());
        editor.putStringSet("packages", packages);
        for (Map.Entry<String, TimerExecutionInfo> entry
                : executionByPackage.entrySet()) {
            String key = safeAppKey(entry.getKey());
            TimerExecutionInfo info = entry.getValue();
            editor.putLong(key + "_remaining", info.remainingMs);
            editor.putLong(key + "_exceed", info.exceedMs);
            editor.putLong(key + "_evaluated", info.evaluatedAt);
            editor.putBoolean(key + "_expired", info.expired);
        }
        editor.apply();
    }

    private Long getSnapshotLong(DataSnapshot snapshot, String field) {
        Object value = snapshot.child(field).getValue();
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private String safeAppKey(String packageName) {
        return packageName.replace(".", "_").replace("#", "_")
                .replace("$", "_").replace("[", "_")
                .replace("]", "_").replace("/", "_");
    }
    private void loadAppLimitsCapability() {
        appLimitsCapabilityLoaded = true;
        maybeRequestLatestTimerState();
    }
    private void setupBlockStatusListener() {
        if (blockPoliciesListener != null) {
            return;
        }
        blockPoliciesRef = FirebaseDatabase.getInstance().getReference("v2")
                .child("device_policies").child(deviceId).child("blocked_apps");
        blockPoliciesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                blockedStateByPackage.clear();
                android.content.SharedPreferences.Editor editor =
                        getSharedPreferences("blocked_apps_" + deviceId, MODE_PRIVATE).edit().clear();

                for (DataSnapshot policy : snapshot.getChildren()) {
                    String packageName = policy.child("packageName")
                            .getValue(String.class);
                    Boolean blocked = policy.child("blocked").getValue(Boolean.class);
                    if (packageName == null || blocked == null) {
                        continue;
                    }
                    blockedStateByPackage.put(packageName, blocked);
                    if (blocked && !AppBlockingPolicy.isUnblockable(packageName)) {
                        editor.putBoolean(packageName, true);
                    }
                }
                editor.apply();
                refreshBlockedStates();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Block policy listener error: " + error.getMessage());
            }
        };
        blockPoliciesRef.addValueEventListener(blockPoliciesListener);
    }

    private void updateUI() {
        if (!timerPoliciesLoaded) {
            if (loadingState != null) {
                loadingState.setVisibility(View.VISIBLE);
            }
            rvTimerStatus.setVisibility(View.GONE);
            emptyState.setVisibility(View.GONE);
            return;
        }

        if (loadingState != null) {
            loadingState.setVisibility(View.GONE);
        }

        Collections.sort(timerApps, new Comparator<TimerAppInfo>() {
            @Override
            public int compare(TimerAppInfo first, TimerAppInfo second) {
                if (first.expired != second.expired) {
                    return first.expired ? -1 : 1;
                }
                if (first.expired) {
                    return Long.compare(second.exceedMs, first.exceedMs);
                }
                return Long.compare(first.remainingMs, second.remainingMs);
            }
        });

        int exceededCount = 0;
        for (TimerAppInfo app : timerApps) {
            if (app.expired) {
                exceededCount++;
            }
        }

        adapter.setExceededCount(exceededCount);

        if (timerApps.isEmpty()) {
            rvTimerStatus.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            rvTimerStatus.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        }
        adapter.notifyDataSetChanged();
    }
    private void openTimerEditor() {
        Intent intent = new Intent(TimerStatusActivity.this, ChildInstalledAppsActivity.class);
        intent.putExtra(ChildInstalledAppsActivity.EXTRA_CHILD_DEVICE_ID, deviceId);
        intent.putExtra(ChildInstalledAppsActivity.EXTRA_CHILD_NAME, (String) null);
        intent.putExtra(ChildInstalledAppsActivity.EXTRA_IS_PARENT_CONTEXT, isParent);
        intent.putExtra(ChildInstalledAppsActivity.EXTRA_RETURN_TO_TIMER_STATUS, true);
        startActivity(intent);
    }

    public void deleteTimer(String packageName) {
        if (timersRef == null) {
            timersRef = FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("device_policies")
                    .child(deviceId)
                    .child("app_timers");
        }
        String safeKey = packageName.replaceAll("[.#$\\[\\]/]", "_");
        timersRef.child(safeKey).removeValue()
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Timer deleted successfully", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Timer deleted for " + packageName);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to delete timer: " + e.getMessage());
                    Toast.makeText(this, "Failed to delete timer", Toast.LENGTH_SHORT).show();
                });
    }

    private void sendBlockCommand(TimerAppInfo app, boolean shouldBlock) {
        String policyId = UUID.randomUUID().toString();
        Map<String, Object> policy = new HashMap<>();
        policy.put("policyId", policyId);
        policy.put("packageName", app.packageName);
        policy.put("appName", app.appName);
        policy.put("blocked", shouldBlock);
        policy.put("enforcementMode", "IMMEDIATE");
        policy.put("delayDurationMs", 0L);
        policy.put("updatedAt", ServerValue.TIMESTAMP);

        FirebaseSchemaV2Repository.syncAppBlockPolicy(
                        deviceId, safeAppKey(app.packageName), policy)
                .addOnSuccessListener(ignored ->
                        applyRequestedBlockState(app, shouldBlock))
                .addOnFailureListener(error ->
                        Toast.makeText(
                                this,
                                "Could not " + (shouldBlock ? "block " : "unblock ")
                                        + app.appName,
                                Toast.LENGTH_SHORT).show());
    }

    private void applyRequestedBlockState(TimerAppInfo app, boolean shouldBlock) {
        blockedStateByPackage.put(app.packageName, shouldBlock);
        cacheBlockStatus(app.packageName, shouldBlock);
        app.blocked = shouldBlock;
        adapter.notifyDataSetChanged();
        Toast.makeText(
                this,
                app.appName + (shouldBlock ? " block requested" : " unblock requested"),
                Toast.LENGTH_SHORT).show();
    }
    private boolean isAppBlocked(String packageName) {
        Boolean firebaseState = blockedStateByPackage.get(packageName);
        if (firebaseState != null) {
            return firebaseState;
        }
        return getSharedPreferences("blocked_apps_" + deviceId, MODE_PRIVATE)
                .getBoolean(packageName, false);
    }

    private void cacheBlockStatus(String packageName, boolean isBlocked) {
        getSharedPreferences("blocked_apps_" + deviceId, MODE_PRIVATE)
                .edit()
                .putBoolean(packageName, isBlocked)
                .apply();
    }

    private void refreshBlockedStates() {
        boolean changed = false;
        for (TimerAppInfo app : timerApps) {
            boolean blocked = isAppBlocked(app.packageName);
            if (app.blocked != blocked) {
                app.blocked = blocked;
                changed = true;
            }
        }
        if (changed && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private void refreshTimerAppIcons() {
        refreshTimerAppMetadata();
    }

    private void refreshTimerAppMetadata() {
        boolean changed = false;
        for (TimerAppInfo app : timerApps) {
            InstalledAppInfo metadata = installedAppsByPackage.get(app.packageName);
            if (metadata != null) {
                if (metadata.iconBase64 != null
                        && !metadata.iconBase64.isEmpty()
                        && !metadata.iconBase64.equals(app.iconBase64)) {
                    app.iconBase64 = metadata.iconBase64;
                    changed = true;
                }
                if (metadata.appName != null
                        && !metadata.appName.isEmpty()
                        && !metadata.appName.equals(app.appName)) {
                    app.appName = metadata.appName;
                    changed = true;
                }
            }
        }
        if (changed && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private Drawable getAppIcon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (Exception e) {
            return getDrawable(R.drawable.ic_app);
        }
    }

    private String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format(Locale.getDefault(), "%dm", minutes);
        } else {
            return "<1m";
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshRequestedThisForeground = false;
        timerPoliciesLoaded = false;
        timerExecutionLoaded = false;
        updateUI();
        setupFirebaseListener();
        setupTimerExecutionListener();
        setupBlockStatusListener();
    }

    @Override
    protected void onStop() {
        if (timersRef != null && timersListener != null) {
            timersRef.removeEventListener(timersListener);
        }
        if (timerExecutionRef != null && timerExecutionListener != null) {
            timerExecutionRef.removeEventListener(timerExecutionListener);
        }
        if (blockPoliciesRef != null && blockPoliciesListener != null) {
            blockPoliciesRef.removeEventListener(blockPoliciesListener);
        }
        timersRef = null;
        timersListener = null;
        timerExecutionRef = null;
        timerExecutionListener = null;
        blockPoliciesRef = null;
        blockPoliciesListener = null;
        timerPoliciesLoaded = false;
        timerExecutionLoaded = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
    // ========== DATA CLASS ==========

    private static class InstalledAppInfo {
        String appName;
        String iconBase64;

        InstalledAppInfo(String appName, String iconBase64) {
            this.appName = appName;
            this.iconBase64 = iconBase64;
        }
    }

    private static class TimerExecutionInfo {
        long remainingMs;
        long exceedMs;
        long evaluatedAt;
        boolean expired;
    }

    public static class TimerAppInfo {
        public String packageName;
        public String appName;
        public String iconBase64;
        public long remainingMs;
        public long dailyLimitMs;
        public long exceedMs;
        public boolean expired;
        public boolean blocked;
        public boolean isExpanded;

        public TimerAppInfo(String packageName, String appName, String iconBase64,
                long remainingMs, long dailyLimitMs, long exceedMs, boolean expired,
                boolean blocked) {
            this.packageName = packageName;
            this.appName = appName;
            this.iconBase64 = iconBase64;
            this.remainingMs = remainingMs;
            this.dailyLimitMs = dailyLimitMs;
            this.exceedMs = exceedMs;
            this.expired = expired;
            this.blocked = blocked;
            this.isExpanded = false;
        }

        public int getProgressPercent() {
            if (dailyLimitMs <= 0)
                return 0;
            return (int) ((remainingMs * 100) / dailyLimitMs);
        }
    }

    // ========== ADAPTER ==========

    private interface TimerActionListener {
        void onBlockApp(TimerAppInfo app);
    }

    private static class TimerStatusAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_ACTIVE = 0;
        private static final int VIEW_TYPE_EXPIRED = 1;
        private static final int VIEW_TYPE_HEADER = 2;
        private Context context;
        private List<TimerAppInfo> apps;
        private boolean showParentActions;
        private TimerActionListener actionListener;
        private Runnable manageTimersAction;
        private int exceededCount;

        public TimerStatusAdapter(Context context, List<TimerAppInfo> apps,
                boolean showParentActions,
                TimerActionListener actionListener,
                Runnable manageTimersAction) {
            this.context = context;
            this.apps = apps;
            this.showParentActions = showParentActions;
            this.actionListener = actionListener;
            this.manageTimersAction = manageTimersAction;
        }

        void setExceededCount(int exceededCount) {
            this.exceededCount = exceededCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_HEADER;
            }
            return apps.get(position - 1).expired ? VIEW_TYPE_EXPIRED : VIEW_TYPE_ACTIVE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_HEADER) {
                View header = LayoutInflater.from(context)
                        .inflate(R.layout.item_timer_status_header, parent, false);
                return new HeaderViewHolder(header);
            }
            View view = LayoutInflater.from(context)
                    .inflate(R.layout.item_timer_status, parent, false);
            return new TimerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderViewHolder) {
                HeaderViewHolder header = (HeaderViewHolder) holder;
                header.exceededSummary.setVisibility(
                        exceededCount > 0 ? View.VISIBLE : View.GONE);
                header.tvExceededSummaryTitle.setText(exceededCount == 1
                        ? "1 App Exceeded Limit"
                        : exceededCount + " Apps Exceeded Limit");
                header.tvSetTimersPrompt.setOnClickListener(v -> manageTimersAction.run());
                return;
            }

            TimerViewHolder timerHolder = (TimerViewHolder) holder;
            int appPosition = position - 1;
            TimerAppInfo app = apps.get(appPosition);

            // Set app info
            timerHolder.tvAppName.setText(app.appName);
            loadAppIcon(timerHolder.ivAppIcon, app);

            // Hide/Show section header (if active)
            if (timerHolder.tvSectionHeader != null) {
                if (app.expired) {
                    timerHolder.tvSectionHeader.setVisibility(View.GONE);
                } else {
                    boolean firstActive = appPosition == 0 || apps.get(appPosition - 1).expired;
                    timerHolder.tvSectionHeader.setText(appPosition == 0
                            ? "ACTIVE TIMERS"
                            : "OTHER ACTIVE TIMERS");
                    timerHolder.tvSectionHeader.setVisibility(firstActive ? View.VISIBLE : View.GONE);
                }
            }

            // Set Action layout visibility based on showParentActions and expanded state
            if (timerHolder.layoutActions != null) {
                if (showParentActions) {
                    timerHolder.layoutActions.setVisibility(app.isExpanded ? View.VISIBLE : View.GONE);
                } else {
                    timerHolder.layoutActions.setVisibility(View.GONE);
                }
            }

            // Make the card clickable to expand/collapse actions
            if (showParentActions && timerHolder.rowContainer != null) {
                timerHolder.rowContainer.setOnClickListener(v -> {
                    app.isExpanded = !app.isExpanded;
                    notifyItemChanged(position);
                });
            } else if (timerHolder.rowContainer != null) {
                timerHolder.rowContainer.setOnClickListener(null);
                timerHolder.rowContainer.setClickable(false);
            }

            if (app.expired) {
                // EXPIRED STATE (Red Theme)
                if (timerHolder.tvSubtitleLimit != null) {
                    timerHolder.tvSubtitleLimit.setText("TIME LIMIT REACHED");
                    timerHolder.tvSubtitleLimit.setTextColor(context.getColor(R.color.error_600));
                }

                if (timerHolder.statusIndicator != null) {
                    timerHolder.statusIndicator.setImageResource(R.drawable.ic_warning);
                    timerHolder.statusIndicator.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.error_600)));
                }

                if (timerHolder.tvExpiredBadge != null) {
                    timerHolder.tvExpiredBadge.setText("Expired");
                    timerHolder.tvExpiredBadge.setTextColor(context.getColor(R.color.error_600));
                }

                if (timerHolder.tvThirdHeaderLabel != null) {
                    timerHolder.tvThirdHeaderLabel.setText("EXCEEDED");
                    timerHolder.tvThirdHeaderLabel.setTextColor(context.getColor(R.color.error_600));
                }

                long displayExceed = app.exceedMs > 0 ? app.exceedMs : 0;
                if (timerHolder.tvTimeRemaining != null) {
                    timerHolder.tvTimeRemaining.setText(displayExceed > 0 ? formatTime(displayExceed) : "0m");
                    timerHolder.tvTimeRemaining.setTextColor(context.getColor(R.color.error_600));
                }

                if (timerHolder.tvUsedTime != null) {
                    timerHolder.tvUsedTime.setText(formatTime(app.dailyLimitMs + displayExceed));
                }

                if (timerHolder.tvDailyLimit != null) {
                    timerHolder.tvDailyLimit.setText(formatTime(app.dailyLimitMs));
                }

                if (timerHolder.progressTimer != null) {
                    timerHolder.progressTimer.setProgressDrawable(context.getDrawable(R.drawable.progress_timer_expired));
                    timerHolder.progressTimer.setProgress(100);
                }

                if (timerHolder.tvStatusLabel != null) {
                    timerHolder.tvStatusLabel.setText("LIMIT REACHED");
                    timerHolder.tvStatusLabel.setTextColor(context.getColor(R.color.error_600));
                }
            } else {
                // ACTIVE STATE (Green Theme)
                if (timerHolder.tvSubtitleLimit != null) {
                    timerHolder.tvSubtitleLimit.setText("TIME LIMIT ACTIVE");
                    timerHolder.tvSubtitleLimit.setTextColor(context.getColor(R.color.success_600));
                }

                if (timerHolder.statusIndicator != null) {
                    timerHolder.statusIndicator.setImageResource(R.drawable.ic_shield_time);
                    timerHolder.statusIndicator.setImageTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.success_600)));
                }

                if (timerHolder.tvExpiredBadge != null) {
                    timerHolder.tvExpiredBadge.setText("Active");
                    timerHolder.tvExpiredBadge.setTextColor(context.getColor(R.color.success_600));
                }

                if (timerHolder.tvThirdHeaderLabel != null) {
                    timerHolder.tvThirdHeaderLabel.setText("REMAINING");
                    timerHolder.tvThirdHeaderLabel.setTextColor(context.getColor(R.color.neutral_500));
                }

                if (timerHolder.tvTimeRemaining != null) {
                    timerHolder.tvTimeRemaining.setText(formatTime(app.remainingMs));
                    timerHolder.tvTimeRemaining.setTextColor(context.getColor(R.color.success_600));
                }

                long usedMs = app.dailyLimitMs - app.remainingMs;
                if (usedMs < 0) usedMs = 0;
                if (timerHolder.tvUsedTime != null) {
                    timerHolder.tvUsedTime.setText(formatTime(usedMs));
                }

                if (timerHolder.tvDailyLimit != null) {
                    timerHolder.tvDailyLimit.setText(formatTime(app.dailyLimitMs));
                }

                if (timerHolder.progressTimer != null) {
                    timerHolder.progressTimer.setProgressDrawable(context.getDrawable(R.drawable.progress_timer));
                    timerHolder.progressTimer.setProgress(app.getProgressPercent());
                }

                if (timerHolder.tvStatusLabel != null) {
                    timerHolder.tvStatusLabel.setText("TIME REMAINING");
                    timerHolder.tvStatusLabel.setTextColor(context.getColor(R.color.success_600));
                }
            }

            // Set Action Button listeners
            if (showParentActions) {
                if (timerHolder.btnDeleteTimer != null) {
                    timerHolder.btnDeleteTimer.setOnClickListener(v -> {
                        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(context);
                        builder.setTitle("Delete Timer");
                        builder.setMessage("Are you sure you want to delete the timer for " + app.appName + "?");
                        builder.setPositiveButton("Delete", (dialog, which) -> {
                            if (context instanceof TimerStatusActivity) {
                                ((TimerStatusActivity) context).deleteTimer(app.packageName);
                            }
                        });
                        builder.setNegativeButton("Cancel", null);
                        builder.show();
                    });
                }

                if (timerHolder.btnBlockApp != null) {
                    timerHolder.btnBlockApp.setText(app.blocked ? "Unblock App" : "Block App");
                    timerHolder.btnBlockApp.setTextColor(context.getColor(
                            app.blocked ? R.color.modern_green_700 : R.color.error_600));
                    timerHolder.btnBlockApp.setBackgroundResource(
                            app.blocked
                                    ? R.drawable.bg_timer_unblock_action
                                    : R.drawable.bg_timer_danger_action);
                    timerHolder.btnBlockApp.setOnClickListener(v -> {
                        if (actionListener != null) {
                            actionListener.onBlockApp(app);
                        }
                    });
                }
            }
        }

        @Override
        public int getItemCount() {
            return apps.size() + 1;
        }

        private String formatTime(long millis) {
            long totalSeconds = millis / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;

            if (hours > 0) {
                return String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
            } else if (minutes > 0) {
                return String.format(Locale.getDefault(), "%dm", minutes);
            } else {
                return "<1m";
            }
        }

        private void loadAppIcon(ImageView imageView, TimerAppInfo app) {
            if (app.iconBase64 != null && !app.iconBase64.isEmpty()) {
                try {
                    byte[] decodedBytes = Base64.decode(app.iconBase64, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to decode app icon for " + app.packageName + ": " + e.getMessage());
                }
            }

            try {
                Drawable icon = context.getPackageManager().getApplicationIcon(app.packageName);
                imageView.setImageDrawable(icon);
            } catch (Exception e) {
                imageView.setImageResource(R.drawable.ic_app);
            }
        }

        static class HeaderViewHolder extends RecyclerView.ViewHolder {
            LinearLayout exceededSummary;
            TextView tvExceededSummaryTitle;
            TextView tvSetTimersPrompt;

            HeaderViewHolder(View itemView) {
                super(itemView);
                exceededSummary = itemView.findViewById(R.id.exceededSummary);
                tvExceededSummaryTitle = itemView.findViewById(R.id.tvExceededSummaryTitle);
                tvSetTimersPrompt = itemView.findViewById(R.id.tvSetTimersPrompt);
            }
        }

        static class TimerViewHolder extends RecyclerView.ViewHolder {
            LinearLayout rowContainer;
            ImageView ivAppIcon;
            TextView tvAppName;
            TextView tvSubtitleLimit;
            TextView tvExpiredBadge;
            TextView tvDailyLimit;
            ProgressBar progressTimer;
            ImageView statusIndicator;
            TextView tvTimeRemaining;
            TextView tvStatusLabel;
            TextView tvSectionHeader;
            TextView tvUsedTime;
            TextView tvThirdHeaderLabel;
            Button btnDeleteTimer;
            Button btnBlockApp;
            LinearLayout layoutActions;

            TimerViewHolder(View itemView) {
                super(itemView);
                rowContainer = itemView.findViewById(R.id.timerRowContainer);
                ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvSubtitleLimit = itemView.findViewById(R.id.tvSubtitleLimit);
                tvExpiredBadge = itemView.findViewById(R.id.tvExpiredBadge);
                tvDailyLimit = itemView.findViewById(R.id.tvDailyLimit);
                progressTimer = itemView.findViewById(R.id.progressTimer);
                statusIndicator = itemView.findViewById(R.id.statusIndicator);
                tvTimeRemaining = itemView.findViewById(R.id.tvTimeRemaining);
                tvStatusLabel = itemView.findViewById(R.id.tvStatusLabel);
                tvSectionHeader = itemView.findViewById(R.id.tvSectionHeader);
                tvUsedTime = itemView.findViewById(R.id.tvUsedTime);
                tvThirdHeaderLabel = itemView.findViewById(R.id.tvThirdHeaderLabel);
                btnDeleteTimer = itemView.findViewById(R.id.btnDeleteTimer);
                btnBlockApp = itemView.findViewById(R.id.btnBlockApp);
                layoutActions = itemView.findViewById(R.id.layoutActions);
            }
        }
    }
}
