package online.monarchlabs.sentinel.assistant.execution;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import online.monarchlabs.sentinel.assistant.config.AssistantFeatureFlags;
import online.monarchlabs.sentinel.workers.AssistantCommandOutboxScheduler;

public class AssistantCommandExecutor {
    public interface ExecutionCallback {
        void onQueued(SentinelCommand command);
        void onAck(Map<String, Object> ack);
        void onError(String message);
    }

    private static final long ACK_TIMEOUT_MS = 30_000L; // 30 seconds
    private static final long MAX_LISTENER_LIFETIME_MS = 5 * 60_000L; // 5 minutes hard limit

    interface TimeoutScheduler {
        void postDelayed(Runnable runnable, long delayMs);

        void removeCallbacks(Runnable runnable);
    }

    interface AckEndpoint {
        void addValueEventListener(ValueEventListener listener);

        void removeEventListener(ValueEventListener listener);
    }

    private final FirebaseDatabase database;
    private final TimeoutScheduler timeoutScheduler;
    private final Context appContext;
    private final AssistantCommandOutbox outbox;
    private final boolean localOutboxEnabled;
    // Maps commandId -> {listener, timeoutRunnable, startTime}
    private final Map<String, ListenerTracker> activeListeners = new ConcurrentHashMap<>();

    private static class ListenerTracker {
        AckEndpoint ackEndpoint;
        ValueEventListener listener;
        Runnable timeoutRunnable;
        long startTimeMs;
    }

    private static class DatabaseAckEndpoint implements AckEndpoint {
        private final DatabaseReference reference;

        DatabaseAckEndpoint(DatabaseReference reference) {
            this.reference = reference;
        }

        @Override
        public void addValueEventListener(ValueEventListener listener) {
            reference.addValueEventListener(listener);
        }

        @Override
        public void removeEventListener(ValueEventListener listener) {
            reference.removeEventListener(listener);
        }
    }

    private static class HandlerTimeoutScheduler implements TimeoutScheduler {
        private final Handler handler;

        HandlerTimeoutScheduler(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            handler.postDelayed(runnable, delayMs);
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            handler.removeCallbacks(runnable);
        }
    }

    public AssistantCommandExecutor() {
        this(null, FirebaseDatabase.getInstance(),
                new HandlerTimeoutScheduler(new Handler(Looper.getMainLooper())));
    }

    public AssistantCommandExecutor(Context context) {
        this(context, FirebaseDatabase.getInstance(),
                new HandlerTimeoutScheduler(new Handler(Looper.getMainLooper())));
    }

    AssistantCommandExecutor(FirebaseDatabase database) {
        this(null, database, new HandlerTimeoutScheduler(new Handler(Looper.getMainLooper())));
    }

    AssistantCommandExecutor(FirebaseDatabase database, TimeoutScheduler timeoutScheduler) {
        this(null, database, timeoutScheduler);
    }

    AssistantCommandExecutor(Context context, FirebaseDatabase database, TimeoutScheduler timeoutScheduler) {
        this.database = database;
        this.timeoutScheduler = timeoutScheduler;
        this.appContext = context == null ? null : context.getApplicationContext();
        this.outbox = this.appContext == null ? null : new AssistantCommandOutbox(this.appContext);
        this.localOutboxEnabled = new AssistantFeatureFlags()
                .isEnabled(AssistantFeatureFlags.ASSISTANT_LOCAL_OUTBOX_ENABLED);
    }

    public void enqueue(SentinelCommand command, ExecutionCallback callback) {
        if (command == null) {
            if (callback != null) {
                callback.onError("No command was prepared.");
            }
            return;
        }
        if (command.getChildId() == null || command.getChildId().trim().isEmpty()) {
            if (callback != null) {
                callback.onError("Choose a child before sending this command.");
            }
            return;
        }

        DatabaseReference commandRef = database.getReference("v2")
                .child("commands")
                .child(command.getChildId())
                .child(command.getCommandId());

        if (shouldUseLocalOutbox() && outbox.save(command)) {
            AssistantCommandOutboxScheduler.scheduleRetry(appContext);
        }

        // Sync policy directly on parent side to persist timer records for dashboard sync
        if (command.getCommandType() == online.monarchlabs.sentinel.assistant.core.CommandType.ASSISTANT_SET_APP_TIMER) {
            applyTimerPolicyFromCommand(command);
        } else if (command.getCommandType() == online.monarchlabs.sentinel.assistant.core.CommandType.ASSISTANT_REMOVE_APP_TIMER) {
            removeTimerPolicyFromCommand(command);
        } else if (command.getCommandType() == online.monarchlabs.sentinel.assistant.core.CommandType.ASSISTANT_BLOCK_APP
                || command.getCommandType() == online.monarchlabs.sentinel.assistant.core.CommandType.ASSISTANT_BLOCK_CATEGORY) {
            applyBlockPolicyFromCommand(command);
        } else if (command.getCommandType() == online.monarchlabs.sentinel.assistant.core.CommandType.ASSISTANT_UNBLOCK_APP) {
            removeBlockPolicyFromCommand(command);
        } else if (command.getCommandType() == online.monarchlabs.sentinel.assistant.core.CommandType.ASSISTANT_UNBLOCK_ALL_APPS) {
            removeAllBlockPoliciesFromCommand(command);
        }

        commandRef.setValue(toV2CommandMap(command))
                .addOnSuccessListener(unused -> {
                    if (shouldUseLocalOutbox()) {
                        outbox.remove(command.getCommandId());
                    }
                    if (callback != null) {
                        callback.onQueued(command);
                    }
                    listenForAck(command, callback);
                })
                .addOnFailureListener(error -> {
                    if (shouldUseLocalOutbox() && appContext != null) {
                        AssistantCommandOutboxScheduler.scheduleRetry(appContext);
                    }
                    if (callback != null) {
                        String message = "Could not queue command: " + error.getMessage();
                        if (shouldUseLocalOutbox()) {
                            message += " It has been saved locally and will retry when connectivity returns.";
                        }
                        callback.onError(message);
                    }
                });
    }

    private void listenForAck(SentinelCommand command, ExecutionCallback callback) {
        String childId = command.getChildId();
        String commandId = command.getCommandId();

        DatabaseReference resultRef = database.getReference("v2")
                .child("commands")
                .child(childId)
                .child(commandId);

        beginAckTracking(commandId, new DatabaseAckEndpoint(resultRef), callback);
    }

    void beginAckTracking(String commandId, AckEndpoint ackEndpoint, ExecutionCallback callback) {
        if (commandId == null || commandId.trim().isEmpty() || ackEndpoint == null) {
            return;
        }

        ListenerTracker tracker = new ListenerTracker();
        tracker.startTimeMs = System.currentTimeMillis();
        tracker.ackEndpoint = ackEndpoint;

        final ValueEventListener[] listenerHolder = new ValueEventListener[1];
        listenerHolder[0] = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    return;
                }
                Object value = snapshot.getValue();
                if (value instanceof Map && callback != null) {
                    Map<String, Object> ack = (Map<String, Object>) value;
                    Object status = ack.get("status");
                    if (status == null || "pending".equals(String.valueOf(status))) {
                        if (System.currentTimeMillis() - tracker.startTimeMs > MAX_LISTENER_LIFETIME_MS) {
                            removeListener(commandId);
                            callback.onError("Timeout: child device did not respond in time.");
                        }
                        return;
                    }
                    callback.onAck(ack);
                    if (!isTerminalStatus(String.valueOf(status))) {
                        return;
                    }
                }
                // Terminal status reached - cleanup
                removeListener(commandId);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                if (callback != null) {
                    callback.onError("Command status listener failed: " + error.getMessage());
                }
                removeListener(commandId);
            }
        };

        tracker.listener = listenerHolder[0];

        // Setup timeout callback
        tracker.timeoutRunnable = () -> {
            removeListener(commandId);
            if (callback != null) {
                callback.onError("Timeout: Child device did not respond within 30 seconds. " +
                        "It may be offline or taking longer to process the command.");
            }
        };

        activeListeners.put(commandId, tracker);
        ackEndpoint.addValueEventListener(tracker.listener);

        // Schedule timeout
        timeoutScheduler.postDelayed(tracker.timeoutRunnable, ACK_TIMEOUT_MS);
    }

    private void removeListener(String commandId) {
        ListenerTracker tracker = activeListeners.remove(commandId);
        if (tracker != null) {
            // Cancel timeout if still pending
            timeoutScheduler.removeCallbacks(tracker.timeoutRunnable);
            // Remove Firebase listener
            if (tracker.ackEndpoint != null && tracker.listener != null) {
                tracker.ackEndpoint.removeEventListener(tracker.listener);
            }
        }
    }

    private boolean shouldUseLocalOutbox() {
        return appContext != null
                && outbox != null
                && localOutboxEnabled;
    }

    /**
     * Clean up all active listeners. Call this when Activity is destroyed or in cleanup.
     */
    public void cleanup() {
        for (Map.Entry<String, ListenerTracker> entry : activeListeners.entrySet()) {
            ListenerTracker tracker = entry.getValue();
            if (tracker != null) {
                timeoutScheduler.removeCallbacks(tracker.timeoutRunnable);
                if (tracker.ackEndpoint != null && tracker.listener != null) {
                    tracker.ackEndpoint.removeEventListener(tracker.listener);
                }
            }
        }
        activeListeners.clear();
    }

    private boolean isTerminalStatus(String status) {
        return "APPLIED".equals(status)
                || "PARTIALLY_APPLIED".equals(status)
                || "FAILED".equals(status)
                || "PENDING_PERMISSION".equals(status)
                || "DUPLICATE_IGNORED".equals(status)
                || "EXPIRED".equals(status);
    }

    private void applyTimerPolicyFromCommand(SentinelCommand command) {
        String childId = command.getChildId();
        java.util.List<String> packages = command.getTargetPackages();
        java.util.Map<String, Object> payload = command.getPayload();
        if (childId == null || packages == null || payload == null) {
            return;
        }
        Object durationObj = payload.get("durationMillis");
        if (!(durationObj instanceof Number)) {
            return;
        }
        long duration = ((Number) durationObj).longValue();
        String appName = (String) payload.get("appName");
        if (appName == null || appName.isEmpty()) {
            appName = "App";
        }
        for (String pkg : packages) {
            if (pkg == null || pkg.trim().isEmpty()) {
                continue;
            }
            String safeKey = pkg.replaceAll("[.#$\\[\\]/]", "_");
            java.util.Map<String, Object> timerData = new java.util.HashMap<>();
            timerData.put("packageName", pkg);
            timerData.put("appName", appName);
            timerData.put("iconBase64", "");
            timerData.put("totalTimeMillis", duration);
            timerData.put("dailyLimitMillis", duration);
            timerData.put("remainingTimeMillis", duration);
            timerData.put("usageAtSetMillis", -1L);
            timerData.put("active", true);
            timerData.put("expired", false);
            timerData.put("state", "ACTIVE");
            long policyVersion = System.currentTimeMillis();
            timerData.put("policyVersion", policyVersion);
            timerData.put("createdAt", policyVersion);
            timerData.put("lastUpdated", policyVersion);

            database.getReference("v2")
                    .child("device_policies")
                    .child(childId)
                    .child("app_timers")
                    .child(safeKey)
                    .setValue(timerData);

            database.getReference("app_timers")
                    .child(childId)
                    .child(safeKey)
                    .setValue(timerData);
        }
    }

    private void removeTimerPolicyFromCommand(SentinelCommand command) {
        String childId = command.getChildId();
        java.util.List<String> packages = command.getTargetPackages();
        if (childId == null || packages == null) {
            return;
        }
        for (String pkg : packages) {
            if (pkg == null || pkg.trim().isEmpty()) {
                continue;
            }
            String safeKey = pkg.replaceAll("[.#$\\[\\]/]", "_");
            database.getReference("v2")
                    .child("device_policies")
                    .child(childId)
                    .child("app_timers")
                    .child(safeKey)
                    .removeValue();

            database.getReference("app_timers")
                    .child(childId)
                    .child(safeKey)
                    .removeValue();
        }
    }

    private void applyBlockPolicyFromCommand(SentinelCommand command) {
        String childId = command.getChildId();
        java.util.List<String> packages = command.getTargetPackages();
        if (childId == null || packages == null) {
            return;
        }
        for (String pkg : packages) {
            if (pkg == null || pkg.trim().isEmpty()) {
                continue;
            }
            if (online.monarchlabs.sentinel.AppBlockingPolicy.isUnblockable(pkg)) {
                continue;
            }
            String safeKey = pkg.replaceAll("[.#$\\[\\]/]", "_");
            java.util.Map<String, Object> policy = new java.util.HashMap<>();
            policy.put("policyId", java.util.UUID.randomUUID().toString());
            policy.put("packageName", pkg);
            policy.put("appName", pkg);
            policy.put("blocked", true);
            policy.put("enforcementMode", "IMMEDIATE");
            policy.put("delayDurationMs", 0L);
            policy.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
            database.getReference("v2")
                    .child("device_policies")
                    .child(childId)
                    .child("blocked_apps")
                    .child(safeKey)
                    .setValue(policy);

            database.getReference("blocked_apps")
                    .child(childId)
                    .child(safeKey)
                    .setValue(true);
        }
    }

    private void removeBlockPolicyFromCommand(SentinelCommand command) {
        String childId = command.getChildId();
        java.util.List<String> packages = command.getTargetPackages();
        if (childId == null || packages == null) {
            return;
        }
        for (String pkg : packages) {
            if (pkg == null || pkg.trim().isEmpty()) {
                continue;
            }
            String safeKey = pkg.replaceAll("[.#$\\[\\]/]", "_");
            java.util.Map<String, Object> policy = new java.util.HashMap<>();
            policy.put("policyId", java.util.UUID.randomUUID().toString());
            policy.put("packageName", pkg);
            policy.put("appName", pkg);
            policy.put("blocked", false);
            policy.put("enforcementMode", "IMMEDIATE");
            policy.put("delayDurationMs", 0L);
            policy.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);

            database.getReference("v2")
                    .child("device_policies")
                    .child(childId)
                    .child("blocked_apps")
                    .child(safeKey)
                    .setValue(policy);

            database.getReference("blocked_apps")
                    .child(childId)
                    .child(safeKey)
                    .removeValue();
        }
    }

    private void removeAllBlockPoliciesFromCommand(SentinelCommand command) {
        String childId = command.getChildId();
        if (childId == null) {
            return;
        }
        database.getReference("v2")
                .child("device_policies")
                .child(childId)
                .child("blocked_apps")
                .removeValue();

        database.getReference("blocked_apps")
                .child(childId)
                .removeValue();
    }

    private Map<String, Object> toV2CommandMap(SentinelCommand command) {
        Map<String, Object> map = new HashMap<>();
        map.put("commandId", command.getCommandId());
        map.put("assistantActionId", command.getAssistantActionId());
        map.put("idempotencyKey", command.getIdempotencyKey());
        map.put("deviceId", command.getChildId());
        map.put("childId", command.getChildId());
        map.put("parentId", command.getParentId());
        map.put("type", command.getCommandType().name());
        map.put("source", command.getSource().name());
        map.put("status", "pending");
        map.put("targetPackages", command.getTargetPackages());
        map.put("targetCategories", command.getTargetCategories());
        map.put("payload", command.getPayload());
        map.put("createdAtMillis", command.getCreatedAtMillis());
        map.put("expiresAtMillis", command.getExpiresAtMillis());
        map.put("schemaVersion", 2);
        return map;
    }
}
