package online.monarchlabs.sentinel.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import online.monarchlabs.sentinel.ChildDashboardActivity;
import online.monarchlabs.sentinel.ChildMonitoringDisclosureActivity;
import online.monarchlabs.sentinel.AppTimerLocalStore;
import online.monarchlabs.sentinel.R;
import online.monarchlabs.sentinel.SessionManager;
import online.monarchlabs.sentinel.TimerStatusActivity;
import online.monarchlabs.sentinel.utils.CrashlyticsLogger;
import online.monarchlabs.sentinel.utils.SUsageDataManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Monitors per-app daily timers using real UsageStats.
 * On expiry: notifies child and parent and tracks post-expiry usage ("exceed").
 * App timers are informational and do not block apps.
 */
public class AppTimerService extends Service {
    private static final String TAG = "AppTimerService";
    private static final String CHANNEL_ID = "app_timer_monitoring_v2";
    private static final String LEGACY_CHANNEL_ID = "app_timer_channel";
    private static final String EXPIRY_CHANNEL_ID = "timer_expiry_channel";
    private static final int NOTIFICATION_ID = 2101;
    private static final int LEGACY_NOTIFICATION_ID = 2001;
    private static final int EXPIRY_NOTIFICATION_ID_BASE = 3000;
    private static final long CHECK_INTERVAL_MS = 1000;
    private static final long RECONCILE_INTERVAL_MS = 10_000L;
    private static final long EXPIRY_NOTIF_INTERVAL_MS = 5 * 60 * 1000L;
    private static final String PREF_LAST_RESET_DATE = "last_timer_reset_date";
    private static final String PREF_LAST_RESET_AT = "last_timer_reset_at";
    private static final String PREF_LAST_RESET_ELAPSED = "last_timer_reset_elapsed";
    private static final String PREF_LEGACY_TIMER_BLOCKS = "app_timer_blocked_apps";
    private static final String STATE_ACTIVE = "ACTIVE";
    private static final String STATE_PAUSED = "PAUSED";
    private static final String STATE_EXPIRED = "EXPIRED";
    private static final String STATE_CANCELLED = "CANCELLED";

    private String deviceId;
    private Handler handler;
    private Runnable timerRunnable;
    private UsageStatsManager usageStatsManager;
    private DatabaseReference timersRef;
    private ChildEventListener timersListener;
    private DatabaseReference executionRef;
    private DatabaseReference syncRequestRef;
    private ChildEventListener syncRequestListener;
    private DatabaseReference connectedRef;
    private ValueEventListener connectedListener;
    private Map<String, AppTimer> activeTimers = new HashMap<>();
    private String currentForegroundApp = "";
    private NotificationManager notificationManager;
    private String lastResetDate = "";
    private boolean isFirebaseDataLoaded = false;
    private boolean hasSeenFirebaseConnectionState;
    private boolean firebaseConnected;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!ChildMonitoringDisclosureActivity.hasAcceptedDisclosure(this)) {
            Log.w(TAG, "Monitoring disclosure has not been accepted; stopping timer service");
            stopSelf();
            return;
        }
        Log.d(TAG, "AppTimerService created");

        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        handler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        createExpiryNotificationChannel();

        lastResetDate = getSharedPreferences("timer_prefs", MODE_PRIVATE)
                .getString(PREF_LAST_RESET_DATE, "");

        clearLegacyTimerExpiryBlocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ChildMonitoringDisclosureActivity.hasAcceptedDisclosure(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!tryStartForeground()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String requestedDeviceId = intent != null
                ? intent.getStringExtra("deviceId")
                : null;
        if (requestedDeviceId != null && !requestedDeviceId.isEmpty()) {
            deviceId = requestedDeviceId;
        }

        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = new SessionManager(this).getChildDeviceId();
        }

        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "No child device ID available; timer service cannot start");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String authUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                : null;
        if (authUid == null || authUid.isEmpty()) {
            Log.w(TAG, "No Firebase child auth user; timer service waiting for fresh QR pairing");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_owners")
                .child(deviceId)
                .child("childAuthUid")
                .get()
                .addOnSuccessListener(snapshot -> {
                    String ownerChildUid = snapshot.getValue(String.class);
                    if (!authUid.equals(ownerChildUid)) {
                        Log.w(TAG, "Timer service skipped: v2 ownership missing for device " + deviceId);
                        stopSelf(startId);
                        return;
                    }
                    startTimerRuntime();
                })
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Timer service skipped: cannot verify v2 ownership: "
                            + error.getMessage());
                    stopSelf(startId);
                });

        return START_STICKY;
    }

    private void startTimerRuntime() {
        timersRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("device_policies")
                .child(deviceId)
                .child("app_timers");
        executionRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("timer_execution")
                .child(deviceId);
        syncRequestRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("timer_state_requests")
                .child(deviceId);

        loadCachedTimers();
        setupTimerListener();
        setupSyncRequestListener();
        setupConnectionListener();
        startMonitoring();
    }

    private boolean tryStartForeground() {
        try {
            startForeground(NOTIFICATION_ID, createNotification());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Foreground start rejected; stopping timer service: " + e.getMessage());
            CrashlyticsLogger.recordForegroundServiceRejected(
                    TAG,
                    "specialUse:continuous_parental_control_app_timer_monitoring",
                    e);
            return false;
        }
    }

    private void setupTimerListener() {
        if (timersListener != null && timersRef != null) {
            timersRef.removeEventListener(timersListener);
        }

        timersListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                applyTimerPolicy(snapshot);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                applyTimerPolicy(snapshot);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                cancelTimerPolicy(snapshot);
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Timer policy listener cancelled: " + error.getMessage());
            }
        };
        timersRef.addChildEventListener(timersListener);

        timersRef.get()
                .addOnSuccessListener(this::reconcileCachedTimerPolicies)
                .addOnFailureListener(error ->
                        Log.w(TAG, "Timer policy startup reconciliation deferred: "
                                + error.getMessage()));
    }

    private void applyTimerPolicy(DataSnapshot timerSnapshot) {
        try {
            String packageName = timerSnapshot.child("packageName").getValue(String.class);
            if (packageName == null || packageName.isEmpty()) {
                packageName = timerSnapshot.getKey();
            }
            if (packageName == null) {
                return;
            }

            Long remainingMs = getTimerLong(timerSnapshot, "remainingTimeMillis");
            Long dailyLimitMs = getTimerLong(timerSnapshot, "dailyLimitMillis");
            if (dailyLimitMs == null) {
                dailyLimitMs = getTimerLong(timerSnapshot, "totalTimeMillis");
            }
            if (dailyLimitMs == null && remainingMs != null) {
                dailyLimitMs = remainingMs;
            }
            if (dailyLimitMs == null || dailyLimitMs <= 0L) {
                return;
            }

            Boolean active = timerSnapshot.child("active").getValue(Boolean.class);
            Boolean expired = timerSnapshot.child("expired").getValue(Boolean.class);
            Long exceedMs = getTimerLong(timerSnapshot, "exceedTimeMillis");
            Long policyVersion = getTimerLong(timerSnapshot, "policyVersion");
            if (policyVersion == null) {
                policyVersion = getTimerLong(timerSnapshot, "lastUpdated");
            }
            if (policyVersion == null) {
                policyVersion = getTimerLong(timerSnapshot, "createdAt");
            }
            long incomingPolicyVersion = policyVersion != null
                    ? policyVersion : System.currentTimeMillis();
            String remoteState = timerSnapshot.child("state").getValue(String.class);

            AppTimer timer = activeTimers.get(packageName);
            if (timer == null) {
                timer = new AppTimer();
                activeTimers.put(packageName, timer);
                timer.lastReconcileTime = 0L;
            }

            timer.packageName = packageName;
            timer.key = timerSnapshot.getKey();
            boolean firstPolicy = timer.policyVersion == 0L;
            boolean newerPolicy = incomingPolicyVersion > timer.policyVersion;
            if (!firstPolicy && !newerPolicy) {
                return;
            }
            if (!firstPolicy) {
                reconcileTimer(timer, System.currentTimeMillis());
            }

            timer.dailyLimitMillis = dailyLimitMs;
            timer.remainingTimeMillis = remainingMs != null
                    ? remainingMs : timer.dailyLimitMillis;
            timer.activationRemainingMillis = timer.remainingTimeMillis;
            timer.exceedTimeMillis = exceedMs != null ? exceedMs : 0L;
            timer.policyVersion = incomingPolicyVersion;
            timer.state = normalizeRemoteState(
                    remoteState, active, expired, timer.remainingTimeMillis);
            timer.active = STATE_ACTIVE.equals(timer.state);
            timer.expiryNotified = STATE_EXPIRED.equals(timer.state);
            timer.expiredAt = timer.expiryNotified
                    ? valueOrZero(getTimerLong(timerSnapshot, "expiredAt")) : 0L;
            timer.cancelledAt = 0L;
            timer.usageAtSetMillis = getTodayUsageForApp(packageName);
            timer.lastEvaluatedAt = System.currentTimeMillis();
            timer.lastEvaluatedElapsedRealtime = SystemClock.elapsedRealtime();
            timer.executionVersion++;
            timer.pendingSync = true;

            if (timer.active && timer.remainingTimeMillis > 0L) {
                timer.expiryNotified = false;
                timer.exceedTimeMillis = 0L;
                cancelExpiryNotification(timer);
            }
            cacheTimer(timer);
            isFirebaseDataLoaded = true;
            Log.d(TAG, "Applied timer policy v" + timer.policyVersion
                    + " locally for " + packageName + " state=" + timer.state);
        } catch (Exception error) {
            Log.w(TAG, "Error parsing timer policy: " + error.getMessage());
        }
    }

    private void cancelTimerPolicy(DataSnapshot timerSnapshot) {
        String packageName = timerSnapshot.child("packageName").getValue(String.class);
        if (packageName == null || packageName.isEmpty()) {
            String removedKey = timerSnapshot.getKey();
            for (AppTimer timer : activeTimers.values()) {
                if (removedKey != null && removedKey.equals(timer.key)) {
                    packageName = timer.packageName;
                    break;
                }
            }
        }
        cancelTimer(packageName);
    }

    private void cancelTimer(String packageName) {
        if (packageName == null) {
            return;
        }
        AppTimer timer = activeTimers.remove(packageName);
        if (timer == null) {
            AppTimerLocalStore.remove(this, deviceId, packageName);
            return;
        }

        cancelExpiryNotification(timer);
        AppTimerLocalStore.remove(this, deviceId, packageName);
        if (executionRef != null && timer.key != null && !timer.key.isEmpty()) {
            executionRef.child(timer.key).removeValue()
                    .addOnFailureListener(error ->
                            Log.w(TAG, "Timer execution cleanup deferred: "
                                    + error.getMessage()));
        }
        Log.d(TAG, "Timer removed locally after parent policy deletion: "
                + packageName);
    }
    private void reconcileCachedTimerPolicies(DataSnapshot snapshot) {
        Set<String> currentKeys = new HashSet<>();
        for (DataSnapshot timerSnapshot : snapshot.getChildren()) {
            if (timerSnapshot.getKey() != null) {
                currentKeys.add(timerSnapshot.getKey());
            }
        }
        for (AppTimer timer : new ArrayList<>(activeTimers.values())) {
            if (timer.key == null || !currentKeys.contains(timer.key)) {
                cancelTimer(timer.packageName);
            }
        }
        isFirebaseDataLoaded = true;
    }
    private void setupSyncRequestListener() {
        if (syncRequestRef == null) {
            return;
        }
        if (syncRequestListener != null) {
            syncRequestRef.removeEventListener(syncRequestListener);
        }
        syncRequestListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                processTimerStateRequest(snapshot);
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {
                processTimerStateRequest(snapshot);
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Timer state request listener cancelled: " + error.getMessage());
            }
        };
        syncRequestRef.addChildEventListener(syncRequestListener);
    }

    private void processTimerStateRequest(DataSnapshot parentRequestSnapshot) {
        String parentDeviceId = parentRequestSnapshot.getKey();
        DataSnapshot request = parentRequestSnapshot.child("request");
        String requestId = request.child("requestId").getValue(String.class);
        if (parentDeviceId == null || requestId == null || requestId.isEmpty()) {
            return;
        }

        String preferenceKey = "last_request_id_" + parentDeviceId;
        android.content.SharedPreferences preferences = getSharedPreferences(
                "app_timer_sync_" + deviceId, MODE_PRIVATE);
        if (requestId.equals(preferences.getString(preferenceKey, ""))) {
            return;
        }
        preferences.edit().putString(preferenceKey, requestId).apply();
        syncAllTimerSnapshots("parent_request", requestId, parentDeviceId);
    }
    private void setupConnectionListener() {
        if (connectedRef != null && connectedListener != null) {
            connectedRef.removeEventListener(connectedListener);
        }
        connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected");
        connectedListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                boolean connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                boolean reconnected = hasSeenFirebaseConnectionState
                        && !firebaseConnected && connected;
                boolean initialRecovery = !hasSeenFirebaseConnectionState && connected;
                hasSeenFirebaseConnectionState = true;
                firebaseConnected = connected;
                if (reconnected || initialRecovery) {
                    flushPendingSnapshots("connection_recovered");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Firebase connection listener cancelled: " + error.getMessage());
            }
        };
        connectedRef.addValueEventListener(connectedListener);
    }

    private void loadCachedTimers() {
        activeTimers.clear();
        for (AppTimerLocalStore.TimerRecord record :
                AppTimerLocalStore.load(this, deviceId)) {
            if (record.packageName == null || record.packageName.isEmpty()) {
                continue;
            }
            if (STATE_CANCELLED.equalsIgnoreCase(record.state)) {
                AppTimerLocalStore.remove(this, deviceId, record.packageName);
                continue;
            }
            AppTimer timer = new AppTimer();
            timer.packageName = record.packageName;
            timer.key = record.key;
            timer.remainingTimeMillis = record.remainingTimeMillis;
            timer.dailyLimitMillis = record.dailyLimitMillis;
            timer.exceedTimeMillis = record.exceedTimeMillis;
            timer.usageAtSetMillis = record.usageAtSetMillis;
            timer.activationRemainingMillis = record.activationRemainingMillis > 0
                    ? record.activationRemainingMillis : record.remainingTimeMillis;
            timer.policyVersion = record.policyVersion;
            timer.executionVersion = record.executionVersion;
            timer.lastEvaluatedAt = record.lastEvaluatedAt;
            timer.lastEvaluatedElapsedRealtime = record.lastEvaluatedElapsedRealtime;
            timer.expiredAt = record.expiredAt;
            timer.cancelledAt = record.cancelledAt;
            timer.lastSyncedExecutionVersion = record.lastSyncedExecutionVersion;
            timer.state = record.state;
            timer.lastResetDate = record.lastResetDate;
            timer.pendingSync = record.pendingSync;
            timer.active = record.active;
            timer.expiryNotified = record.expired;
            activeTimers.put(timer.packageName, timer);
        }
        Log.d(TAG, "Restored " + activeTimers.size() + " timers from local storage");
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

    private long valueOrZero(Long value) {
        return value != null ? value : 0L;
    }

    private String normalizeRemoteState(String state, Boolean active,
            Boolean expired, long remainingMillis) {
        if (state != null) {
            String normalized = state.trim().toUpperCase(Locale.US);
            if (STATE_ACTIVE.equals(normalized)
                    || STATE_PAUSED.equals(normalized)
                    || STATE_EXPIRED.equals(normalized)
                    || STATE_CANCELLED.equals(normalized)) {
                return normalized;
            }
        }
        if (Boolean.TRUE.equals(expired)
                || (remainingMillis <= 0 && !Boolean.TRUE.equals(active))) {
            return STATE_EXPIRED;
        }
        return Boolean.TRUE.equals(active) ? STATE_ACTIVE : STATE_PAUSED;
    }

    private AppTimerLocalStore.TimerRecord toCacheRecord(AppTimer timer) {
        AppTimerLocalStore.TimerRecord record = new AppTimerLocalStore.TimerRecord();
        record.packageName = timer.packageName;
        record.key = timer.key;
        record.appName = getAppName(timer.packageName);
        record.remainingTimeMillis = timer.remainingTimeMillis;
        record.dailyLimitMillis = timer.dailyLimitMillis;
        record.exceedTimeMillis = timer.exceedTimeMillis;
        record.usageAtSetMillis = timer.usageAtSetMillis;
        record.activationRemainingMillis = timer.activationRemainingMillis;
        record.policyVersion = timer.policyVersion;
        record.executionVersion = timer.executionVersion;
        record.lastEvaluatedAt = timer.lastEvaluatedAt;
        record.lastEvaluatedElapsedRealtime = timer.lastEvaluatedElapsedRealtime;
        record.expiredAt = timer.expiredAt;
        record.cancelledAt = timer.cancelledAt;
        record.lastSyncedExecutionVersion = timer.lastSyncedExecutionVersion;
        record.state = timer.state;
        record.lastResetDate = timer.lastResetDate;
        record.pendingSync = timer.pendingSync;
        record.active = timer.active;
        record.expired = timer.expiryNotified;
        return record;
    }

    private void cacheTimer(AppTimer timer) {
        AppTimerLocalStore.put(this, deviceId, toCacheRecord(timer));
    }

    private void startMonitoring() {
        if (handler != null && timerRunnable != null) {
            handler.removeCallbacks(timerRunnable);
        }

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                checkAndUpdateTimers();
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        handler.post(timerRunnable);
    }

    private void checkAndUpdateTimers() {
        long currentTime = System.currentTimeMillis();
        checkMidnightReset();

        for (AppTimer timer : activeTimers.values()) {
            if (STATE_CANCELLED.equals(timer.state)) {
                continue;
            }
            if (currentTime - timer.lastReconcileTime < RECONCILE_INTERVAL_MS
                    && timer.lastReconcileTime != 0) {
                continue;
            }
            timer.lastReconcileTime = currentTime;
            reconcileTimer(timer, currentTime);
            cacheTimer(timer);
        }

        String foregroundApp = getForegroundApp();
        if (foregroundApp == null || foregroundApp.isEmpty()) {
            return;
        }

        if (!foregroundApp.equals(currentForegroundApp)) {
            currentForegroundApp = foregroundApp;
        }

        AppTimer fgTimer = activeTimers.get(foregroundApp);
        if (fgTimer != null && fgTimer.expiryNotified) {
            fgTimer.accumulatedActiveMs += CHECK_INTERVAL_MS;
            long intervalsPassed = fgTimer.accumulatedActiveMs / EXPIRY_NOTIF_INTERVAL_MS;
            if (intervalsPassed > fgTimer.lastNotifIntervalCount) {
                fgTimer.lastNotifIntervalCount = intervalsPassed;
                showExpiryNotification(fgTimer);
            }
        }
    }

    private void reconcileTimer(AppTimer timer, long currentTime) {
        if (timer == null || STATE_CANCELLED.equals(timer.state)
                || STATE_PAUSED.equals(timer.state)) {
            return;
        }

        detectClockDiscontinuity(timer, currentTime);
        long currentUsage = getTodayUsageForApp(timer.packageName);
        if (timer.usageAtSetMillis < 0) {
            timer.usageAtSetMillis = currentUsage;
            timer.activationRemainingMillis = timer.remainingTimeMillis > 0
                    ? timer.remainingTimeMillis : timer.dailyLimitMillis;
        }

        long usedSinceActivation = Math.max(0L, currentUsage - timer.usageAtSetMillis);
        long budgetAtActivation = timer.activationRemainingMillis > 0
                ? timer.activationRemainingMillis : timer.remainingTimeMillis;
        long previousRemaining = timer.remainingTimeMillis;

        if (STATE_ACTIVE.equals(timer.state)) {
            timer.remainingTimeMillis = Math.max(
                    0L, budgetAtActivation - usedSinceActivation);
            if (timer.remainingTimeMillis <= 0 && !timer.expiryNotified) {
                handleTimerExpiry(timer, usedSinceActivation);
            }
        } else if (STATE_EXPIRED.equals(timer.state)) {
            timer.exceedTimeMillis = Math.max(
                    timer.exceedTimeMillis, usedSinceActivation - budgetAtActivation);
        }

        timer.lastEvaluatedAt = currentTime;
        timer.lastEvaluatedElapsedRealtime = SystemClock.elapsedRealtime();
        if (previousRemaining != timer.remainingTimeMillis) {
            timer.executionVersion++;
            timer.pendingSync = true;
        }
    }

    private void detectClockDiscontinuity(AppTimer timer, long currentTime) {
        if (timer.lastEvaluatedAt <= 0 || timer.lastEvaluatedElapsedRealtime <= 0) {
            return;
        }
        long wallDelta = currentTime - timer.lastEvaluatedAt;
        long elapsedDelta = SystemClock.elapsedRealtime()
                - timer.lastEvaluatedElapsedRealtime;
        if (elapsedDelta >= 0 && Math.abs(wallDelta - elapsedDelta) > 5 * 60_000L) {
            // Timer execution uses UsageStats, not wall-clock subtraction, so a
            // manual clock/timezone change cannot grant or consume timer budget.
            Log.w(TAG, "Clock discontinuity detected for " + timer.packageName
                    + "; recalculating from verified foreground usage");
        }
    }

    private void handleTimerExpiry(AppTimer timer, long usedSinceSet) {
        timer.expiryNotified = true;
        timer.active = false;
        timer.state = STATE_EXPIRED;
        timer.remainingTimeMillis = 0;
        timer.expiredAt = System.currentTimeMillis();
        timer.exceedTimeMillis = Math.max(
                0L, usedSinceSet - timer.activationRemainingMillis);
        timer.executionVersion++;
        timer.pendingSync = true;
        cacheTimer(timer);

        Log.d(TAG, "Timer expired for: " + timer.packageName + ", exceed=" + timer.exceedTimeMillis / 1000 + "s");
        showExpiryNotification(timer);
        syncExpiredTimerAndNotifyParent(timer);
    }

    private long getTodayUsageForApp(String packageName) {
        return SUsageDataManager.getInstance(this).getTodayForegroundUsageMillis(packageName);
    }

    private void checkMidnightReset() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String currentDate = sdf.format(new Date());

        if (currentDate.equals(lastResetDate)) {
            return;
        }
        if (!isFirebaseDataLoaded && activeTimers.isEmpty()) {
            return;
        }

        android.content.SharedPreferences timerPrefs =
                getSharedPreferences("timer_prefs", MODE_PRIVATE);
        long lastResetElapsed = timerPrefs.getLong(PREF_LAST_RESET_ELAPSED, 0L);
        long elapsedSinceReset = SystemClock.elapsedRealtime() - lastResetElapsed;
        if (!lastResetDate.isEmpty()
                && lastResetElapsed > 0
                && elapsedSinceReset >= 0
                && elapsedSinceReset < 20 * 60 * 60_000L) {
            // A timezone/manual-clock change crossed a calendar boundary shortly
            // after the last reset. Do not grant a second daily budget.
            Log.w(TAG, "Calendar date changed too soon after timer reset; "
                    + "treating it as a clock/timezone change");
            lastResetDate = currentDate;
            timerPrefs.edit().putString(PREF_LAST_RESET_DATE, currentDate).apply();
            return;
        }

        for (AppTimer timer : activeTimers.values()) {
            if (timer.dailyLimitMillis <= 0 || STATE_CANCELLED.equals(timer.state)) {
                continue;
            }
            timer.remainingTimeMillis = timer.dailyLimitMillis;
            timer.activationRemainingMillis = timer.dailyLimitMillis;
            timer.active = true;
            timer.state = STATE_ACTIVE;
            timer.expiryNotified = false;
            timer.exceedTimeMillis = 0;
            timer.expiredAt = 0L;
            timer.accumulatedActiveMs = 0;
            timer.lastNotifIntervalCount = 0;
            timer.usageAtSetMillis = getTodayUsageForApp(timer.packageName);
            timer.lastResetDate = currentDate;
            timer.executionVersion++;
            timer.pendingSync = true;
            cancelExpiryNotification(timer);
            cacheTimer(timer);
        }

        lastResetDate = currentDate;
        timerPrefs.edit()
                .putString(PREF_LAST_RESET_DATE, lastResetDate)
                .putLong(PREF_LAST_RESET_AT, System.currentTimeMillis())
                .putLong(PREF_LAST_RESET_ELAPSED, SystemClock.elapsedRealtime())
                .apply();
        flushPendingSnapshots("midnight_reset");
    }

    private Map<String, Object> buildTimerSnapshot(
            AppTimer timer, String reason, String requestId) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("deviceId", deviceId);
        snapshot.put("timerKey", timer.key);
        snapshot.put("packageName", timer.packageName);
        snapshot.put("appName", getAppName(timer.packageName));
        snapshot.put("state", timer.state);
        snapshot.put("active", STATE_ACTIVE.equals(timer.state));
        snapshot.put("expired", STATE_EXPIRED.equals(timer.state));
        snapshot.put("cancelled", STATE_CANCELLED.equals(timer.state));
        snapshot.put("remainingTimeMillis", timer.remainingTimeMillis);
        snapshot.put("dailyLimitMillis", timer.dailyLimitMillis);
        snapshot.put("exceedTimeMillis", timer.exceedTimeMillis);
        snapshot.put("policyVersion", timer.policyVersion);
        snapshot.put("executionVersion", timer.executionVersion);
        snapshot.put("evaluatedAt", System.currentTimeMillis());
        snapshot.put("expiredAt", timer.expiredAt);
        snapshot.put("cancelledAt", timer.cancelledAt);
        snapshot.put("reason", reason);
        if (requestId != null) {
            snapshot.put("requestId", requestId);
        }
        return snapshot;
    }

    private void syncAllTimerSnapshots(
            String reason, String requestId, String parentDeviceId) {
        syncTimerSnapshots(
                new ArrayList<>(activeTimers.values()),
                reason,
                requestId,
                parentDeviceId,
                null);
    }

    private void flushPendingSnapshots(String reason) {
        List<AppTimer> pendingTimers = new ArrayList<>();
        for (AppTimer timer : activeTimers.values()) {
            if (timer.pendingSync
                    && timer.executionVersion > timer.lastSyncedExecutionVersion) {
                pendingTimers.add(timer);
            }
        }
        if (!pendingTimers.isEmpty()) {
            syncTimerSnapshots(pendingTimers, reason, null, null, null);
        }
    }

    private void syncExpiredTimerAndNotifyParent(AppTimer timer) {
        List<AppTimer> expiredTimer = new ArrayList<>();
        expiredTimer.add(timer);
        syncTimerSnapshots(expiredTimer, "expired", null, null, timer);
    }

    private void syncTimerSnapshots(List<AppTimer> timers, String reason,
            String requestId, String parentDeviceId, AppTimer expiryTimer) {
        Map<String, Object> updates = new HashMap<>();
        Map<AppTimer, Long> syncedVersions = new HashMap<>();
        long now = System.currentTimeMillis();

        for (AppTimer timer : timers) {
            if (timer == null || timer.key == null) {
                continue;
            }
            reconcileTimer(timer, now);
            updates.put(
                    "v2/timer_execution/" + deviceId + "/" + timer.key,
                    buildTimerSnapshot(timer, reason, requestId));
            syncedVersions.put(timer, timer.executionVersion);
        }

        if (requestId != null && parentDeviceId != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "accepted");
            response.put("processedAt", now);
            response.put("processedRequestId", requestId);
            response.put("timerCount", syncedVersions.size());
            String responsePath = "v2/timer_state_requests/" + deviceId + "/"
                    + parentDeviceId + "/response";
            updates.put(responsePath, response);
        }

        if (expiryTimer != null && expiryTimer.key != null) {
            String eventId = expiryTimer.key + "_"
                    + expiryTimer.policyVersion + "_expired";
            updates.put(
                    "v2/timer_events/" + deviceId + "/" + eventId,
                    buildExpiryNotification(expiryTimer, eventId, now));
        }

        if (updates.isEmpty()) {
            return;
        }

        FirebaseDatabase.getInstance().getReference().updateChildren(updates)
                .addOnSuccessListener(ignored -> {
                    for (Map.Entry<AppTimer, Long> entry : syncedVersions.entrySet()) {
                        AppTimer timer = entry.getKey();
                        long syncedVersion = entry.getValue();
                        timer.lastSyncedExecutionVersion = syncedVersion;
                        timer.pendingSync = timer.executionVersion > syncedVersion;
                        cacheTimer(timer);
                    }
                })
                .addOnFailureListener(error -> {
                    for (AppTimer timer : syncedVersions.keySet()) {
                        timer.pendingSync = true;
                        cacheTimer(timer);
                    }
                    Log.w(TAG, "Timer snapshot batch deferred: " + error.getMessage());
                });
    }

    private Map<String, Object> buildExpiryNotification(
            AppTimer timer, String eventId, long timestamp) {
        String appName = getAppName(timer.packageName);
        Map<String, Object> notification = new HashMap<>();
        notification.put("eventId", eventId);
        notification.put("type", "daily_app_timer_expired");
        notification.put("deviceId", deviceId);
        notification.put("packageName", timer.packageName);
        notification.put("appName", appName);
        notification.put("dailyLimitMillis", timer.dailyLimitMillis);
        notification.put("exceedTimeMillis", timer.exceedTimeMillis);
        notification.put("timestamp", timestamp);
        notification.put("read", false);
        notification.put("title", "Timer expired: " + appName);
        notification.put(
                "message",
                appName + " daily limit reached. The app remains accessible.");
        return notification;
    }
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Child app timer monitoring",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Persistent notice that Sentinel is monitoring child app timers");
            channel.setShowBadge(false);

            if (notificationManager != null) {
                notificationManager.cancel(LEGACY_NOTIFICATION_ID);
                notificationManager.createNotificationChannel(channel);
                notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
            }
        }
    }

    private void createExpiryNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    EXPIRY_CHANNEL_ID,
                    "Timer Expired Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Alerts when app time limits are reached");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent dashboardIntent = new Intent(this, ChildDashboardActivity.class);
        dashboardIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                dashboardIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sentinel app timer monitoring is active")
                .setContentText("Daily app limits are tracked without blocking apps")
                .setSmallIcon(R.drawable.ic_timer_status)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void showExpiryNotification(AppTimer timer) {
        String appName = getAppName(timer.packageName);
        String exceedText = timer.exceedTimeMillis > 0
                ? formatDuration(timer.exceedTimeMillis) + " over limit"
                : "Daily limit reached - app remains accessible";

        Intent statusIntent = new Intent(this, TimerStatusActivity.class);
        statusIntent.putExtra(TimerStatusActivity.EXTRA_DEVICE_ID, deviceId);
        statusIntent.putExtra(TimerStatusActivity.EXTRA_IS_PARENT, false);
        statusIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                timer.packageName.hashCode(),
                statusIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, EXPIRY_CHANNEL_ID)
                .setContentTitle("Time's up: " + appName)
                .setContentText(exceedText)
                .setSmallIcon(R.drawable.ic_timer_status)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(Notification.CATEGORY_STATUS)
                .setSubText("Sentinel")
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .setAutoCancel(true)
                .build();

        int notifId = EXPIRY_NOTIFICATION_ID_BASE + Math.abs(timer.packageName.hashCode());
        if (notificationManager != null) {
            notificationManager.notify(notifId, notification);
        }
    }

    private void cancelExpiryNotification(AppTimer timer) {
        int notifId = EXPIRY_NOTIFICATION_ID_BASE + Math.abs(timer.packageName.hashCode());
        if (notificationManager != null) {
            notificationManager.cancel(notifId);
        }
    }

    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    public static String formatDuration(long millis) {
        long totalMinutes = millis / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else if (minutes > 0) {
            return minutes + "m";
        }
        return "<1m";
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        android.app.PendingIntent restartServicePendingIntent = android.app.PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);

        android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
        alarmService.set(android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent);

        super.onTaskRemoved(rootIntent);
    }

    private String getForegroundApp() {
        if (usageStatsManager == null) {
            return "";
        }

        long endTime = System.currentTimeMillis();
        long startTime = endTime - 10000;

        String eventApp = getForegroundAppFromEvents(startTime, endTime);
        if (!eventApp.isEmpty()) {
            return eventApp;
        }

        List<android.app.usage.UsageStats> stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

        if (stats != null) {
            String topPackageName = "";
            long lastTime = 0;
            for (android.app.usage.UsageStats stat : stats) {
                if (stat.getLastTimeUsed() > lastTime) {
                    lastTime = stat.getLastTimeUsed();
                    topPackageName = stat.getPackageName();
                }
            }
            if (lastTime > endTime - 2000 && !topPackageName.isEmpty()) {
                return topPackageName;
            }
        }

        return currentForegroundApp;
    }

    private String getForegroundAppFromEvents(long startTime, long endTime) {
        try {
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();
            String foregroundApp = "";
            long lastEventTime = 0;

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (event.getTimeStamp() > lastEventTime) {
                        foregroundApp = event.getPackageName();
                        lastEventTime = event.getTimeStamp();
                    }
                }
            }
            return foregroundApp;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && timerRunnable != null) {
            handler.removeCallbacks(timerRunnable);
        }
        if (timersListener != null && timersRef != null) {
            timersRef.removeEventListener(timersListener);
        }
        if (syncRequestListener != null && syncRequestRef != null) {
            syncRequestRef.removeEventListener(syncRequestListener);
        }
        if (connectedListener != null && connectedRef != null) {
            connectedRef.removeEventListener(connectedListener);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context, String deviceId) {
        Intent intent = new Intent(context, AppTimerService.class);
        intent.putExtra("deviceId", deviceId);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to start timer service: " + e.getMessage());
            CrashlyticsLogger.recordForegroundServiceRejected(
                    TAG,
                    "specialUse:continuous_parental_control_app_timer_monitoring",
                    e);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AppTimerService.class);
        context.stopService(intent);
    }

    public static void clearLocalConnectionState(Context context, String deviceId) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE);
        for (AppTimerLocalStore.TimerRecord record :
                AppTimerLocalStore.load(context, deviceId)) {
            if (manager != null && record.packageName != null) {
                manager.cancel(EXPIRY_NOTIFICATION_ID_BASE
                        + Math.abs(record.packageName.hashCode()));
            }
        }
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
            manager.cancel(LEGACY_NOTIFICATION_ID);
            manager.cancel(1001);
            manager.cancel(8888);
        }
        if (deviceId == null || deviceId.isEmpty()) {
            return;
        }
        AppTimerLocalStore.clear(context, deviceId);
        context.getSharedPreferences(
                "app_timer_sync_" + deviceId, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    private void clearLegacyTimerExpiryBlocks() {
        android.content.SharedPreferences timerBlocks =
                getSharedPreferences(PREF_LEGACY_TIMER_BLOCKS, MODE_PRIVATE);
        Map<String, ?> legacyBlocks = timerBlocks.getAll();
        if (legacyBlocks.isEmpty()) {
            return;
        }

        android.content.SharedPreferences.Editor blockedAppsEditor =
                getSharedPreferences("blocked_apps", MODE_PRIVATE).edit();
        for (String packageName : legacyBlocks.keySet()) {
            blockedAppsEditor.remove(packageName);
        }
        blockedAppsEditor.apply();
        timerBlocks.edit().clear().apply();

        Intent intent = new Intent("online.monarchlabs.sentinel.BLOCKED_APPS_UPDATED");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
        Log.d(TAG, "Cleared " + legacyBlocks.size() + " legacy timer-created app blocks");
    }

    static class AppTimer {
        String packageName;
        String key;
        long remainingTimeMillis;
        long dailyLimitMillis;
        long exceedTimeMillis;
        long usageAtSetMillis = -1;
        long activationRemainingMillis;
        long policyVersion;
        long executionVersion;
        long lastEvaluatedAt;
        long lastEvaluatedElapsedRealtime;
        long expiredAt;
        long cancelledAt;
        long lastSyncedExecutionVersion;
        String state = STATE_ACTIVE;
        String lastResetDate = "";
        boolean pendingSync;
        boolean active;
        boolean expiryNotified = false;
        long lastReconcileTime = 0;
        long accumulatedActiveMs = 0;
        long lastNotifIntervalCount = 0;
    }
}
