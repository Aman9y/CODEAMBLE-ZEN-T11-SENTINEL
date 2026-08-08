package online.monarchlabs.sentinel;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import online.monarchlabs.sentinel.adapters.PermissionEventAdapter;
import online.monarchlabs.sentinel.data.ParentAppInventoryCache;
import online.monarchlabs.sentinel.models.PermissionEvent;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;

/**
 * Activity that displays permission change notifications for a specific child
 * device.
 * Shows current permission status and historical events.
 */
public class ChildNotificationsActivity extends BaseActivity {
    private static final String TAG = "ChildNotifications";
    private static final String KEY_LAST_READ_ALL = "lastReadTimestamp";
    private static final String KEY_LAST_READ_PERMISSION_STATUS = "lastReadPermissionStatusTimestamp";
    private static final String KEY_LAST_READ_APP_STATUS = "lastReadAppStatusTimestamp";

    public static final String EXTRA_CHILD_DEVICE_ID = "childDeviceId";
    public static final String EXTRA_CHILD_NAME = "childName";
    public static final String EXTRA_PARENT_USER_ID = "parentUserId";

    private String childDeviceId;
    private String childName;
    private String parentUserId;

    private RecyclerView rvEvents;
    private LinearLayout emptyState;
    private PermissionEventAdapter adapter;
    private List<PermissionEvent> permissionEvents = new ArrayList<>();
    private List<PermissionEvent> sentinelProtectionEvents = new ArrayList<>();
    private List<String> sentinelProtectionEventKeys = new ArrayList<>();

    private ImageView ivAccessibility, ivUsageStats, ivNotifications, ivBattery;
    private TextView tvStatusSummary;

    private DatabaseReference eventsRef;
    private DatabaseReference statusRef;
    private ValueEventListener eventsListener;
    private ValueEventListener statusListener;

    // 📦 APP STATUS FILTER
    private MaterialButton btnPermissionStatus, btnAppStatus, btnClearPermissionHistory;
    private LinearLayout containerPermissionStatus, containerAppStatus;
    private RecyclerView rvAppStatus;
    private LinearLayout emptyStateAppStatus;
    private AppStatusAdapter appStatusAdapter;
    private List<AppStatusEvent> appEvents = new ArrayList<>();
    private final Map<String, String> appIconBase64ByPackage = new HashMap<>();
    private DatabaseReference appEventsRef;
    private ValueEventListener appEventsListener;
    private DatabaseReference notificationStateListenerRef;
    private ValueEventListener notificationStateListener;

    // 🔔 BADGE COUNTERS
    private TextView tvPermissionBadge, tvAppStatusBadge;
    private int permissionUnreadCount = 0;
    private int appUnreadCount = 0;
    private int sentinelProtectionUnreadCount = 0;
    private long permissionStatusLastReadTime = 0L;
    private long appStatusLastReadTime = 0L;
    private boolean notificationReadStateLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_notifications);

        // Get intent extras
        childDeviceId = getIntent().getStringExtra(EXTRA_CHILD_DEVICE_ID);
        childName = getIntent().getStringExtra(EXTRA_CHILD_NAME);
        parentUserId = getIntent().getStringExtra(EXTRA_PARENT_USER_ID);

        if (childDeviceId == null || parentUserId == null) {
            Log.e(TAG, "Missing required extras!");
            finish();
            return;
        }

        Log.d(TAG, "Opening notifications for: " + childName + " (" + childDeviceId + ")");

        setupToolbar();
        initializeViews();
        setupRecyclerView();
        loadAppStatusIcons();
        setupFilterToggle();
        setupPermissionHistoryClear();
        setupFirebaseListeners();
        setupAppEventsListener();
        setupBadgeCounters(); // Count and display unread notifications
        markNotificationsAsSeenForBell(); // Clear the parent dashboard bell only.
        markPermissionStatusAsRead(); // Default visible tab is Permission Status.
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String title = (childName != null) ? childName + " - Notifications" : "Device Notifications";
            getSupportActionBar().setTitle(title);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void initializeViews() {
        rvEvents = findViewById(R.id.rvEvents);
        emptyState = findViewById(R.id.emptyState);

        ivAccessibility = findViewById(R.id.ivAccessibility);
        ivUsageStats = findViewById(R.id.ivUsageStats);
        ivNotifications = findViewById(R.id.ivNotifications);
        ivBattery = findViewById(R.id.ivBattery);
        tvStatusSummary = findViewById(R.id.tvStatusSummary);

        // 📦 APP STATUS VIEWS
        btnPermissionStatus = findViewById(R.id.btnPermissionStatus);
        btnAppStatus = findViewById(R.id.btnAppStatus);
        btnClearPermissionHistory = findViewById(R.id.btnClearPermissionHistory);
        containerPermissionStatus = findViewById(R.id.containerPermissionStatus);
        containerAppStatus = findViewById(R.id.containerAppStatus);
        rvAppStatus = findViewById(R.id.rvAppStatus);
        emptyStateAppStatus = findViewById(R.id.emptyStateAppStatus);

        // 🔔 BADGE VIEWS
        tvPermissionBadge = findViewById(R.id.tvPermissionBadge);
        tvAppStatusBadge = findViewById(R.id.tvAppStatusBadge);
    }

    private void setupRecyclerView() {
        adapter = new PermissionEventAdapter(this);
        rvEvents.setLayoutManager(new LinearLayoutManager(this));
        rvEvents.setAdapter(adapter);

        // Setup app status recycler view
        appStatusAdapter = new AppStatusAdapter(this, appEvents);
        appStatusAdapter.setIconBase64ByPackage(appIconBase64ByPackage);
        rvAppStatus.setLayoutManager(new LinearLayoutManager(this));
        rvAppStatus.setAdapter(appStatusAdapter);
    }

    /**
     * 🔄 Setup filter toggle between Permission Status and App Status
     */
    private void loadAppStatusIcons() {
        ParentAppInventoryCache.Entry cached = ParentAppInventoryCache.load(
                this, parentUserId, childDeviceId);
        if (cached != null && cached.apps != null && !cached.apps.isEmpty()) {
            applyAppInventoryIcons(cached.apps);
        }

        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_installs")
                .child(childDeviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Object rawApps = snapshot.child("apps").getValue();
                        if (!(rawApps instanceof Map)) {
                            return;
                        }

                        @SuppressWarnings("unchecked")
                        Map<String, Object> apps = (Map<String, Object>) rawApps;
                        String revisionId = snapshot.child("revisionId").getValue(String.class);
                        ParentAppInventoryCache.save(
                                ChildNotificationsActivity.this,
                                parentUserId,
                                childDeviceId,
                                revisionId != null ? revisionId : "",
                                apps);
                        applyAppInventoryIcons(apps);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.w(TAG, "App inventory icon load cancelled: " + error.getMessage());
                    }
                });
        loadAppCatalogIcons();
    }

    private void loadAppCatalogIcons() {
        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("app_catalog")
                .child(childDeviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        Object rawCatalog = snapshot.getValue();
                        if (rawCatalog instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> catalog = (Map<String, Object>) rawCatalog;
                            applyAppInventoryIcons(catalog);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.w(TAG, "App catalog icon load cancelled: " + error.getMessage());
                    }
                });
    }
    private void applyAppInventoryIcons(Map<String, Object> apps) {
        for (Object rawApp : apps.values()) {
            if (!(rawApp instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> app = (Map<String, Object>) rawApp;
            String packageName = stringValue(app.get("packageName"));
            String iconBase64 = stringValue(app.get("iconBase64"));
            if (packageName != null && !packageName.isEmpty()
                    && iconBase64 != null && !iconBase64.isEmpty()) {
                appIconBase64ByPackage.put(packageName, iconBase64);
            }
        }
        if (appStatusAdapter != null) {
            appStatusAdapter.setIconBase64ByPackage(appIconBase64ByPackage);
        }
    }

    private String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }
    private void setupFilterToggle() {
        if (btnPermissionStatus == null || btnAppStatus == null)
            return;

        updateTabAppearance(true);

        btnPermissionStatus.setOnClickListener(v -> {
            updateTabAppearance(true);
            showPermissionStatusView();
            markPermissionStatusAsRead();
        });

        btnAppStatus.setOnClickListener(v -> {
            updateTabAppearance(false);
            showAppStatusView();
            markAppStatusAsRead();
        });
    }

    private void setupPermissionHistoryClear() {
        if (btnClearPermissionHistory == null) {
            return;
        }
        btnClearPermissionHistory.setOnClickListener(v -> confirmClearPermissionHistory());
    }

    private void confirmClearPermissionHistory() {
        if (permissionEvents.isEmpty() && sentinelProtectionEvents.isEmpty()) {
            Toast.makeText(this, "No permission history to clear", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Clear permission history?")
                .setMessage("This clears the permission activity history for this child. Current permission status will stay unchanged.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> clearPermissionHistory())
                .show();
    }

    private void clearPermissionHistory() {
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            return;
        }

        if (btnClearPermissionHistory != null) {
            btnClearPermissionHistory.setEnabled(false);
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("v2/permission_logs/" + childDeviceId, null);
        for (String eventKey : sentinelProtectionEventKeys) {
            if (eventKey != null && !eventKey.isEmpty()) {
                updates.put("v2/app_events/" + childDeviceId + "/" + eventKey, null);
            }
        }
        long currentTime = System.currentTimeMillis();
        updates.put("v2/parent_notification_state/" + parentUserId + "/" + childDeviceId
                + "/" + KEY_LAST_READ_PERMISSION_STATUS, currentTime);

        FirebaseDatabase.getInstance().getReference()
                .updateChildren(updates)
                .addOnSuccessListener(unused -> {
                    permissionStatusLastReadTime = currentTime;
                    permissionEvents.clear();
                    sentinelProtectionEvents.clear();
                    sentinelProtectionEventKeys.clear();
                    renderPermissionEvents();
                    recalculateUnreadBadges();
                    Toast.makeText(this, "Permission history cleared", Toast.LENGTH_SHORT).show();
                    if (btnClearPermissionHistory != null) {
                        btnClearPermissionHistory.setEnabled(true);
                    }
                })
                .addOnFailureListener(error -> {
                    Log.e(TAG, "Failed to clear permission history", error);
                    Toast.makeText(this, "Could not clear permission history", Toast.LENGTH_SHORT).show();
                    if (btnClearPermissionHistory != null) {
                        btnClearPermissionHistory.setEnabled(true);
                    }
                });
    }
    private void updateTabAppearance(boolean permissionSelected) {
        int selectedBackground = ContextCompat.getColor(this, R.color.primary_600);
        int selectedText = ContextCompat.getColor(this, android.R.color.white);
        int unselectedBackground = ContextCompat.getColor(this, android.R.color.transparent);
        int unselectedText = ContextCompat.getColor(this, R.color.neutral_600);

        btnPermissionStatus.setBackgroundTintList(ColorStateList.valueOf(
                permissionSelected ? selectedBackground : unselectedBackground));
        btnPermissionStatus.setTextColor(permissionSelected ? selectedText : unselectedText);
        btnAppStatus.setBackgroundTintList(ColorStateList.valueOf(
                permissionSelected ? unselectedBackground : selectedBackground));
        btnAppStatus.setTextColor(permissionSelected ? unselectedText : selectedText);
    }

    /**
     * Show Permission Status view
     */
    private void showPermissionStatusView() {
        containerPermissionStatus.setVisibility(View.VISIBLE);
        containerAppStatus.setVisibility(View.GONE);
    }

    /**
     * Show App Status view
     */
    private void showAppStatusView() {
        containerPermissionStatus.setVisibility(View.GONE);
        containerAppStatus.setVisibility(View.VISIBLE);

        // Setup app events listener if not already done
        if (appEventsRef == null) {
            setupAppEventsListener();
        }
    }

    private void setupFirebaseListeners() {
        setupCanonicalPermissionListeners();
    }
    private void setupCanonicalPermissionListeners() {
        eventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("permission_logs")
                .child(childDeviceId);
        eventsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<PermissionEvent> events = new ArrayList<>();
                for (DataSnapshot eventSnapshot : snapshot.getChildren()) {
                    PermissionEvent event = eventSnapshot.getValue(PermissionEvent.class);
                    if (event != null) {
                        events.add(event);
                    }
                }
                Collections.sort(events,
                        (e1, e2) -> Long.compare(e2.getTimestamp(), e1.getTimestamp()));
                permissionEvents.clear();
                permissionEvents.addAll(events);
                renderPermissionEvents();
                recalculateUnreadBadges();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Canonical permission logs cancelled: " + error.getMessage());
            }
        };
        eventsRef.addValueEventListener(eventsListener);

        statusRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("permissions_current")
                .child(childDeviceId);
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                updateStatusIcons(
                        Boolean.TRUE.equals(snapshot.child("accessibility").getValue(Boolean.class)),
                        Boolean.TRUE.equals(snapshot.child("usageStats").getValue(Boolean.class)),
                        Boolean.TRUE.equals(snapshot.child("notifications").getValue(Boolean.class)),
                        Boolean.TRUE.equals(snapshot.child("batteryOptimization").getValue(Boolean.class)));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Canonical permission status cancelled: " + error.getMessage());
            }
        };
        statusRef.addValueEventListener(statusListener);
    }

    private void updateStatusIcons(boolean accessibility, boolean usageStats,
            boolean notifications, boolean batteryOpt) {
        int greenColor = ContextCompat.getColor(this, R.color.success_600);
        int redColor = ContextCompat.getColor(this, R.color.error_500);

        ivAccessibility.setColorFilter(accessibility ? greenColor : redColor);
        ivUsageStats.setColorFilter(usageStats ? greenColor : redColor);
        ivNotifications.setColorFilter(notifications ? greenColor : redColor);
        ivBattery.setColorFilter(batteryOpt ? greenColor : redColor);
        ivAccessibility.setBackgroundResource(accessibility
                ? R.drawable.bg_circle_success_soft : R.drawable.bg_circle_error_soft);
        ivUsageStats.setBackgroundResource(usageStats
                ? R.drawable.bg_circle_success_soft : R.drawable.bg_circle_error_soft);
        ivNotifications.setBackgroundResource(notifications
                ? R.drawable.bg_circle_success_soft : R.drawable.bg_circle_error_soft);
        ivBattery.setBackgroundResource(batteryOpt
                ? R.drawable.bg_circle_success_soft : R.drawable.bg_circle_error_soft);

        int activeCount = 0;
        if (accessibility)
            activeCount++;
        if (usageStats)
            activeCount++;
        if (notifications)
            activeCount++;
        if (batteryOpt)
            activeCount++;

        if (activeCount == 4) {
            tvStatusSummary.setText("All permissions active ✓");
            tvStatusSummary.setTextColor(greenColor);
        } else if (activeCount == 0) {
            tvStatusSummary.setText("All permissions disabled");
            tvStatusSummary.setTextColor(redColor);
        } else {
            tvStatusSummary.setText((4 - activeCount) + " permission(s) need attention");
            tvStatusSummary.setTextColor(ContextCompat.getColor(this, R.color.warning_600));
        }
    }

    private void updateEmptyState(boolean isEmpty) {
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvEvents.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void renderPermissionEvents() {
        List<PermissionEvent> combinedEvents = new ArrayList<>();
        combinedEvents.addAll(permissionEvents);
        combinedEvents.addAll(sentinelProtectionEvents);
        Collections.sort(combinedEvents,
                (e1, e2) -> Long.compare(e2.getTimestamp(), e1.getTimestamp()));
        adapter.setEvents(combinedEvents);
        updateEmptyState(combinedEvents.isEmpty());
    }

    private boolean isAppInstallStatusEvent(AppStatusEvent event) {
        if (event == null || event.getAction() == null) {
            return false;
        }
        return "INSTALLED".equals(event.getAction())
                || "UNINSTALLED".equals(event.getAction());
    }

    private boolean isSentinelProtectionEvent(AppStatusEvent event) {
        if (event == null || event.getAction() == null) {
            return false;
        }
        if (isAppInstallStatusEvent(event)) {
            return false;
        }
        String packageName = event.getPackageName();
        String appName = event.getAppName();
        return "online.monarchlabs.sentinel".equals(packageName)
                || "Sentinel Protection".equals(appName);
    }

    private PermissionEvent toSentinelProtectionPermissionEvent(AppStatusEvent event) {
        String rawAction = event.getAction();
        boolean restored = "RESTORED".equals(rawAction);
        long timestamp = event.getTimestamp();
        return new PermissionEvent(
                "Uninstall Protection",
                restored ? "ACTIVATED" : "DEACTIVATED",
                getSentinelProtectionEffect(rawAction),
                timestamp,
                formatEventDate(timestamp),
                formatEventTime(timestamp));
    }

    private String getSentinelProtectionEffect(String rawAction) {
        if ("RESTORED".equals(rawAction)) {
            return "Sentinel protection communication is restored.";
        }
        if ("LIKELY_UNINSTALLED".equals(rawAction)) {
            return "Sentinel has not communicated for a long time. The child app is likely removed or disabled.";
        }
        return "Sentinel has stopped communicating. Review uninstall protection on the child device.";
    }

    private String formatEventDate(long timestamp) {
        return new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                .format(new Date(timestamp));
    }

    private String formatEventTime(long timestamp) {
        return new SimpleDateFormat("hh:mm a", Locale.getDefault())
                .format(new Date(timestamp));
    }
    private void setupAppEventsListener() {
        if (appEventsRef != null) {
            return;
        }

        String firebasePath = "v2/app_events/" + childDeviceId;
        Log.d(TAG, "Setting up App Events Listener: " + firebasePath);

        appEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("app_events")
                .child(childDeviceId);

        appEventsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                appEvents.clear();
                sentinelProtectionEvents.clear();
                sentinelProtectionEventKeys.clear();

                for (DataSnapshot eventSnap : snapshot.getChildren()) {
                    AppStatusEvent event = eventSnap.getValue(AppStatusEvent.class);
                    if (event == null) {
                        Log.w(TAG, "App event is null for snapshot: " + eventSnap.getKey());
                        continue;
                    }

                    if (isAppInstallStatusEvent(event)) {
                        appEvents.add(event);
                    } else if (isSentinelProtectionEvent(event)) {
                        sentinelProtectionEvents.add(toSentinelProtectionPermissionEvent(event));
                        if (eventSnap.getKey() != null) {
                            sentinelProtectionEventKeys.add(eventSnap.getKey());
                        }
                    }
                }

                Collections.sort(appEvents,
                        (e1, e2) -> Long.compare(e2.getTimestamp(), e1.getTimestamp()));
                appStatusAdapter.notifyDataSetChanged();
                updateAppStatusEmptyState(appEvents.isEmpty());
                renderPermissionEvents();
                recalculateUnreadBadges();
                Log.d(TAG, "Loaded app status events=" + appEvents.size()
                        + ", protection events=" + sentinelProtectionEvents.size());
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "App events listener cancelled: " + error.getMessage());
            }
        };

        appEventsRef.addValueEventListener(appEventsListener);
    }

    /**
     * Setup badge counters to show unread notifications count.
     * Tab badges use their own read timestamps so App Status does not get
     * cleared just because the Permission Status tab was opened.
     */
    private void setupBadgeCounters() {
        Log.d(TAG, "Setting up badge counters for device: " + childDeviceId);

        notificationStateListenerRef = notificationStateRef();
        notificationStateListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                permissionStatusLastReadTime = Math.max(
                        permissionStatusLastReadTime,
                        readLong(snapshot.child(KEY_LAST_READ_PERMISSION_STATUS)));
                appStatusLastReadTime = Math.max(
                        appStatusLastReadTime,
                        readLong(snapshot.child(KEY_LAST_READ_APP_STATUS)));
                notificationReadStateLoaded = true;
                recalculateUnreadBadges();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to get notification read state: " + error.getMessage());
                notificationReadStateLoaded = true;
            }
        };
        notificationStateListenerRef.addValueEventListener(notificationStateListener);
    }

    private long readLong(DataSnapshot snapshot) {
        Long value = snapshot.getValue(Long.class);
        return value != null ? value : 0L;
    }

    private void recalculateUnreadBadges() {
        if (!notificationReadStateLoaded) {
            return;
        }

        int permissionCount = 0;
        for (PermissionEvent event : permissionEvents) {
            if (event.getTimestamp() > permissionStatusLastReadTime) {
                permissionCount++;
            }
        }

        int protectionCount = 0;
        for (PermissionEvent event : sentinelProtectionEvents) {
            if (event.getTimestamp() > permissionStatusLastReadTime) {
                protectionCount++;
            }
        }

        int appCount = 0;
        for (AppStatusEvent event : appEvents) {
            if (event.getTimestamp() > appStatusLastReadTime) {
                appCount++;
            }
        }

        permissionUnreadCount = permissionCount;
        sentinelProtectionUnreadCount = protectionCount;
        appUnreadCount = appCount;
        updatePermissionBadge(permissionUnreadCount + sentinelProtectionUnreadCount);
        updateAppStatusBadge(appUnreadCount);
    }
    /**
     * Update permission status badge
     */
    private void updatePermissionBadge(int count) {
        runOnUiThread(() -> {
            if (tvPermissionBadge != null) {
                if (count > 0) {
                    tvPermissionBadge.setVisibility(View.VISIBLE);
                    tvPermissionBadge.setText(count > 99 ? "99+" : String.valueOf(count));
                    Log.d(TAG, "🔔 Permission badge updated: " + count);
                } else {
                    tvPermissionBadge.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * Update app status badge
     */
    private void updateAppStatusBadge(int count) {
        runOnUiThread(() -> {
            if (tvAppStatusBadge != null) {
                if (count > 0) {
                    tvAppStatusBadge.setVisibility(View.VISIBLE);
                    tvAppStatusBadge.setText(count > 99 ? "99+" : String.valueOf(count));
                    Log.d(TAG, "🔔 App status badge updated: " + count);
                } else {
                    tvAppStatusBadge.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * Update app status empty state
     */
    private void updateAppStatusEmptyState(boolean isEmpty) {
        emptyStateAppStatus.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvAppStatus.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /**
     * 🔔 Mark notifications as read by saving current timestamp
     */
    private DatabaseReference notificationStateRef() {
        return FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_notification_state")
                .child(parentUserId)
                .child(childDeviceId);
    }
    private void markNotificationsAsSeenForBell() {
        if (childDeviceId == null) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        setResult(RESULT_OK);

        notificationStateRef().child(KEY_LAST_READ_ALL)
                .setValue(currentTime)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Marked notification bell as seen at: " + currentTime);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to mark notification bell as seen: " + e.getMessage());
                });
    }

    private void markPermissionStatusAsRead() {
        if (childDeviceId == null) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        permissionStatusLastReadTime = currentTime;
        permissionUnreadCount = 0;
        sentinelProtectionUnreadCount = 0;
        updatePermissionBadge(0);
        notificationStateRef().child(KEY_LAST_READ_PERMISSION_STATUS).setValue(currentTime);
    }

    private void markAppStatusAsRead() {
        if (childDeviceId == null) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        appStatusLastReadTime = currentTime;
        appUnreadCount = 0;
        updateAppStatusBadge(0);
        notificationStateRef().child(KEY_LAST_READ_APP_STATUS).setValue(currentTime);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Remove listeners to prevent memory leaks
        if (eventsRef != null && eventsListener != null) {
            eventsRef.removeEventListener(eventsListener);
        }
        if (statusRef != null && statusListener != null) {
            statusRef.removeEventListener(statusListener);
        }
        if (appEventsRef != null && appEventsListener != null) {
            appEventsRef.removeEventListener(appEventsListener);
        }
        if (notificationStateListenerRef != null && notificationStateListener != null) {
            notificationStateListenerRef.removeEventListener(notificationStateListener);
        }
    }
}
