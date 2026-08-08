package online.monarchlabs.sentinel;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import online.monarchlabs.sentinel.adapters.DaySelectorAdapter;
import online.monarchlabs.sentinel.adapters.LegendAdapter;
import online.monarchlabs.sentinel.adapters.SUsageAppAdapter;
import online.monarchlabs.sentinel.models.SUsageAppInfo;
import online.monarchlabs.sentinel.models.SUsageDailyData;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import online.monarchlabs.sentinel.utils.AppCategorizer;
import online.monarchlabs.sentinel.utils.ParentUsageCacheManager;

/**
 * Activity for PARENT to view child's usage data.
 * Fetches data from Firebase that was uploaded by the child device.
 */
public class ChildUsageViewActivity extends BaseActivity {

    private static final String TAG = "ChildUsageView";
    public static final String EXTRA_CHILD_DEVICE_ID = "child_device_id";
    public static final String EXTRA_CHILD_NAME = "child_name";

    // Views
    private TextView tvTitle;
    private TextView tvChildName;
    private TextView tvUsageLastSynced;
    private TextView tvDeviceLastSeen;

    private DonutChart donutChart;

    private RecyclerView rvDateStrip;
    private RecyclerView rvLegend;
    private RecyclerView rvAppsUsage;

    private TextView tvEmptyApps;
    private ImageView btnRefresh;
    private ImageView btnBack;
    private View loadingOverlay;

    // Data
    private SUsageAppAdapter appAdapter;
    private List<SUsageDailyData> weeklyUsage = new ArrayList<>();
    private int selectedDayIndex = 6; // Today
    private String childDeviceId;
    private String childName;
    private ValueEventListener usageListener;
    private DatabaseReference usageRef;
    private ValueEventListener deviceStatusListener;
    private DatabaseReference deviceStatusRef;
    private DatabaseReference usageAppsRef;
    private ChildEventListener usageAppsListener;
    private DatabaseReference usageAppStatesRef;
    private ValueEventListener usageAppStatesListener;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private ParentUsageCacheManager cacheManager;
    private List<String> expectedDateKeys = new ArrayList<>();
    private boolean usageScreenForeground;
    private boolean iconFetchInProgress;
    private final Set<String> metadataLookupAttemptedKeys = new HashSet<>();
    private Runnable usageMidnightRollover;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_usage_view);

        // Get child device info from intent
        childDeviceId = getIntent().getStringExtra(EXTRA_CHILD_DEVICE_ID);
        childName = getIntent().getStringExtra(EXTRA_CHILD_NAME);

        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.e(TAG, "No child device ID provided!");
            Toast.makeText(this, "Error: No child device selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "Viewing usage for child: " + childDeviceId + " (" + childName + ")");

        initViews();
        setupRecyclerView();
        setupClickListeners();

        cacheManager = ParentUsageCacheManager.getInstance(this);
        initializeUsageDays();
    }

    @Override
    protected void onStart() {
        super.onStart();
        usageScreenForeground = true;
        loadUsageDataFromFirebase();
        loadDeviceStatusFromFirebase();
        ensureAppIconsCached();
        scheduleUsageMidnightRollover();
    }

    @Override
    protected void onStop() {
        usageScreenForeground = false;
        refreshHandler.removeCallbacksAndMessages(null);
        usageMidnightRollover = null;
        removeFirebaseListener();
        removeDeviceStatusListener();
        super.onStop();
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tvTitle);
        tvChildName = findViewById(R.id.tvChildName);
        tvUsageLastSynced = findViewById(R.id.tvUsageLastSynced);
        tvDeviceLastSeen = findViewById(R.id.tvDeviceLastSeen);

        donutChart = findViewById(R.id.donutChart);
        rvDateStrip = findViewById(R.id.rvDateStrip);
        rvLegend = findViewById(R.id.rvLegend);
        rvAppsUsage = findViewById(R.id.rvAppsUsage);

        tvEmptyApps = findViewById(R.id.tvEmptyApps);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnBack = findViewById(R.id.btnBack);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        // Set child name
        if (childName != null && !childName.isEmpty()) {
            tvChildName.setText(childName);
        } else {
            tvChildName.setText(childDeviceId);
        }

        if (tvUsageLastSynced != null) {
            tvUsageLastSynced.setText("Usage synced: --");
        }
        if (tvDeviceLastSeen != null) {
            tvDeviceLastSeen.setText("Device last seen: --");
        }
    }

    private void setupRecyclerView() {
        appAdapter = new SUsageAppAdapter();
        appAdapter.setRemoteMode(true); // Parent viewing child's apps - don't try to load icons
        rvAppsUsage.setLayoutManager(new LinearLayoutManager(this));
        rvAppsUsage.setAdapter(appAdapter);
        rvAppsUsage.setNestedScrollingEnabled(false);

        // Handle App Click -> Scroll to App in InstalledApps Activity
        appAdapter.setOnItemClickListener(appUsage -> {
            android.content.Intent intent = new android.content.Intent(this, ChildInstalledAppsActivity.class);
            intent.putExtra(ChildInstalledAppsActivity.EXTRA_CHILD_DEVICE_ID, childDeviceId);
            intent.putExtra(ChildInstalledAppsActivity.EXTRA_CHILD_NAME, childName);
            intent.putExtra(ChildInstalledAppsActivity.EXTRA_IS_PARENT_CONTEXT, true);
            intent.putExtra("scrollToPackage", appUsage.getPackageName()); // Pass package to scroll to
            startActivity(intent);
        });
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> refreshSelectedDate());
    }

    private void refreshSelectedDate() {
        String dateKey = getSelectedDateKey();
        if (dateKey == null) {
            return;
        }

        cacheManager.invalidateDay(childDeviceId, dateKey);
        if (dateKey.equals(todayKey())) {
            requestChildToUploadData();
        } else {
            loadUsageDataFromFirebase();
            Toast.makeText(this, "Refreshing selected day...", Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeUsageDays() {
        expectedDateKeys = buildExpectedDateKeys();
        weeklyUsage.clear();
        for (String dateKey : expectedDateKeys) {
            weeklyUsage.add(createEmptyDay(dateKey));
        }
        selectedDayIndex = Math.max(0, weeklyUsage.size() - 1);
        setupDateStrip();
        updateSelectedDayDisplay();
    }

    private void loadUsageDataFromFirebase() {
        removeFirebaseListener();

        String dateKey = getSelectedDateKey();
        if (dateKey == null) {
            showEmptyState();
            return;
        }

        boolean todaySelected = dateKey.equals(todayKey());
        Map<String, Object> cachedDay = cacheManager.getDailyUsage(childDeviceId, dateKey);
        if (cachedDay != null) {
            displayCachedDay(cachedDay, dateKey);
            if (!todaySelected) {
                return;
            }
        } else if (!todaySelected && cacheManager.isMissingDayFresh(childDeviceId, dateKey)) {
            replaceDay(dateKey, createEmptyDay(dateKey));
            showLoading(false);
            updateSelectedDayDisplay();
            return;
        }

        if (!usageScreenForeground) {
            return;
        }

        showLoading(cachedDay == null);
        DatabaseReference dayRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("usage_daily")
                .child(childDeviceId)
                .child(dateKey);

        if (todaySelected) {
            attachTodayUsageListeners(dayRef, dateKey);
            return;
        }

        dayRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                handleHistoricalDaySnapshot(snapshot, dateKey);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleUsageLoadError(error);
            }
        });
    }

    private void attachTodayUsageListeners(DatabaseReference dayRef, String dateKey) {
        usageRef = dayRef.child("lastUpdated");
        usageListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isCurrentUsageSelection(dateKey)) {
                    return;
                }
                showLoading(false);
                Long lastUpdated = snapshot.getValue(Long.class);
                long syncedAt = lastUpdated != null ? lastUpdated : 0L;
                SUsageDailyData day = getDay(dateKey);
                if (day != null && syncedAt > 0L) {
                    day.setLastUpdated(syncedAt);
                }
                updateLastSynced(syncedAt);
                cacheCurrentTodayUsage(dateKey);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleUsageLoadError(error);
            }
        };
        usageRef.addValueEventListener(usageListener);

        usageAppsRef = dayRef.child("apps");
        usageAppsListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                mergeTodayApp(snapshot, dateKey);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                mergeTodayApp(snapshot, dateKey);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                if (!isCurrentUsageSelection(dateKey)) {
                    return;
                }
                SUsageDailyData day = getDay(dateKey);
                if (day != null && snapshot.getKey() != null) {
                    day.getApps().remove(snapshot.getKey());
                    updateSelectedDayDisplay();
                    cacheCurrentTodayUsage(dateKey);
                }
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {
                // Usage ordering is calculated locally.
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                handleUsageLoadError(error);
            }
        };
        usageAppsRef.addChildEventListener(usageAppsListener);

        usageAppStatesRef = dayRef.child("appStates");
        usageAppStatesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isCurrentUsageSelection(dateKey)) {
                    return;
                }
                SUsageDailyData day = getDay(dateKey);
                if (day != null) {
                    applyAppStatesFromSnapshot(day, snapshot);
                    updateSelectedDayDisplay();
                    cacheCurrentTodayUsage(dateKey);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Usage app state listener cancelled: " + error.getMessage());
            }
        };
        usageAppStatesRef.addValueEventListener(usageAppStatesListener);
    }

    private void mergeTodayApp(DataSnapshot appSnapshot, String dateKey) {
        if (!isCurrentUsageSelection(dateKey) || appSnapshot.getKey() == null) {
            return;
        }

        SUsageDailyData day = getDay(dateKey);
        if (day == null) {
            day = createEmptyDay(dateKey);
            replaceDay(dateKey, day);
        }

        SUsageAppInfo app = parseAppSnapshot(appSnapshot);
        if (app != null) {
            day.getApps().put(appSnapshot.getKey(), app);
            showLoading(false);
            updateSelectedDayDisplay();
            cacheCurrentTodayUsage(dateKey);
            ensureAppIconsCached();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleHistoricalDaySnapshot(DataSnapshot snapshot, String dateKey) {
        if (!isCurrentUsageSelection(dateKey)) {
            return;
        }

        showLoading(false);
        if (!snapshot.exists()) {
            cacheManager.cacheMissingDay(childDeviceId, dateKey);
            replaceDay(dateKey, createEmptyDay(dateKey));
            updateUsageSyncSummary("unavailable");
            updateSelectedDayDisplay();
            return;
        }

        Object rawValue = snapshot.getValue();
        if (rawValue instanceof Map) {
            cacheManager.cacheDailyUsage(
                    childDeviceId,
                    dateKey,
                    (Map<String, Object>) rawValue);
        }

        SUsageDailyData dailyData = parseDailySnapshot(snapshot, dateKey);
        replaceDay(dateKey, dailyData);
        updateLastSynced(dailyData.getLastUpdated());
        updateSelectedDayDisplay();
        ensureAppIconsCached();
    }

    private void displayCachedDay(Map<String, Object> dayMap, String dateKey) {
        try {
            SUsageDailyData dailyData = parseDailyMap(dayMap, dateKey);
            replaceDay(dateKey, dailyData);
            showLoading(false);
            updateLastSynced(dailyData.getLastUpdated());
            updateSelectedDayDisplay();
            cacheCurrentTodayUsage(dateKey);
            ensureAppIconsCached();
        } catch (Exception error) {
            Log.e(TAG, "Could not parse cached usage for " + dateKey, error);
            cacheManager.invalidateDay(childDeviceId, dateKey);
            showLoading(false);
            showEmptyState();
        }
    }

    private void cacheCurrentTodayUsage(String dateKey) {
        if (cacheManager == null || childDeviceId == null || !dateKey.equals(todayKey())) {
            return;
        }
        SUsageDailyData day = getDay(dateKey);
        if (day == null) {
            return;
        }
        cacheManager.cacheLiveDailyUsage(childDeviceId, dateKey, toCacheMap(day));
    }

    private Map<String, Object> toCacheMap(SUsageDailyData day) {
        Map<String, Object> dayMap = new HashMap<>();
        Map<String, Object> appsMap = new HashMap<>();
        Map<String, Object> appStatesMap = new HashMap<>();
        long summedUsage = 0L;

        if (day.getApps() != null) {
            for (Map.Entry<String, SUsageAppInfo> entry : day.getApps().entrySet()) {
                SUsageAppInfo app = entry.getValue();
                if (app == null) {
                    continue;
                }
                String appKey = firstNonEmpty(entry.getKey(), sanitizeAppKey(app.getPackageName()));
                long usageMillis = Math.max(0L, app.getUsageTimeMillis());
                summedUsage += usageMillis;

                Map<String, Object> appMap = new HashMap<>();
                appMap.put("usageTimeMillis", usageMillis);
                putIfNotEmpty(appMap, "packageName", app.getPackageName());
                putIfNotEmpty(appMap, "appName", app.getAppName());
                putIfNotEmpty(appMap, "category", app.getCategory());
                appMap.put("installed", app.isInstalled());
                putIfNotEmpty(appMap, "status", app.getStatus());
                if (app.getUninstalledAt() > 0L) {
                    appMap.put("uninstalledAt", app.getUninstalledAt());
                }
                if (app.getReinstalledAt() > 0L) {
                    appMap.put("reinstalledAt", app.getReinstalledAt());
                }
                appsMap.put(appKey, appMap);

                Map<String, Object> stateMap = new HashMap<>();
                stateMap.put("installed", app.isInstalled());
                putIfNotEmpty(stateMap, "status", app.getStatus());
                if (app.getUninstalledAt() > 0L) {
                    stateMap.put("uninstalledAt", app.getUninstalledAt());
                }
                if (app.getReinstalledAt() > 0L) {
                    stateMap.put("reinstalledAt", app.getReinstalledAt());
                }
                appStatesMap.put(appKey, stateMap);
            }
        }

        dayMap.put("totalScreenTimeMillis", Math.max(day.getTotalScreenTimeMillis(), summedUsage));
        dayMap.put("communicationTimeMillis", day.getCommunicationTimeMillis());
        dayMap.put("entertainmentTimeMillis", day.getEntertainmentTimeMillis());
        dayMap.put("gamesTimeMillis", day.getGamesTimeMillis());
        dayMap.put("otherTimeMillis", day.getOtherTimeMillis());
        dayMap.put("lastUpdated", day.getLastUpdated());
        dayMap.put("apps", appsMap);
        if (!appStatesMap.isEmpty()) {
            dayMap.put("appStates", appStatesMap);
        }
        return dayMap;
    }

    private void putIfNotEmpty(Map<String, Object> target, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.put(key, value);
        }
    }
    private SUsageDailyData parseDailySnapshot(DataSnapshot snapshot, String dateKey) {
        SUsageDailyData day = createEmptyDay(dateKey);
        day.setTotalScreenTimeMillis(readSnapshotLong(snapshot, "totalScreenTimeMillis"));
        day.setCommunicationTimeMillis(readSnapshotLong(snapshot, "communicationTimeMillis"));
        day.setEntertainmentTimeMillis(readSnapshotLong(snapshot, "entertainmentTimeMillis"));
        day.setGamesTimeMillis(readSnapshotLong(snapshot, "gamesTimeMillis"));
        day.setOtherTimeMillis(readSnapshotLong(snapshot, "otherTimeMillis"));
        day.setLastUpdated(readSnapshotLong(snapshot, "lastUpdated"));

        for (DataSnapshot appSnapshot : snapshot.child("apps").getChildren()) {
            SUsageAppInfo app = parseAppSnapshot(appSnapshot);
            if (app != null && appSnapshot.getKey() != null) {
                day.getApps().put(appSnapshot.getKey(), app);
            }
        }
        applyAppStatesFromSnapshot(day, snapshot.child("appStates"));
        return day;
    }

    @SuppressWarnings("unchecked")
    private SUsageDailyData parseDailyMap(Map<String, Object> dayMap, String dateKey) {
        SUsageDailyData day = createEmptyDay(dateKey);
        day.setTotalScreenTimeMillis(readMapLong(dayMap.get("totalScreenTimeMillis")));
        day.setCommunicationTimeMillis(readMapLong(dayMap.get("communicationTimeMillis")));
        day.setEntertainmentTimeMillis(readMapLong(dayMap.get("entertainmentTimeMillis")));
        day.setGamesTimeMillis(readMapLong(dayMap.get("gamesTimeMillis")));
        day.setOtherTimeMillis(readMapLong(dayMap.get("otherTimeMillis")));
        day.setLastUpdated(readMapLong(dayMap.get("lastUpdated")));

        Object appsValue = dayMap.get("apps");
        if (appsValue instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) appsValue).entrySet()) {
                String appKey = String.valueOf(entry.getKey());
                SUsageAppInfo app = parseAppValue(appKey, entry.getValue());
                if (app != null) {
                    day.getApps().put(appKey, app);
                }
            }
        }
        Object appStatesValue = dayMap.get("appStates");
        if (appStatesValue instanceof Map) {
            applyAppStatesFromMap(day, (Map<?, ?>) appStatesValue);
        }
        return day;
    }

    private SUsageAppInfo parseAppSnapshot(DataSnapshot snapshot) {
        if (snapshot.getKey() == null) {
            return null;
        }

        Object rawValue = snapshot.getValue();
        if (rawValue instanceof Number) {
            return buildCompactApp(snapshot.getKey(), ((Number) rawValue).longValue());
        }

        long duration = readSnapshotLong(snapshot, "usageTimeMillis");
        if (duration <= 0L) {
            duration = readSnapshotLong(snapshot, "usageTime");
        }
        String packageName = snapshot.child("packageName").getValue(String.class);
        String appName = snapshot.child("appName").getValue(String.class);
        String category = snapshot.child("category").getValue(String.class);
        String iconBase64 = snapshot.child("iconBase64").getValue(String.class);
        SUsageAppInfo app = buildApp(
                snapshot.getKey(),
                packageName,
                appName,
                category,
                iconBase64,
                duration);
        applyStateToApp(
                app,
                snapshot.child("installed").getValue(),
                snapshot.child("status").getValue(),
                snapshot.child("uninstalledAt").getValue(),
                snapshot.child("reinstalledAt").getValue());
        return app;
    }

    @SuppressWarnings("unchecked")
    private SUsageAppInfo parseAppValue(String appKey, Object rawValue) {
        if (rawValue instanceof Number) {
            return buildCompactApp(appKey, ((Number) rawValue).longValue());
        }
        if (!(rawValue instanceof Map)) {
            return null;
        }

        Map<String, Object> appMap = (Map<String, Object>) rawValue;
        long duration = readMapLong(appMap.get("usageTimeMillis"));
        if (duration <= 0L) {
            duration = readMapLong(appMap.get("usageTime"));
        }
        SUsageAppInfo app = buildApp(
                appKey,
                stringValue(appMap.get("packageName")),
                stringValue(appMap.get("appName")),
                stringValue(appMap.get("category")),
                stringValue(appMap.get("iconBase64")),
                duration);
        applyStateToApp(
                app,
                appMap.get("installed"),
                appMap.get("status"),
                appMap.get("uninstalledAt"),
                appMap.get("reinstalledAt"));
        return app;
    }

    private SUsageAppInfo buildCompactApp(String appKey, long duration) {
        return buildApp(appKey, null, null, null, null, duration);
    }

    private SUsageAppInfo buildApp(
            String appKey,
            String packageName,
            String appName,
            String category,
            String iconBase64,
            long duration) {
        String resolvedPackage = firstNonEmpty(
                packageName,
                cacheManager.getAppPackageName(childDeviceId, appKey),
                appKey);
        String resolvedName = firstNonEmpty(
                appName,
                cacheManager.getAppName(childDeviceId, appKey),
                resolvedPackage);

        if ("online.monarchlabs.sentinel".equals(resolvedPackage) || "online_monarchlabs_sentinel".equals(resolvedPackage)) {
            resolvedName = "Sentinel";
            resolvedPackage = "online.monarchlabs.sentinel";
        }
        String resolvedCategory = firstNonEmpty(
                category,
                cacheManager.getAppCategory(childDeviceId, appKey),
                AppCategorizer.getCategory(resolvedPackage).getDisplayName());
        String resolvedIcon = firstNonEmpty(
                iconBase64,
                cacheManager.getAppIconByKey(childDeviceId, appKey));

        SUsageAppInfo app = new SUsageAppInfo(
                resolvedPackage,
                resolvedName,
                Math.max(0L, duration),
                resolvedCategory);
        app.setIconBase64(resolvedIcon);
        return app;
    }

    private void applyAppStatesFromSnapshot(SUsageDailyData day, DataSnapshot statesSnapshot) {
        if (day == null || statesSnapshot == null || !statesSnapshot.exists()) {
            return;
        }
        for (DataSnapshot stateSnapshot : statesSnapshot.getChildren()) {
            if (stateSnapshot.getKey() == null) {
                continue;
            }
            SUsageAppInfo app = day.getApps().get(stateSnapshot.getKey());
            if (app != null) {
                applyStateToApp(
                        app,
                        stateSnapshot.child("installed").getValue(),
                        stateSnapshot.child("status").getValue(),
                        stateSnapshot.child("uninstalledAt").getValue(),
                        stateSnapshot.child("reinstalledAt").getValue());
            }
        }
    }

    private void applyAppStatesFromMap(SUsageDailyData day, Map<?, ?> statesMap) {
        if (day == null || statesMap == null) {
            return;
        }
        for (Map.Entry<?, ?> entry : statesMap.entrySet()) {
            String appKey = String.valueOf(entry.getKey());
            SUsageAppInfo app = day.getApps().get(appKey);
            if (app == null || !(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<?, ?> state = (Map<?, ?>) entry.getValue();
            applyStateToApp(
                    app,
                    state.get("installed"),
                    state.get("status"),
                    state.get("uninstalledAt"),
                    state.get("reinstalledAt"));
        }
    }

    private void applyStateToApp(SUsageAppInfo app, Object installedValue,
            Object statusValue, Object uninstalledAtValue, Object reinstalledAtValue) {
        if (app == null) {
            return;
        }
        if (installedValue instanceof Boolean) {
            app.setInstalled((Boolean) installedValue);
        }
        if (statusValue instanceof String) {
            app.setStatus((String) statusValue);
        }
        long uninstalledAt = readMapLong(uninstalledAtValue);
        if (uninstalledAt > 0L) {
            app.setUninstalledAt(uninstalledAt);
        }
        long reinstalledAt = readMapLong(reinstalledAtValue);
        if (reinstalledAt > 0L) {
            app.setReinstalledAt(reinstalledAt);
        }
    }

    private boolean isCurrentUsageSelection(String dateKey) {
        return usageScreenForeground && dateKey != null && dateKey.equals(getSelectedDateKey());
    }

    private SUsageDailyData getDay(String dateKey) {
        int index = expectedDateKeys.indexOf(dateKey);
        return index >= 0 && index < weeklyUsage.size() ? weeklyUsage.get(index) : null;
    }

    private long readSnapshotLong(DataSnapshot snapshot, String childName) {
        Object raw = snapshot.child(childName).getValue();
        return raw instanceof Number ? ((Number) raw).longValue() : 0L;
    }

    private long readMapLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }
    private void handleUsageLoadError(DatabaseError error) {
        showLoading(false);
        Log.e(TAG, "Usage load failed: " + error.getMessage());
        Toast.makeText(this, "Error loading data: " + error.getMessage(), Toast.LENGTH_SHORT).show();
    }

    private void updateLastSynced(long lastUpdated) {
        if (lastUpdated <= 0L) {
            updateUsageSyncSummary("unavailable");
            return;
        }

        String timeText = formatRelativeTime(lastUpdated);
        updateUsageSyncSummary(timeText);
        if (System.currentTimeMillis() - lastUpdated > 60L * 60L * 1000L) {
            Toast.makeText(this, "Usage synced: " + timeText, Toast.LENGTH_LONG).show();
        }
    }

    private void replaceDay(String dateKey, SUsageDailyData dailyData) {
        int index = expectedDateKeys.indexOf(dateKey);
        if (index >= 0 && index < weeklyUsage.size()) {
            weeklyUsage.set(index, dailyData);
        }
    }

    private String getSelectedDateKey() {
        if (selectedDayIndex < 0 || selectedDayIndex >= expectedDateKeys.size()) {
            return null;
        }
        return expectedDateKeys.get(selectedDayIndex);
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void scheduleUsageMidnightRollover() {
        if (usageMidnightRollover != null) {
            refreshHandler.removeCallbacks(usageMidnightRollover);
        }

        Calendar nextDay = Calendar.getInstance();
        nextDay.add(Calendar.DAY_OF_YEAR, 1);
        nextDay.set(Calendar.HOUR_OF_DAY, 0);
        nextDay.set(Calendar.MINUTE, 0);
        nextDay.set(Calendar.SECOND, 1);
        nextDay.set(Calendar.MILLISECOND, 0);

        usageMidnightRollover = () -> {
            if (!usageScreenForeground) {
                return;
            }

            String previouslySelectedDate = getSelectedDateKey();
            boolean wasViewingToday = selectedDayIndex == expectedDateKeys.size() - 1;
            List<String> newDateKeys = buildExpectedDateKeys();
            List<SUsageDailyData> rotatedUsage = new ArrayList<>();
            for (String newDateKey : newDateKeys) {
                int oldIndex = expectedDateKeys.indexOf(newDateKey);
                rotatedUsage.add(oldIndex >= 0
                        ? weeklyUsage.get(oldIndex)
                        : createEmptyDay(newDateKey));
            }

            expectedDateKeys = newDateKeys;
            weeklyUsage = rotatedUsage;
            int preservedIndex = previouslySelectedDate != null
                    ? expectedDateKeys.indexOf(previouslySelectedDate)
                    : -1;
            selectedDayIndex = wasViewingToday || preservedIndex < 0
                    ? expectedDateKeys.size() - 1
                    : preservedIndex;

            setupDateStrip();
            updateSelectedDayDisplay();
            loadUsageDataFromFirebase();
            scheduleUsageMidnightRollover();
        };

        long delayMs = Math.max(1000L, nextDay.getTimeInMillis() - System.currentTimeMillis());
        refreshHandler.postDelayed(usageMidnightRollover, delayMs);
    }
    private List<String> buildExpectedDateKeys() {
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        List<String> dates = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            Calendar dayCalendar = (Calendar) calendar.clone();
            dayCalendar.add(Calendar.DAY_OF_YEAR, -i);
            dates.add(dateFormat.format(dayCalendar.getTime()));
        }
        return dates;
    }

    private void setupDateStrip() {
        List<Calendar> days = new ArrayList<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        for (String dateKey : expectedDateKeys) {
            Calendar day = Calendar.getInstance();
            try {
                Date parsed = dateFormat.parse(dateKey);
                if (parsed != null) {
                    day.setTime(parsed);
                }
            } catch (Exception error) {
                Log.w(TAG, "Could not parse usage date " + dateKey);
            }
            days.add(day);
        }

        DaySelectorAdapter dayAdapter = new DaySelectorAdapter(days, (index, day) -> {
            if (index == selectedDayIndex) {
                return;
            }
            selectedDayIndex = index;
            if (rvDateStrip.getAdapter() instanceof DaySelectorAdapter) {
                ((DaySelectorAdapter) rvDateStrip.getAdapter()).setSelectedIndex(index);
            }
            updateSelectedDayDisplay();
            loadUsageDataFromFirebase();
        });

        rvDateStrip.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvDateStrip.setAdapter(dayAdapter);
        dayAdapter.setSelectedIndex(selectedDayIndex);
        rvDateStrip.scrollToPosition(days.size() - 1);
    }
    private SUsageDailyData createEmptyDay(String dateKey) {
        SUsageDailyData emptyDay = new SUsageDailyData();
        emptyDay.setDateKey(dateKey);
        return emptyDay;
    }

    private void ensureAppIconsCached() {
        // Bypassed: Downloading the entire device_installs/apps node (containing hundreds of Base64 app icons) 
        // consumes megabytes of bandwidth and causes severe network congestion on screen startup.
        // App metadata and icons are already lazy-loaded on-demand via requestMissingAppMetadata() in updateSelectedDayDisplay().
    }

    private void cacheAppMetadataSnapshot(String appKey, DataSnapshot appSnapshot) {
        if (appSnapshot == null || !appSnapshot.exists()) {
            return;
        }
        String resolvedKey = firstNonEmpty(appKey, appSnapshot.getKey());
        String packageName = appSnapshot.child("packageName").getValue(String.class);
        String appName = appSnapshot.child("appName").getValue(String.class);
        if (appName == null || appName.isEmpty()) {
            appName = appSnapshot.child("name").getValue(String.class);
        }
        String category = appSnapshot.child("category").getValue(String.class);
        String iconBase64 = appSnapshot.child("iconBase64").getValue(String.class);
        cacheManager.cacheAppMetadata(
                childDeviceId,
                resolvedKey,
                packageName,
                appName,
                category,
                iconBase64);
    }

    private void requestMissingAppMetadata(List<SUsageAppInfo> apps) {
        if (apps == null || apps.isEmpty() || childDeviceId == null || !usageScreenForeground) {
            return;
        }
        for (SUsageAppInfo app : apps) {
            if (!needsAppMetadataLookup(app)) {
                continue;
            }
            String appKey = sanitizeAppKey(firstNonEmpty(app.getPackageName(), app.getAppName()));
            String lookupKey = childDeviceId + ":" + appKey;
            if (metadataLookupAttemptedKeys.contains(lookupKey)) {
                continue;
            }
            metadataLookupAttemptedKeys.add(lookupKey);
            fetchAppCatalogMetadata(appKey);
        }
    }

    private boolean needsAppMetadataLookup(SUsageAppInfo app) {
        if (app == null) {
            return false;
        }
        String packageName = firstNonEmpty(app.getPackageName(), "");
        String appName = firstNonEmpty(app.getAppName(), "");
        String sanitizedPackage = sanitizeAppKey(packageName);
        boolean packageLooksSanitized = !packageName.contains(".") && packageName.contains("_");
        boolean nameLooksRaw = appName.isEmpty()
                || appName.equals(packageName)
                || appName.equals(sanitizedPackage)
                || (!appName.contains(" ") && appName.contains("_"));
        boolean iconMissing = app.getIconBase64() == null || app.getIconBase64().isEmpty();
        return packageLooksSanitized || nameLooksRaw || iconMissing;
    }

    private void fetchAppCatalogMetadata(String appKey) {
        if (appKey == null || appKey.isEmpty()) {
            return;
        }
        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("app_catalog")
                .child(childDeviceId)
                .child(appKey)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (!snapshot.exists()) {
                            return;
                        }
                        cacheAppMetadataSnapshot(appKey, snapshot);
                        if (usageScreenForeground) {
                            updateSelectedDayDisplay();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.w(TAG, "App catalog metadata lookup failed for "
                                + appKey + ": " + error.getMessage());
                    }
                });
    }
    private void applyCachedMetadata(List<SUsageAppInfo> apps) {
        for (SUsageAppInfo app : apps) {
            if (app == null) {
                continue;
            }
            String appKey = sanitizeAppKey(app.getPackageName());
            String packageName = cacheManager.getAppPackageName(childDeviceId, appKey);
            String appName = cacheManager.getAppName(childDeviceId, appKey);
            String category = cacheManager.getAppCategory(childDeviceId, appKey);
            String iconBase64 = cacheManager.getAppIconByKey(childDeviceId, appKey);

            if (packageName != null && !packageName.isEmpty()) {
                app.setPackageName(packageName);
            }
            if (appName != null && !appName.isEmpty()) {
                app.setAppName(appName);
            }
            if (category != null && !category.isEmpty()) {
                app.setCategory(category);
            } else if (app.getCategory() == null || app.getCategory().isEmpty()
                    || "Other".equalsIgnoreCase(app.getCategory())) {
                app.setCategory(AppCategorizer.getCategory(app.getPackageName()).getDisplayName());
            }
            if (iconBase64 != null && !iconBase64.isEmpty()) {
                app.setIconBase64(iconBase64);
            }
        }
    }

    private String sanitizeAppKey(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "unknown";
        }
        return packageName.replaceAll("[.#$\\[\\]/]", "_");
    }
    private void showEmptyState() {
        tvEmptyApps.setVisibility(View.VISIBLE);
        rvAppsUsage.setVisibility(View.GONE);

        if (donutChart != null)
            donutChart.setVisibility(View.GONE);
    }

    private void updateSelectedDayDisplay() {
        if (weeklyUsage == null || weeklyUsage.isEmpty() ||
                selectedDayIndex < 0 || selectedDayIndex >= weeklyUsage.size()) {
            return;
        }

        SUsageDailyData selectedDay = weeklyUsage.get(selectedDayIndex);

        // Update apps list. Keep the list aligned with the uploaded total so
        // Sentinel appears in Detailed Stats when its usage contributes to the day.
        List<SUsageAppInfo> appList = new ArrayList<>();
        for (SUsageAppInfo app : selectedDay.getAppList()) {
            if (app != null) {
                appList.add(app);
            }
        }

        applyCachedMetadata(appList);
        requestMissingAppMetadata(appList);

        // Sort by usage time descending
        Collections.sort(appList, (a, b) -> Long.compare(b.getUsageTimeMillis(), a.getUsageTimeMillis()));

        appAdapter.updateData(appList);

        updateDonutChart(appList, formatDuration(sumUsageMillis(appList)));

        // Show/hide empty state
        if (appList.isEmpty()) {
            tvEmptyApps.setVisibility(View.VISIBLE);
            rvAppsUsage.setVisibility(View.GONE);
        } else {
            tvEmptyApps.setVisibility(View.GONE);
            rvAppsUsage.setVisibility(View.VISIBLE);
        }

        // Update selection in adapter (if needed, but usually click triggers it)
        if (rvDateStrip.getAdapter() instanceof DaySelectorAdapter) {
            ((DaySelectorAdapter) rvDateStrip.getAdapter()).setSelectedIndex(selectedDayIndex);
        }
    }

    private long sumUsageMillis(List<SUsageAppInfo> apps) {
        long totalMillis = 0L;
        if (apps == null) {
            return totalMillis;
        }
        for (SUsageAppInfo app : apps) {
            if (app != null) {
                totalMillis += Math.max(0L, app.getUsageTimeMillis());
            }
        }
        return totalMillis;
    }

    private String formatDuration(long durationMillis) {
        long totalMinutes = Math.max(0L, durationMillis) / (1000L * 60L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private void updateDonutChart(List<SUsageAppInfo> apps, String centerText) {
        if (donutChart == null)
            return;

        if (apps == null || apps.isEmpty()) {
            donutChart.setVisibility(View.GONE);
            return;
        }
        donutChart.setVisibility(View.VISIBLE);

        List<Float> values = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        // Colors palette (Distinct / Vibrant / Premium)
        int[] palette = {
                Color.parseColor("#4F46E5"), // Indigo 600
                Color.parseColor("#EC4899"), // Pink 500
                Color.parseColor("#10B981"), // Emerald 500
                Color.parseColor("#F59E0B"), // Amber 500
                Color.parseColor("#06B6D4"), // Cyan 500
                Color.parseColor("#8B5CF6"), // Violet 500
                Color.parseColor("#F43F5E") // Rose 500
        };

        int count = Math.min(apps.size(), 5);
        for (int i = 0; i < count; i++) {
            values.add((float) apps.get(i).getUsageTimeMillis());
            labels.add(apps.get(i).getAppName());
            colors.add(palette[i % palette.length]);
        }

        donutChart.setData(values, colors, labels);
        donutChart.setCenterText(centerText, "Total");

        // Update Legend
        LegendAdapter legendAdapter = new LegendAdapter(labels, colors);
        rvLegend.setLayoutManager(new GridLayoutManager(this, 2)); // 2 Columns
        rvLegend.setAdapter(legendAdapter);
    }

    private void loadDeviceStatusFromFirebase() {
        removeDeviceStatusListener();

        deviceStatusRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_status")
                .child(childDeviceId);

        deviceStatusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    updateDeviceStatusSummary("Device last seen: unavailable");
                    return;
                }

                String bootstrapConnectionId = snapshot
                        .child("usageBootstrap")
                        .child("connectionId")
                        .getValue(String.class);
                String historyGeneration = snapshot
                        .child("usageBootstrap")
                        .child("historyGeneration")
                        .getValue(String.class);
                boolean cacheScopeChanged = cacheManager.setUsageScope(
                        childDeviceId,
                        bootstrapConnectionId,
                        historyGeneration);
                if (cacheScopeChanged
                        && !todayKey().equals(getSelectedDateKey())) {
                    loadUsageDataFromFirebase();
                }
                Long lastSeen = snapshot.child("lastSeen").getValue(Long.class);
                Boolean isOnline = snapshot.child("isOnline").getValue(Boolean.class);
                Boolean isAppActive = snapshot.child("isAppActive").getValue(Boolean.class);

                updateDeviceStatusSummary(buildDeviceStatusText(lastSeen, isOnline, isAppActive));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Device status error: " + error.getMessage());
                updateDeviceStatusSummary("Device last seen: unavailable");
            }
        };

        deviceStatusRef.addValueEventListener(deviceStatusListener);
    }

    private void updateUsageSyncSummary(String syncText) {
        if (tvUsageLastSynced != null) {
            tvUsageLastSynced.setText("Usage synced: " + syncText.replace("Usage synced: ", ""));
        }
    }

    private void updateDeviceStatusSummary(String statusText) {
        if (tvDeviceLastSeen != null) {
            tvDeviceLastSeen.setText(statusText);
        }
    }

    private String buildDeviceStatusText(Long lastSeen, Boolean isOnline, Boolean isAppActive) {
        if (lastSeen == null || lastSeen <= 0) {
            return "Device last seen: unavailable";
        }

        String status;
        if (Boolean.TRUE.equals(isOnline)) {
            status = "Online";
        } else if (Boolean.TRUE.equals(isAppActive)) {
            status = "App active";
        } else {
            status = "Offline";
        }

        return "Device last seen: " + status + " · " + formatRelativeTime(lastSeen);
    }

    private String formatRelativeTime(long timestamp) {
        long timeSince = System.currentTimeMillis() - timestamp;
        long minutesAgo = timeSince / (1000 * 60);

        if (minutesAgo < 1) {
            return "Just now";
        }
        if (minutesAgo < 60) {
            return minutesAgo + " min ago";
        }
        if (minutesAgo < 1440) {
            long hours = minutesAgo / 60;
            return hours + " hour" + (hours > 1 ? "s" : "") + " ago";
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    private void removeDeviceStatusListener() {
        if (deviceStatusRef != null && deviceStatusListener != null) {
            deviceStatusRef.removeEventListener(deviceStatusListener);
            deviceStatusListener = null;
        }
    }

    private void requestChildToUploadData() {
        DatabaseReference requestRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("commands")
                .child(childDeviceId)
                .child("usage_refresh");

        java.util.Map<String, Object> request = new java.util.HashMap<>();
        request.put("command", "refresh_usage_data");
        request.put("deviceId", childDeviceId);
        request.put("timestamp", System.currentTimeMillis());
        request.put("requestedBy", "parent");
        request.put("status", "pending");
        request.put("reason", "manual_refresh");

        requestRef.setValue(request)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Update request sent to child device");

                    Toast.makeText(this, "Requesting fresh data from child...", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to send update request: " + e.getMessage());
                });
    }

    private void removeFirebaseListener() {
        if (usageRef != null && usageListener != null) {
            usageRef.removeEventListener(usageListener);
        }
        if (usageAppsRef != null && usageAppsListener != null) {
            usageAppsRef.removeEventListener(usageAppsListener);
        }
        if (usageAppStatesRef != null && usageAppStatesListener != null) {
            usageAppStatesRef.removeEventListener(usageAppStatesListener);
        }
        usageRef = null;
        usageListener = null;
        usageAppsRef = null;
        usageAppsListener = null;
        usageAppStatesRef = null;
        usageAppStatesListener = null;
    }

    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}
