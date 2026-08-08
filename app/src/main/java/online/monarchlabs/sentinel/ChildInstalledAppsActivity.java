package online.monarchlabs.sentinel;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageButton;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SwitchCompat;


import online.monarchlabs.sentinel.models.AppWithUsage;
import online.monarchlabs.sentinel.models.StudyModePolicy;
import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;
import online.monarchlabs.sentinel.data.StudyModeContract;
import online.monarchlabs.sentinel.data.StudyModePolicyRepository;
import online.monarchlabs.sentinel.data.ParentAppInventoryCache;
import online.monarchlabs.sentinel.utils.AppCategorizer;
import online.monarchlabs.sentinel.utils.StudyModeScheduleEvaluator;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity to show all installed apps on child device with per-app timer
 * feature.
 */
public class ChildInstalledAppsActivity extends BaseActivity {
    private static final String TAG = "ChildInstalledApps";
    public static final String EXTRA_CHILD_DEVICE_ID = "childDeviceId";
    public static final String EXTRA_CHILD_NAME = "childName";
    public static final String EXTRA_IS_PARENT_CONTEXT = "is_parent_context";
    public static final String EXTRA_RETURN_TO_TIMER_STATUS = "return_to_timer_status";
    private static final long STUDY_MODE_UI_MIN_REFRESH_MS = 1_000L;
    private static final long STUDY_MODE_UI_BOUNDARY_GRACE_MS = 750L;
    private static final long STUDY_MODE_UI_FALLBACK_REFRESH_MS = 15 * 60_000L;

    private String childDeviceId;
    private String childName;
    private boolean isParentContext;
    private boolean returnToTimerStatus;

    private RecyclerView rvApps;

    private View loadingOverlay;
    private View appListSkeleton;
    private TextView tvEmpty;
    private ImageView btnBack;
    private TextView tvTimerStatusPrompt;

    private ImageButton btnClearSearch;

    private EnhancedAppsAdapter adapter;
    private List<Object> displayList = new ArrayList<>(); // Mixed: headers, AppWithUsage
    private List<AppWithUsage> allAppsWithUsage = new ArrayList<>();
    private Map<String, Long> usageDataMap = new HashMap<>(); // package -> usage time ms
    private Map<String, AppTimerData> appTimers = new HashMap<>();
    private final LruCache<String, Bitmap> iconCache = new LruCache<>(80);
    private final ExecutorService iconDecodeExecutor = Executors.newFixedThreadPool(2);
    private final Handler displayRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable displayRefreshRunnable = this::rebuildDisplayListNow;

    // 🚀 CACHE: Store usage data locally for instant loading
    private SharedPreferences usageCache;


    // 🔍 Search and filter
    private EditText etSearch;
    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    private ValueEventListener inventoryRevisionListener;
    private ValueEventListener timersListener;
    private ChildEventListener usageListener;
    private boolean usageListenerAttached;
    private DatabaseReference canonicalAppsRef;
    private DatabaseReference inventoryRevisionRef;
    private DatabaseReference timersRef;
    private DatabaseReference usageRef;
    private DatabaseReference blockPoliciesRef;
    private ChildEventListener blockPolicyListener;
    private DatabaseReference studyModeRef;
    private ValueEventListener studyModeListener;
    private StudyModePolicy activeStudyModePolicy;
    private String activeStudyModeSessionKey;
    private final java.util.Set<String> activeStudyModeBlocks = new java.util.HashSet<>();
    private boolean useV2BlockPolicies = true;
    private String parentCacheScope;
    private ParentAppInventoryCache.Entry inventoryCacheEntry;
    private boolean initialInventoryReady;
    private boolean initialUsageReady;
    private final Runnable initialUsageFallbackRunnable = () -> {
        initialUsageReady = true;
        updateInitialContentVisibility();
    };
    private final Runnable usageReadyAfterFirstBatchRunnable = () -> {
        initialUsageReady = true;
        updateInitialContentVisibility();
    };
    private final Runnable studyModeUiRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeStudyModePolicy != null) {
                java.util.Set<String> previousBlocks = new java.util.HashSet<>(activeStudyModeBlocks);
                String previousSessionKey = activeStudyModeSessionKey;
                refreshStudyModeState();
                if (!previousBlocks.equals(activeStudyModeBlocks)
                        || !java.util.Objects.equals(previousSessionKey, activeStudyModeSessionKey)) {
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                }
            }
            scheduleStudyModeUiRefresh();
        }
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_installed_apps);

        childDeviceId = getIntent().getStringExtra(EXTRA_CHILD_DEVICE_ID);
        childName = getIntent().getStringExtra(EXTRA_CHILD_NAME); // This might be device name
        isParentContext = getIntent().getBooleanExtra(EXTRA_IS_PARENT_CONTEXT, true);
        returnToTimerStatus = getIntent().getBooleanExtra(EXTRA_RETURN_TO_TIMER_STATUS, false);

        if (childDeviceId == null) {
            Toast.makeText(this, "Error: No device selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Select v2 current-state App Limits when the child supports it.
        loadAppLimitsCapability();

        // 🚀 CACHE: Initialize usage cache
        com.google.firebase.auth.FirebaseUser parentUser =
                FirebaseAuth.getInstance().getCurrentUser();
        parentCacheScope = parentUser != null ? parentUser.getUid() : "signed_out";
        usageCache = getSharedPreferences(
                "usage_cache_" + parentCacheScope + "_" + childDeviceId,
                MODE_PRIVATE);

        // 🚀 CACHE: Load cached usage data IMMEDIATELY for instant display
        loadCachedUsageData();

        initViews();
        loadChildNameFromFirebase(); // 🔧 Load actual child name
        setupRecyclerView();
        loadCachedInventory();
        setupClickListeners();

    }

    /**
     * 🔧 Load actual child name (userName) from Firebase
     */
    private void loadChildNameFromFirebase() {
        String parentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        if (parentUserId == null)
            return;

        DatabaseReference deviceRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("devices")
                .child(childDeviceId);

        deviceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String userName = snapshot.child("userName").getValue(String.class);
                    if (userName != null && !userName.isEmpty()) {
                        childName = userName; // Update with actual child name
                        TextView tvChildName = findViewById(R.id.tvChildName);
                        if (tvChildName != null) {
                            tvChildName.setText(childName);
                        }
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w(TAG, "Failed to load child name: " + error.getMessage());
            }
        });
    }

    private void initViews() {
        rvApps = findViewById(R.id.rvInstalledApps);

        loadingOverlay = findViewById(R.id.loadingOverlay);
        appListSkeleton = findViewById(R.id.appListSkeleton);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnBack = findViewById(R.id.btnBack);
        tvTimerStatusPrompt = findViewById(R.id.tvTimerStatusPrompt);

        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);

        TextView tvChildName = findViewById(R.id.tvChildName);
        if (tvChildName != null && childName != null) {
            tvChildName.setText(childName);
        }

        setupSearch();
        setupTimerStatusPrompt();
    }

    private void setupTimerStatusPrompt() {
        if (tvTimerStatusPrompt == null) {
            return;
        }

        String promptText = "Click here to view the timer status for the apps you have set timers on";
        SpannableString spannableString = new SpannableString(promptText);
        int clickStart = promptText.indexOf("Click here");
        int clickEnd = clickStart + "Click here".length();

        if (clickStart >= 0) {
            spannableString.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    if (returnToTimerStatus) {
                        finish();
                        return;
                    }
                    Intent intent = new Intent(ChildInstalledAppsActivity.this, TimerStatusActivity.class);
                    intent.putExtra(TimerStatusActivity.EXTRA_DEVICE_ID, childDeviceId);
                    intent.putExtra(TimerStatusActivity.EXTRA_IS_PARENT, isParentContext);
                    startActivity(intent);
                }
            }, clickStart, clickEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            spannableString.setSpan(new ForegroundColorSpan(Color.parseColor("#2563EB")), clickStart, clickEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannableString.setSpan(new UnderlineSpan(), clickStart, clickEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        tvTimerStatusPrompt.setText(spannableString);
        tvTimerStatusPrompt.setMovementMethod(LinkMovementMethod.getInstance());
        tvTimerStatusPrompt.setHighlightColor(Color.TRANSPARENT);
    }

    private void setupRecyclerView() {
        adapter = new EnhancedAppsAdapter();
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setHasFixedSize(true);
        rvApps.setItemAnimator(null);
        rvApps.getRecycledViewPool().setMaxRecycledViews(1, 8);
        rvApps.getRecycledViewPool().setMaxRecycledViews(2, 16);
        rvApps.setAdapter(adapter);
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());

        // Clear search button
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                etSearch.setText("");
                btnClearSearch.setVisibility(View.GONE);
            });
        }
    }

    /**
     * 🔍 Setup search with debouncing (300ms delay)
     */
    private void setupSearch() {
        if (etSearch == null)
            return;

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Show/hide clear button
                if (btnClearSearch != null) {
                    btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }

                searchRunnable = () -> rebuildDisplayList();
                searchHandler.postDelayed(searchRunnable, 300);
            }
        });
    }

    /**
     * 🚀 CACHE: Load usage data from local cache for INSTANT display
     * ⚡ OPTIMIZED: Checks date to ensure we don't show yesterday's data
     */
    private void loadCachedUsageData() {
        try {
            // Check if cache is from today
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String today = sdf.format(new Date());
            String cacheDate = usageCache.getString("last_cache_date", "");

            if (!today.equals(cacheDate)) {
                Log.d(TAG, "🚀 CACHE: Cache is outdated (Old: " + cacheDate + ", New: " + today + "). Clearing.");
                usageCache.edit().clear().apply();
                usageDataMap.clear();
                initialUsageReady = false;
                return;
            }

            Map<String, ?> allCache = usageCache.getAll();
            usageDataMap.clear();

            int count = 0;
            for (Map.Entry<String, ?> entry : allCache.entrySet()) {
                if (entry.getValue() instanceof Long) {
                    usageDataMap.put(entry.getKey(), (Long) entry.getValue());
                    count++;
                }
            }

            if (count > 0) {
                initialUsageReady = true;
                Log.d(TAG, "CACHE: Loaded " + count + " cached usage entries - INSTANT!");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to load cached usage: " + e.getMessage());
        }
    }

    /**
     * 🆕 Listen for daily usage data from SUSAGE
     * Path:
     * v2/usage_daily/{deviceId}/{dateKey}/apps/{packageKey}
     */
    private void listenForUsageData() {
        if (usageListenerAttached || childDeviceId == null || childDeviceId.isEmpty()) {
            return;
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Log.d(TAG, "Listening to compact app usage changes for " + childDeviceId + "/" + today);

        usageCache.edit().putString("last_cache_date", today).apply();
        if (!initialUsageReady) {
            displayRefreshHandler.removeCallbacks(initialUsageFallbackRunnable);
            displayRefreshHandler.postDelayed(initialUsageFallbackRunnable, 900L);
            updateInitialContentVisibility();
        }

        usageRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("usage_daily")
                .child(childDeviceId)
                .child(today)
                .child("apps");

        usageListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                mergeUsageEntry(snapshot, today);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                mergeUsageEntry(snapshot, today);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                removeUsageEntry(snapshot);
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {
                // Display order is calculated locally.
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Usage data error: " + error.getMessage());
            }
        };

        usageRef.addChildEventListener(usageListener);
        usageListenerAttached = true;
    }

    private void mergeUsageEntry(DataSnapshot snapshot, String today) {
        String appKey = snapshot.getKey();
        if (appKey == null) {
            return;
        }

        Object rawValue = snapshot.getValue();
        Number durationValue = null;
        if (rawValue instanceof Number) {
            durationValue = (Number) rawValue;
        } else {
            Object rawTimeMillis = snapshot.child("usageTimeMillis").getValue();
            if (rawTimeMillis instanceof Number) {
                durationValue = (Number) rawTimeMillis;
            } else {
                Object rawTime = snapshot.child("usageTime").getValue();
                if (rawTime instanceof Number) {
                    durationValue = (Number) rawTime;
                }
            }
        }

        String packageName = rawValue instanceof Number
                ? null
                : snapshot.child("packageName").getValue(String.class);
        String storageKey = packageName != null && !packageName.isEmpty()
                ? packageName
                : appKey;
        long duration = durationValue != null ? Math.max(0L, durationValue.longValue()) : 0L;

        markUsageDataArrived();

        if (duration > 0L) {
            usageDataMap.put(storageKey, duration);
            usageCache.edit()
                    .putString("last_cache_date", today)
                    .putLong(storageKey, duration)
                    .apply();
        } else {
            usageDataMap.remove(storageKey);
            usageCache.edit().remove(storageKey).apply();
        }
        rebuildDisplayList();
    }
    private void removeUsageEntry(DataSnapshot snapshot) {
        markUsageDataArrived();

        String appKey = snapshot.getKey();
        if (appKey == null) {
            return;
        }

        String packageName = snapshot.child("packageName").getValue(String.class);
        usageDataMap.remove(appKey);
        if (packageName != null) {
            usageDataMap.remove(packageName);
        }

        SharedPreferences.Editor editor = usageCache.edit().remove(appKey);
        if (packageName != null) {
            editor.remove(packageName);
        }
        editor.apply();
        rebuildDisplayList();
    }

    private void markUsageDataArrived() {
        if (initialUsageReady) {
            return;
        }
        displayRefreshHandler.removeCallbacks(initialUsageFallbackRunnable);
        displayRefreshHandler.removeCallbacks(usageReadyAfterFirstBatchRunnable);
        displayRefreshHandler.postDelayed(usageReadyAfterFirstBatchRunnable, 250L);
    }
    private Long getUsageForPackage(String packageName) {
        Long duration = usageDataMap.get(packageName);
        return duration != null ? duration : usageDataMap.get(sanitizeAppKey(packageName));
    }

    private String sanitizeAppKey(String packageName) {
        return packageName != null
                ? packageName.replaceAll("[.#$\\[\\]/]", "_")
                : "";
    }

    private void detachUsageListener() {
        if (usageListener != null && usageRef != null) {
            usageRef.removeEventListener(usageListener);
        }
        usageListener = null;
        usageRef = null;
        usageListenerAttached = false;
    }
    private void rebuildDisplayList() {
        displayRefreshHandler.removeCallbacks(displayRefreshRunnable);
        displayRefreshHandler.postDelayed(displayRefreshRunnable, 50L);
    }

    private void rebuildDisplayListNow() {
        displayList.clear();

        Log.d(TAG, "=== REBUILD START ===");
        Log.d(TAG, "Total apps loaded: " + allAppsWithUsage.size());
        Log.d(TAG, "Usage data entries: " + usageDataMap.size());

        if (allAppsWithUsage.isEmpty()) {
            adapter.notifyDataSetChanged();
            updateEmptyState();
            return;
        }

        // 🔄 SYNC: Ensure usage times in allAppsWithUsage are updated from usageDataMap
        for (AppWithUsage app : allAppsWithUsage) {
            Long usageTime = getUsageForPackage(app.getPackageName());
            if (usageTime != null) {
                app.setUsageTimeMs(usageTime);
            } else {
                app.setUsageTimeMs(0L);
            }
        }

        // Get search query
        String query = etSearch != null ? etSearch.getText().toString().toLowerCase().trim() : "";
        Log.d(TAG, "Search query: '" + query + "'");

        // Filter by search
        List<AppWithUsage> filteredApps = new ArrayList<>();
        for (AppWithUsage app : allAppsWithUsage) {

            // Search filter
            if (!query.isEmpty() &&
                    !app.getAppName().toLowerCase().contains(query) &&
                    !app.getPackageName().toLowerCase().contains(query)) {
                continue;
            }

            filteredApps.add(app);
        }

        Log.d(TAG, "Filtered apps: " + filteredApps.size());

        // Sort by usage time to get top 5
        List<AppWithUsage> sortedByUsage = new ArrayList<>(filteredApps);
        Collections.sort(sortedByUsage, (a, b) -> Long.compare(b.getUsageTimeMs(), a.getUsageTimeMs()));

        // Get top 5 with usage > 0
        List<AppWithUsage> top5 = new ArrayList<>();
        for (AppWithUsage app : sortedByUsage) {
            if (app.getUsageTimeMs() > 0 && top5.size() < 5) {
                app.setTopUsed(true);
                top5.add(app);
                Log.d(TAG, "Top " + (top5.size()) + ": " + app.getAppName() + " - " + app.getUsageTimeFormatted());
            }
        }

        Log.d(TAG, "Top 5 apps found: " + top5.size());

        // Add "Top 5 Most Used Today" section if we have data
        if (!top5.isEmpty()) {
            displayList.add("TOP 5 MOST USED TODAY"); // Section header
            displayList.addAll(top5);
            Log.d(TAG, "Added Top 5 section to display");
        } else {
            Log.w(TAG, "No apps with usage > 0 found!");
        }

        // Get remaining apps (not in top 5) and sort alphabetically
        List<AppWithUsage> remainingApps = new ArrayList<>();
        for (AppWithUsage app : filteredApps) {
            if (!top5.contains(app)) {
                app.setTopUsed(false);
                remainingApps.add(app);
            }
        }
        // Sort by usage descending (leaderboard), alphabetical tiebreak for zero-usage apps
        Collections.sort(remainingApps, (a, b) -> {
            int cmp = Long.compare(b.getUsageTimeMs(), a.getUsageTimeMs());
            if (cmp != 0) return cmp;
            return a.getAppName().compareToIgnoreCase(b.getAppName());
        });

        // Add "All Apps" section
        if (!remainingApps.isEmpty()) {
            displayList.add("ALL APPS (" + remainingApps.size() + ")"); // Section header
            displayList.addAll(remainingApps);
        }

        adapter.notifyDataSetChanged();
        updateEmptyState();

        Log.d(TAG, "=== REBUILD COMPLETE ===");
        Log.d(TAG, "Display list size: " + displayList.size() + " (top 5: " + top5.size() + ", regular: "
                + remainingApps.size() + ")");
    }

    private void loadCachedInventory() {
        inventoryCacheEntry = ParentAppInventoryCache.load(
                this, parentCacheScope, childDeviceId);
        if (inventoryCacheEntry != null && !inventoryCacheEntry.apps.isEmpty()) {
            parseAppsMap(inventoryCacheEntry.apps);
            showLoading(false);
        }
    }

    private void listenForApps() {
        if (inventoryRevisionListener != null) {
            return;
        }
        if (inventoryCacheEntry == null) {
            showLoading(true);
        }

        canonicalAppsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_installs")
                .child(childDeviceId);
        inventoryRevisionRef = canonicalAppsRef.child("revisionId");

        inventoryRevisionListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String remoteRevision = snapshot.getValue(String.class);
                if (remoteRevision == null) {
                    remoteRevision = "";
                }
                if (inventoryCacheEntry != null
                        && remoteRevision.equals(inventoryCacheEntry.revisionId)) {
                    showLoading(false);
                    return;
                }
                fetchCanonicalInventory(remoteRevision);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showLoading(false);
                if (inventoryCacheEntry == null) {
                    Log.w(TAG, "v2 inventory metadata unavailable: " + error.getMessage());
                }
            }
        };
        inventoryRevisionRef.addValueEventListener(inventoryRevisionListener);
    }

    private void fetchCanonicalInventory(String revisionId) {
        canonicalAppsRef.child("apps")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        showLoading(false);
                        Object value = snapshot.getValue();
                        if (!(value instanceof Map) || !snapshot.hasChildren()) {
                            parseAppsMap(Collections.emptyMap());
                            return;
                        }

                        @SuppressWarnings("unchecked")
                        Map<String, Object> apps = (Map<String, Object>) value;
                        parseAppsMap(apps);
                        inventoryCacheEntry = new ParentAppInventoryCache.Entry(
                                revisionId, new HashMap<>(apps));
                        ParentAppInventoryCache.save(
                                ChildInstalledAppsActivity.this,
                                parentCacheScope,
                                childDeviceId,
                                revisionId,
                                apps);
                            }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        showLoading(false);
                        if (inventoryCacheEntry == null) {
                            Log.w(TAG, "v2 inventory read failed: " + error.getMessage());
                        }
                    }
                });
    }

    private void detachInventoryListener() {
        if (inventoryRevisionRef != null && inventoryRevisionListener != null) {
            inventoryRevisionRef.removeEventListener(inventoryRevisionListener);
        }
        inventoryRevisionRef = null;
        inventoryRevisionListener = null;
    }
    private void listenForTimers() {
        if (timersListener != null) {
            return;
        }
        timersRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_policies")
                .child(childDeviceId)
                .child("app_timers");

        timersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                appTimers.clear();
                for (DataSnapshot timerSnap : snapshot.getChildren()) {
                    try {
                        String packageName = timerSnap.child("packageName").getValue(String.class);
                        Long remainingMs = timerSnap.child("remainingTimeMillis").getValue(Long.class);
                        Long totalMs = timerSnap.child("dailyLimitMillis").getValue(Long.class);
                        if (totalMs == null) {
                            totalMs = timerSnap.child("totalTimeMillis").getValue(Long.class);
                        }
                        Boolean active = timerSnap.child("active").getValue(Boolean.class);

                        if (packageName != null && totalMs != null) {
                            AppTimerData timer = new AppTimerData();
                            timer.packageName = packageName;
                            timer.remainingTimeMillis = remainingMs != null ? remainingMs : totalMs;
                            timer.totalTimeMillis = totalMs;
                            timer.active = active != null && active;
                            appTimers.put(packageName, timer);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error parsing timer: " + e.getMessage());
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Timer listener error: " + error.getMessage());
            }
        };

        timersRef.addValueEventListener(timersListener);
    }

    private void parseAppsMap(Map<String, Object> apps) {
        initialInventoryReady = true;
        allAppsWithUsage.clear();
        for (Object rawApp : apps.values()) {
            if (!(rawApp instanceof Map)) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> appData = (Map<String, Object>) rawApp;
                String packageName = asString(appData.get("packageName"));
                String appName = asString(appData.get("appName"));
                if (appName == null) {
                    appName = asString(appData.get("name"));
                }
                String iconBase64 = asString(appData.get("iconBase64"));
                boolean isSystemApp = Boolean.TRUE.equals(appData.get("isSystemApp"));
                if (packageName == null || appName == null) {
                    continue;
                }

                AppWithUsage app = new AppWithUsage(
                        this, packageName, appName, iconBase64, isSystemApp);
                app.setCategory(resolveInventoryCategory(asString(appData.get("category")), packageName, appName));
                Long usageTime = getUsageForPackage(packageName);
                if (usageTime != null) {
                    app.setUsageTimeMs(usageTime);
                }
                AppTimerData timer = appTimers.get(packageName);
                if (timer != null) {
                    app.setHasTimer(true);
                    app.setTimerLimitMs(timer.totalTimeMillis);
                }
                allAppsWithUsage.add(app);
            } catch (Exception error) {
                Log.w(TAG, "Error parsing cached app", error);
            }
        }
        refreshStudyModeState();
        rebuildDisplayList();
    }

    private String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }
    /**
     * 🔍 Update empty state based on current list
     */
    private void updateEmptyState() {
        View emptyState = findViewById(R.id.emptyState);
        if (!isInitialContentReady()) {
            if (emptyState != null) {
                emptyState.setVisibility(View.GONE);
            }
            if (rvApps != null) {
                rvApps.setVisibility(View.GONE);
            }
            return;
        }
        if (displayList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            rvApps.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            rvApps.setVisibility(View.VISIBLE);
        }
    }

    private boolean isInitialContentReady() {
        return initialInventoryReady && initialUsageReady;
    }

    private void updateInitialContentVisibility() {
        boolean loading = !isInitialContentReady();
        if (appListSkeleton != null) {
            appListSkeleton.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (loading) {
            View emptyState = findViewById(R.id.emptyState);
            if (emptyState != null) {
                emptyState.setVisibility(View.GONE);
            }
            if (rvApps != null) {
                rvApps.setVisibility(View.GONE);
            }
        } else {
            updateEmptyState();
        }
    }

    private void setTimer(String packageName, String appName, String iconBase64, long totalTimeMs) {
        String safeKey = sanitizeAppKey(packageName);


        Map<String, Object> timerPolicy = new HashMap<>();
        timerPolicy.put("packageName", packageName);
        timerPolicy.put("dailyLimitMillis", totalTimeMs);
        timerPolicy.put("active", true);
        timerPolicy.put("policyVersion", ServerValue.TIMESTAMP);
        timerPolicy.put("updatedAt", ServerValue.TIMESTAMP);

        FirebaseSchemaV2Repository.syncAppTimerPolicy(
                        childDeviceId, safeKey, timerPolicy)
                .addOnSuccessListener(ignored ->
                        Log.d(TAG, "Timer policy set for " + packageName))
                .addOnFailureListener(error -> {
                    Log.e(TAG, "Failed to set timer: " + error.getMessage());
                    Toast.makeText(this, "Failed to set timer", Toast.LENGTH_SHORT).show();
                });
    }

    private void deleteTimer(String packageName) {
        String safeKey = sanitizeAppKey(packageName);
        FirebaseSchemaV2Repository.removeAppTimerPolicy(childDeviceId, safeKey)
                .addOnSuccessListener(ignored -> {
                    Toast.makeText(this, "Timer deleted", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Timer deleted for " + packageName);
                })
                .addOnFailureListener(error -> {
                    Log.e(TAG, "Failed to delete timer: " + error.getMessage());
                    Toast.makeText(this, "Failed to delete timer", Toast.LENGTH_SHORT).show();
                });
    }
    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }

        if (show && !isInitialContentReady()) {
            updateInitialContentVisibility();
            return;
        }

        if (appListSkeleton != null) {
            appListSkeleton.setVisibility(View.GONE);
        }
        updateEmptyState();
    }

    private String formatTime(long millis) {
        long totalMinutes = millis / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private void bindAppIcon(ImageView imageView, AppWithUsage app) {
        String packageName = app.getPackageName();
        imageView.setTag(packageName);

        Bitmap cached = iconCache.get(packageName);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageResource(android.R.drawable.sym_def_app_icon);
        String encodedIcon = app.getIconBase64();
        if (encodedIcon == null || encodedIcon.isEmpty()) {
            return;
        }

        iconDecodeExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] decodedBytes = Base64.decode(encodedIcon, Base64.DEFAULT);
                bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                if (bitmap != null) {
                    iconCache.put(packageName, bitmap);
                }
            } catch (Exception ignored) {
            }

            Bitmap decodedBitmap = bitmap;
            imageView.post(() -> {
                if (!isFinishing()
                        && packageName.equals(imageView.getTag())
                        && decodedBitmap != null) {
                    imageView.setImageBitmap(decodedBitmap);
                }
            });
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        listenForApps();
        listenForTimers();
        listenForBlockPolicies();
        listenForStudyModePolicy();
        listenForUsageData();
        scheduleStudyModeUiRefresh();
    }

    @Override
    protected void onStop() {
        detachInventoryListener();
        if (timersListener != null && timersRef != null) {
            timersRef.removeEventListener(timersListener);
        }
        timersListener = null;
        timersRef = null;
        detachBlockPolicyListener();
        detachStudyModePolicyListener();
        detachUsageListener();
        displayRefreshHandler.removeCallbacks(initialUsageFallbackRunnable);
        displayRefreshHandler.removeCallbacks(usageReadyAfterFirstBatchRunnable);
        displayRefreshHandler.removeCallbacks(studyModeUiRefreshRunnable);
        super.onStop();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();

        detachInventoryListener();
        if (timersListener != null && timersRef != null) {
            timersRef.removeEventListener(timersListener);
        }
        detachBlockPolicyListener();
        detachStudyModePolicyListener();
        detachUsageListener();
        displayRefreshHandler.removeCallbacks(displayRefreshRunnable);
        displayRefreshHandler.removeCallbacks(initialUsageFallbackRunnable);
        displayRefreshHandler.removeCallbacks(usageReadyAfterFirstBatchRunnable);
        displayRefreshHandler.removeCallbacks(studyModeUiRefreshRunnable);
        iconDecodeExecutor.shutdownNow();
        iconCache.evictAll();

        Log.d(TAG, "ChildInstalledAppsActivity destroyed");
    }

    // Data classes
    static class AppTimerData {
        String packageName;
        long totalTimeMillis;
        long remainingTimeMillis;
        boolean active;
    }


    // Blocking functionality

    private boolean isAppBlocked(String packageName) {
        return isAppManuallyBlocked(packageName) || isStudyModeBlocked(packageName);
    }

    private boolean isAppManuallyBlocked(String packageName) {
        if (AppBlockingPolicy.isUnblockable(packageName)) {
            return false;
        }
        android.content.SharedPreferences prefs = getSharedPreferences("blocked_apps_" + childDeviceId, MODE_PRIVATE);
        return prefs.getBoolean(packageName, false);
    }

    private boolean isStudyModeBlocked(String packageName) {
        return packageName != null && activeStudyModeBlocks.contains(packageName);
    }
    private void blockApp(String packageName, String appName) {
        if (AppBlockingPolicy.isUnblockable(packageName)) {
            cacheBlockStatus(packageName, false);
            Toast.makeText(this, "Android Settings must remain accessible", Toast.LENGTH_SHORT).show();
            return;
        }
        writeBlockPolicy(packageName, appName, true, "IMMEDIATE", 0L);
    }

    private void blockAppDelayed(String packageName, String appName, long delayMs) {
        if (AppBlockingPolicy.isUnblockable(packageName)) {
            cacheBlockStatus(packageName, false);
            Toast.makeText(this, "Android Settings must remain accessible", Toast.LENGTH_SHORT).show();
            return;
        }
        writeBlockPolicy(packageName, appName, true, "DELAYED", delayMs);
    }

    private void unblockApp(String packageName) {
        writeBlockPolicy(packageName, packageName, false, "IMMEDIATE", 0L);
    }

    private void writeBlockPolicy(String packageName, String appName, boolean blocked,
            String enforcementMode, long delayMs) {
        Map<String, Object> policy = new HashMap<>();
        policy.put("policyId", UUID.randomUUID().toString());
        policy.put("packageName", packageName);
        policy.put("appName", appName);
        policy.put("blocked", blocked);
        policy.put("enforcementMode", enforcementMode);
        policy.put("delayDurationMs", delayMs);
        policy.put("updatedAt", ServerValue.TIMESTAMP);

        FirebaseSchemaV2Repository.syncAppBlockPolicy(
                        childDeviceId, sanitizeAppKey(packageName), policy)
                .addOnSuccessListener(ignored -> {
                    cacheBlockStatus(packageName, blocked);
                    showBlockResultToast(appName, blocked, delayMs);
                })
                .addOnFailureListener(error -> {
                    Log.e(TAG, "Failed to update block policy", error);
                    Toast.makeText(this, "Failed to update app block", Toast.LENGTH_SHORT).show();
                });
    }

    private void showBlockResultToast(String appName, boolean blocked, long delayMs) {
        String message = blocked
                ? appName + (delayMs > 0L ? " scheduled to block in 5 mins" : " blocked")
                : "App unblocked";
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
    private void cacheBlockStatus(String packageName, boolean isBlocked) {
        // Use device specific SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences("blocked_apps_" + childDeviceId, MODE_PRIVATE);
        prefs.edit().putBoolean(packageName, isBlocked).apply();
    }

    private void handleBlockSwitchClick(AppWithUsage app, SwitchCompat switchBlock, boolean canBlock) {
        if (!canBlock) {
            switchBlock.setChecked(false);
            Toast.makeText(this, "Android Settings must remain accessible", Toast.LENGTH_SHORT).show();
            return;
        }

        String packageName = app.getPackageName();
        boolean manuallyBlocked = isAppManuallyBlocked(packageName);
        boolean studyBlocked = isStudyModeBlocked(packageName);
        boolean currentlyBlocked = manuallyBlocked || studyBlocked;
        switchBlock.setChecked(currentlyBlocked);

        if (studyBlocked && !manuallyBlocked) {
            showStudyModeSessionUnblockConfirmation(packageName, app.getAppName(),
                    () -> switchBlock.setChecked(false),
                    () -> switchBlock.setChecked(true));
        } else if (currentlyBlocked) {
            showUnblockConfirmation(packageName, app.getAppName(),
                    () -> {
                        unblockApp(packageName);
                        switchBlock.setChecked(isStudyModeBlocked(packageName));
                    },
                    () -> switchBlock.setChecked(true));
        } else {
            showBlockConfirmation(packageName, app.getAppName(),
                    () -> {
                        blockApp(packageName, app.getAppName());
                        switchBlock.setChecked(true);
                    },
                    () -> {
                        blockAppDelayed(packageName, app.getAppName(), 300000);
                        switchBlock.setChecked(true);
                    },
                    () -> switchBlock.setChecked(false));
        }
    }
    private void showBlockConfirmation(String packageName, String appName, Runnable onConfirmImmediate, Runnable onConfirmDelayed, Runnable onCancel) {
        // Create context with forced light theme to prevent dark mode text issues
        android.view.ContextThemeWrapper themedContext = new android.view.ContextThemeWrapper(this,
                R.style.AlertDialogCustom);

        new androidx.appcompat.app.AlertDialog.Builder(themedContext)
                .setTitle("Block App?")
                .setMessage("Do you wanna block " + appName + "?")
                .setPositiveButton("Block Now", (dialog, which) -> {
                    if (onConfirmImmediate != null)
                        onConfirmImmediate.run();
                })
                .setNeutralButton("In 5 Minutes", (dialog, which) -> {
                    if (onConfirmDelayed != null)
                        onConfirmDelayed.run();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (onCancel != null)
                        onCancel.run();
                })
                .setCancelable(false)
                .show();
    }

    private void showUnblockConfirmation(String packageName, String appName, Runnable onConfirm, Runnable onCancel) {
        // Create context with forced light theme to prevent dark mode text issues
        android.view.ContextThemeWrapper themedContext = new android.view.ContextThemeWrapper(this,
                R.style.AlertDialogCustom);

        new androidx.appcompat.app.AlertDialog.Builder(themedContext)
                .setTitle("Unblock App?")
                .setMessage("Do you wanna unblock " + appName + "?")
                .setPositiveButton("Unblock", (dialog, which) -> {
                    if (onConfirm != null)
                        onConfirm.run();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (onCancel != null)
                        onCancel.run();
                })
                .setCancelable(false)
                .show();
    }

    private void showStudyModeSessionUnblockConfirmation(String packageName, String appName,
            Runnable onConfirm, Runnable onCancel) {
        android.view.ContextThemeWrapper themedContext = new android.view.ContextThemeWrapper(this,
                R.style.AlertDialogCustom);
        new androidx.appcompat.app.AlertDialog.Builder(themedContext)
                .setTitle("Unblock for this session?")
                .setMessage(appName + " is currently blocked by Study Mode. If you continue, it will be unblocked for this Study Mode session only. It may be blocked again the next time Study Mode becomes active.")
                .setPositiveButton("Unblock", (dialog, which) -> {
                    allowStudyModePackageForCurrentSession(packageName, appName, onConfirm);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (onCancel != null) {
                        onCancel.run();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void allowStudyModePackageForCurrentSession(String packageName, String appName,
            Runnable onSuccess) {
        if (activeStudyModePolicy == null || activeStudyModeSessionKey == null) {
            Toast.makeText(this, "Study Mode is not active right now", Toast.LENGTH_SHORT).show();
            if (onSuccess != null) {
                onSuccess.run();
            }
            return;
        }

        Map<String, Object> allow = new HashMap<>();
        allow.put("packageName", packageName);
        allow.put("allowed", true);
        allow.put("sessionKey", activeStudyModeSessionKey);
        allow.put("updatedAt", ServerValue.TIMESTAMP);

        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_modes")
                .child(childDeviceId)
                .child(StudyModeContract.MODE_ID)
                .child("sessionAllows")
                .child(sanitizeAppKey(packageName))
                .setValue(allow)
                .addOnSuccessListener(ignored -> {
                    if (activeStudyModePolicy.sessionAllowedPackages == null) {
                        activeStudyModePolicy.sessionAllowedPackages = new HashMap<>();
                    }
                    activeStudyModePolicy.sessionAllowedPackages.put(packageName, activeStudyModeSessionKey);
                    refreshStudyModeState();
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                    Toast.makeText(this, appName + " unblocked for this Study Mode session", Toast.LENGTH_SHORT).show();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                })
                .addOnFailureListener(error -> {
                    Log.e(TAG, "Failed to allow Study Mode session exception", error);
                    Toast.makeText(this, "Failed to unblock Study Mode app", Toast.LENGTH_SHORT).show();
                });
    }
    // \ud83c\udd95 Enhanced Adapter with multiple view types
    class EnhancedAppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_HEADER = 0;
        private static final int VIEW_TYPE_TOP_USED = 1;
        private static final int VIEW_TYPE_REGULAR = 2;

        @Override
        public int getItemViewType(int position) {
            Object item = displayList.get(position);
            if (item instanceof String) {
                return VIEW_TYPE_HEADER;
            } else if (item instanceof AppWithUsage) {
                AppWithUsage app = (AppWithUsage) item;
                return app.isTopUsed() ? VIEW_TYPE_TOP_USED : VIEW_TYPE_REGULAR;
            }
            return VIEW_TYPE_REGULAR;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    View headerView = inflater.inflate(R.layout.item_app_section_header, parent, false);
                    return new HeaderViewHolder(headerView);
                case VIEW_TYPE_TOP_USED:
                    View topView = inflater.inflate(R.layout.item_top_used_app, parent, false);
                    return new TopUsedViewHolder(topView);
                default:
                    View regularView = inflater.inflate(R.layout.item_installed_app, parent, false);
                    return new RegularViewHolder(regularView);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = displayList.get(position);

            if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).bind((String) item);
            } else if (holder instanceof TopUsedViewHolder) {
                int rank = 1;
                for (int i = 0; i <= position; i++) {
                    Object obj = displayList.get(i);
                    if (obj instanceof AppWithUsage && ((AppWithUsage) obj).isTopUsed()) {
                        if (i == position)
                            break;
                        rank++;
                    }
                }
                ((TopUsedViewHolder) holder).bind((AppWithUsage) item, rank);
            } else if (holder instanceof RegularViewHolder) {
                ((RegularViewHolder) holder).bind((AppWithUsage) item);
            }
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }

        // Header ViewHolder
        class HeaderViewHolder extends RecyclerView.ViewHolder {
            TextView tvSectionTitle;
            TextView tvLimitHeader;
            TextView tvBlockingHeader;

            HeaderViewHolder(View itemView) {
                super(itemView);
                tvSectionTitle = itemView.findViewById(R.id.tvSectionTitle);
                tvLimitHeader = itemView.findViewById(R.id.tvLimitHeader);
                tvBlockingHeader = itemView.findViewById(R.id.tvBlockingHeader);
            }

            void bind(String title) {
                tvSectionTitle.setText(title);

                // 🎨 Add icons based on title content
                if (title.contains("TOP 5")) {
                    tvSectionTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_trend_chart_circle, 0, 0, 0);
                    tvSectionTitle.setCompoundDrawablePadding(16); // 16px padding

                    // Show column headers for Top 5 section
                    if (tvLimitHeader != null)
                        tvLimitHeader.setVisibility(View.VISIBLE);
                    if (tvBlockingHeader != null)
                        tvBlockingHeader.setVisibility(View.VISIBLE);
                } else if (title.contains("ALL APPS")) {
                    tvSectionTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_apps_phone_premium, 0, 0, 0);
                    tvSectionTitle.setCompoundDrawablePadding(16);

                    // Hide column headers for All Apps section
                    if (tvLimitHeader != null)
                        tvLimitHeader.setVisibility(View.GONE);
                    if (tvBlockingHeader != null)
                        tvBlockingHeader.setVisibility(View.GONE);
                } else {
                    tvSectionTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                    if (tvLimitHeader != null)
                        tvLimitHeader.setVisibility(View.GONE);
                    if (tvBlockingHeader != null)
                        tvBlockingHeader.setVisibility(View.GONE);
                }
            }
        }

        // Top 5 ViewHolder
        class TopUsedViewHolder extends RecyclerView.ViewHolder {
            TextView tvRank, tvAppName, tvUsageTime, tvLimitValue;
            ImageView ivIcon, imgTimerIcon;
            androidx.appcompat.widget.SwitchCompat switchBlock;

            TopUsedViewHolder(View itemView) {
                super(itemView);
                tvRank = itemView.findViewById(R.id.tvRank);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvUsageTime = itemView.findViewById(R.id.tvUsageTime);
                imgTimerIcon = itemView.findViewById(R.id.imgTimerIcon);
                tvLimitValue = itemView.findViewById(R.id.tvLimitValue);
                ivIcon = itemView.findViewById(R.id.ivIcon);
                switchBlock = itemView.findViewById(R.id.switchBlock);
            }

            void bind(AppWithUsage app, int rank) {
                tvRank.setText(String.valueOf(rank));
                tvAppName.setText(app.getAppName());
                tvUsageTime.setText(app.getUsageTimeFormatted() + " today");

                bindAppIcon(ivIcon, app);

                // Show set limit value (static, not countdown)
                AppTimerData limitTimer = appTimers.get(app.getPackageName());
                if (limitTimer != null && limitTimer.totalTimeMillis > 0) {
                    tvLimitValue.setVisibility(View.VISIBLE);
                    tvLimitValue.setText(formatTime(limitTimer.totalTimeMillis));
                } else {
                    tvLimitValue.setVisibility(View.GONE);
                }

                imgTimerIcon.setOnClickListener(v -> showTimerDialogForApp(app));

                // Block switch logic
                boolean isBlocked = isAppBlocked(app.getPackageName());
                switchBlock.setOnCheckedChangeListener(null);
                switchBlock.setChecked(isBlocked);
                boolean canBlock = !AppBlockingPolicy.isUnblockable(app.getPackageName());
                switchBlock.setEnabled(canBlock);
                switchBlock.setAlpha(canBlock ? 1f : 0.45f);

                // Disable swipe/drag to prevent visual desync by consuming touches and performing click on release
                switchBlock.setOnTouchListener((v, event) -> {
                    if (!canBlock) {
                        return true;
                    }
                    if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                        if (event.getX() >= 0 && event.getX() <= v.getWidth() &&
                            event.getY() >= 0 && event.getY() <= v.getHeight()) {
                            v.performClick();
                        }
                    }
                    return true;
                });

                switchBlock.setOnClickListener(v -> handleBlockSwitchClick(app, switchBlock, canBlock));
            }
        }

        // Regular App ViewHolder
        class RegularViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon, imgTimerIcon;
            TextView tvAppName, tvUsageTime, tvLimitValue;
            androidx.appcompat.widget.SwitchCompat switchBlock;

            RegularViewHolder(View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.imgAppIcon);
                imgTimerIcon = itemView.findViewById(R.id.imgTimerIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvUsageTime = itemView.findViewById(R.id.tvUsageTime);
                tvLimitValue = itemView.findViewById(R.id.tvLimitValue);
                switchBlock = itemView.findViewById(R.id.switchBlock);
            }

            void bind(AppWithUsage app) {
                tvAppName.setText(app.getAppName());

                // Show usage time if available
                if (app.getUsageTimeMs() > 0) {
                    tvUsageTime.setVisibility(View.VISIBLE);
                    tvUsageTime.setText(formatTime(app.getUsageTimeMs()) + " today");
                } else {
                    tvUsageTime.setVisibility(View.GONE);
                }

                bindAppIcon(imgIcon, app);

                // Show set limit value (static, not countdown)
                AppTimerData timer = appTimers.get(app.getPackageName());
                if (timer != null && timer.totalTimeMillis > 0) {
                    tvLimitValue.setVisibility(View.VISIBLE);
                    tvLimitValue.setText(formatTime(timer.totalTimeMillis));
                } else {
                    tvLimitValue.setVisibility(View.GONE);
                }

                imgTimerIcon.setOnClickListener(v -> showTimerDialogForApp(app));

                // Block switch
                boolean isBlocked = isAppBlocked(app.getPackageName());
                switchBlock.setOnCheckedChangeListener(null);
                switchBlock.setChecked(isBlocked);
                boolean canBlock = !AppBlockingPolicy.isUnblockable(app.getPackageName());
                switchBlock.setEnabled(canBlock);
                switchBlock.setAlpha(canBlock ? 1f : 0.45f);

                // Disable swipe/drag to prevent visual desync by consuming touches and performing click on release
                switchBlock.setOnTouchListener((v, event) -> {
                    if (!canBlock) {
                        return true;
                    }
                    if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                        if (event.getX() >= 0 && event.getX() <= v.getWidth() &&
                            event.getY() >= 0 && event.getY() <= v.getHeight()) {
                            v.performClick();
                        }
                    }
                    return true;
                });

                switchBlock.setOnClickListener(v -> handleBlockSwitchClick(app, switchBlock, canBlock));
            }
        }
    }

    private void showTimerDialogForApp(AppWithUsage app) {
        // Reuse existing timer dialog logic
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_timer);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView tvSubtitle = dialog.findViewById(R.id.tvTimerSubtitle);
        NumberPicker npHours = dialog.findViewById(R.id.npHours);
        NumberPicker npMinutes = dialog.findViewById(R.id.npMinutes);
        Button btnDelete = dialog.findViewById(R.id.btnDeleteTimer);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnOk = dialog.findViewById(R.id.btnOk);

        tvSubtitle.setText("This app timer for " + app.getAppName() + " will reset at midnight");

        npHours.setMinValue(0);
        npHours.setMaxValue(12);
        npMinutes.setMinValue(0);
        npMinutes.setMaxValue(59);
        npMinutes.setValue(30);

        AppTimerData existingTimer = appTimers.get(app.getPackageName());
        if (existingTimer != null) {
            long limitMs = existingTimer.totalTimeMillis > 0
                    ? existingTimer.totalTimeMillis : existingTimer.remainingTimeMillis;
            int hours = (int) (limitMs / (1000 * 60 * 60));
            int minutes = (int) ((limitMs % (1000 * 60 * 60)) / (1000 * 60));
            npHours.setValue(hours);
            npMinutes.setValue(minutes);
            
            // Only show Delete button, hide Cancel/Save and disable pickers
            btnDelete.setVisibility(View.VISIBLE);
            btnCancel.setVisibility(View.GONE);
            btnOk.setVisibility(View.GONE);
            npHours.setEnabled(false);
            npMinutes.setEnabled(false);
        } else {
            btnDelete.setVisibility(View.GONE);
            btnCancel.setVisibility(View.VISIBLE);
            btnOk.setVisibility(View.VISIBLE);
            npHours.setEnabled(true);
            npMinutes.setEnabled(true);
        }

        btnDelete.setOnClickListener(v -> {
            deleteTimer(app.getPackageName());
            // Switch UI to "Create Mode"
            btnDelete.setVisibility(View.GONE);
            btnCancel.setVisibility(View.VISIBLE);
            btnOk.setVisibility(View.VISIBLE);
            npHours.setEnabled(true);
            npMinutes.setEnabled(true);
            Toast.makeText(this, "Timer deleted. You can set a new one now.", Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnOk.setOnClickListener(v -> {
            int hours = npHours.getValue();
            int minutes = npMinutes.getValue();
            long totalMs = (hours * 60 + minutes) * 60 * 1000L;

            if (totalMs > 0) {
                setTimer(app.getPackageName(), app.getAppName(), app.getIconBase64(), totalMs);
                Toast.makeText(this, "Timer set for " + app.getAppName(), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Please set a time", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private void listenForStudyModePolicy() {
        if (studyModeListener != null) {
            return;
        }
        studyModeRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_modes")
                .child(childDeviceId)
                .child(StudyModeContract.MODE_ID);
        studyModeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                activeStudyModePolicy = StudyModePolicyRepository.fromSnapshot(snapshot);
                refreshStudyModeState();
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
                scheduleStudyModeUiRefresh();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Study Mode listener cancelled: " + error.getMessage());
            }
        };
        studyModeRef.addValueEventListener(studyModeListener);
    }

    private void scheduleStudyModeUiRefresh() {
        displayRefreshHandler.removeCallbacks(studyModeUiRefreshRunnable);
        StudyModePolicy policy = activeStudyModePolicy;
        if (policy == null || !policy.enabled) {
            return;
        }
        long transitionDelay = StudyModeScheduleEvaluator.millisUntilNextTransition(
                policy, System.currentTimeMillis());
        long delayMs = transitionDelay >= 0L
                ? Math.max(STUDY_MODE_UI_MIN_REFRESH_MS,
                        Math.min(STUDY_MODE_UI_FALLBACK_REFRESH_MS,
                                transitionDelay + STUDY_MODE_UI_BOUNDARY_GRACE_MS))
                : STUDY_MODE_UI_FALLBACK_REFRESH_MS;
        displayRefreshHandler.postDelayed(studyModeUiRefreshRunnable, delayMs);
    }

    private void refreshStudyModeState() {
        activeStudyModeBlocks.clear();
        activeStudyModeSessionKey = null;
        StudyModePolicy policy = activeStudyModePolicy;
        if (policy == null || !policy.enabled || !StudyModeScheduleEvaluator.isActiveNow(policy)) {
            return;
        }
        activeStudyModeSessionKey = StudyModeScheduleEvaluator.currentSessionKey(policy);

        java.util.Set<String> explicitBlocks = policy.getEffectiveBlockedPackages();
        for (String packageName : explicitBlocks) {
            if (packageName != null
                    && packageName.contains(".")
                    && !AppBlockingPolicy.isUnblockable(packageName)
                    && !isStudyModeSessionAllowed(policy, packageName)) {
                activeStudyModeBlocks.add(packageName);
            }
        }
        for (AppWithUsage app : allAppsWithUsage) {
            if (app == null || app.getPackageName() == null) {
                continue;
            }
            String packageName = app.getPackageName();
            if (matchesPackageReference(explicitBlocks, packageName)
                    && !AppBlockingPolicy.isUnblockable(packageName)
                    && !isStudyModeSessionAllowed(policy, packageName)
                    && !isStudyAllowedOverride(policy, packageName)) {
                activeStudyModeBlocks.add(packageName);
            }
        }
        addStudyModeCategoryBlocks(policy);
    }

    private void addStudyModeCategoryBlocks(StudyModePolicy policy) {
        if (policy == null || policy.categories == null || allAppsWithUsage == null) {
            return;
        }
        boolean social = isStudyCategoryEnabled(policy, StudyModeContract.CATEGORY_SOCIAL);
        boolean games = isStudyCategoryEnabled(policy, StudyModeContract.CATEGORY_GAMES);
        boolean entertainment = isStudyCategoryEnabled(policy, StudyModeContract.CATEGORY_ENTERTAINMENT);
        if (!social && !games && !entertainment) {
            return;
        }

        for (AppWithUsage app : allAppsWithUsage) {
            if (app == null || AppBlockingPolicy.isUnblockable(app.getPackageName())) {
                continue;
            }
            String packageName = app.getPackageName();
            if (isStudyModeSessionAllowed(policy, packageName)) {
                continue;
            }
            if (isStudyAllowedOverride(policy, packageName)) {
                continue;
            }
            if (matchesStudyModeCategory(app.getCategory(), social, games, entertainment)) {
                activeStudyModeBlocks.add(packageName);
            }
        }
    }

    private boolean isStudyCategoryEnabled(StudyModePolicy policy, String categoryId) {
        if (policy == null || policy.categories == null) {
            return false;
        }
        StudyModePolicy.CategorySelection selection = policy.categories.get(categoryId);
        return selection != null && selection.enabled;
    }

    private boolean matchesStudyModeCategory(AppCategorizer.AppCategory category,
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

    private boolean isStudyModeSessionAllowed(StudyModePolicy policy, String packageName) {
        if (policy == null || packageName == null || policy.sessionAllowedPackages == null) {
            return false;
        }
        String sessionKey = activeStudyModeSessionKey != null
                ? activeStudyModeSessionKey
                : StudyModeScheduleEvaluator.currentSessionKey(policy);
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

    private boolean matchesPackageReference(java.util.Set<String> references, String packageName) {
        return references != null && packageName != null
                && (references.contains(packageName) || references.contains(sanitizeAppKey(packageName)));
    }

    private void detachStudyModePolicyListener() {
        if (studyModeRef != null && studyModeListener != null) {
            studyModeRef.removeEventListener(studyModeListener);
        }
        studyModeRef = null;
        studyModeListener = null;
        activeStudyModePolicy = null;
        activeStudyModeSessionKey = null;
        activeStudyModeBlocks.clear();
    }

    private AppCategorizer.AppCategory resolveInventoryCategory(
            String categoryName, String packageName, String appName) {
        if (categoryName != null) {
            for (AppCategorizer.AppCategory category : AppCategorizer.AppCategory.values()) {
                if (categoryName.equalsIgnoreCase(category.name())
                        || categoryName.equalsIgnoreCase(category.getDisplayName())) {
                    return category;
                }
            }
        }
        return AppCategorizer.getCategory(packageName, appName);
    }
    // Use a one-time legacy migration only after v2 support is confirmed.
    private void loadAppLimitsCapability() {
        useV2BlockPolicies = true;
    }
    private void listenForBlockPolicies() {
        if (blockPolicyListener != null) {
            return;
        }
        blockPoliciesRef = FirebaseDatabase.getInstance().getReference("v2")
                .child("device_policies").child(childDeviceId).child("blocked_apps");
        blockPolicyListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                mergeBlockPolicy(snapshot);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                mergeBlockPolicy(snapshot);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                String packageName = snapshot.child("packageName").getValue(String.class);
                if (packageName != null) {
                    cacheBlockStatus(packageName, false);
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                }
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Block policy listener cancelled: " + error.getMessage());
            }
        };
        blockPoliciesRef.addChildEventListener(blockPolicyListener);
    }

    private void mergeBlockPolicy(DataSnapshot snapshot) {
        String packageName = snapshot.child("packageName").getValue(String.class);
        Boolean blocked = snapshot.child("blocked").getValue(Boolean.class);
        if (packageName == null || blocked == null) {
            return;
        }
        cacheBlockStatus(packageName,
                blocked && !AppBlockingPolicy.isUnblockable(packageName));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void detachBlockPolicyListener() {
        if (blockPoliciesRef != null && blockPolicyListener != null) {
            blockPoliciesRef.removeEventListener(blockPolicyListener);
        }
        blockPoliciesRef = null;
        blockPolicyListener = null;
    }

}


