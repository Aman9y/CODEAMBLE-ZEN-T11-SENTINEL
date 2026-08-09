package online.monarchlabs.sentinel.services;

import android.content.Context;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Firebase-only v2 ownership, pairing, and removal operations. */
public final class RelationshipService {
    private static final String TAG = "RelationshipService";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long STALE_AFTER_MS = 90L * DAY_MS;
    private static final long REMOVAL_TTL_MS = 180L * DAY_MS;
    private static final List<String> DEVICE_ROOTS = Arrays.asList(
            "usage_daily", "app_catalog", "device_installs", "app_events",
            "device_policies", "device_modes", "app_block_state", "device_status", "commands",
            "permissions_current", "permission_logs", "device_health", "locations",
            "timer_execution", "timer_state_requests", "timer_events",
            "client_capabilities");

    private final DatabaseReference root;

    public RelationshipService(Context context) {
        root = FirebaseDatabase.getInstance().getReference();
    }

    public CompletableFuture<Result> pair(String sessionId, String shareKey,
            String parentDeviceId, String childDeviceId,
            String childDeviceName, String childDeviceModel,
            int usageSchemaVersion) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        FirebaseUser child = FirebaseAuth.getInstance().getCurrentUser();
        if (child == null || !child.isAnonymous()) {
            future.complete(Result.error(
                    "Pairing requires a child device session.",
                    "INVALID_CHILD_AUTH"));
            return future;
        }
        if (isBlank(sessionId) || isBlank(shareKey)
                || isBlank(parentDeviceId) || isBlank(childDeviceId)) {
            future.complete(Result.error(
                    "This QR code is invalid or expired.", "INVALID_QR"));
            return future;
        }

        DatabaseReference sessionRef = root.child("v2")
                .child("pairing_sessions").child(sessionId);
        sessionRef.get()
                .addOnSuccessListener(snapshot -> {
                    PairingSession session = PairingSession.from(snapshot);
                    long now = System.currentTimeMillis();
                    if (!session.isValid(
                            shareKey, parentDeviceId, now)) {
                        future.complete(Result.error(
                                "This QR code is invalid or expired.",
                                "INVALID_QR"));
                        return;
                    }
                    claimOwnership(
                            sessionId, shareKey, session, child,
                            childDeviceId, childDeviceName,
                            childDeviceModel, usageSchemaVersion,
                            now, future);
                })
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Pairing session read failed", error);
                    future.complete(Result.error(
                            "Could not verify this QR code.",
                            "PAIRING_SESSION_READ_FAILED"));
                });
        return future;
    }

    private void claimOwnership(String sessionId, String shareKey,
            PairingSession session, FirebaseUser child,
            String childDeviceId, String childDeviceName,
            String childDeviceModel, int usageSchemaVersion,
            long linkedAt, CompletableFuture<Result> future) {
        String connectionId = "conn_" + linkedAt + "_"
                + UUID.randomUUID().toString().replace("-", "");
        DatabaseReference ownerRef = root.child("v2")
                .child("device_owners").child(childDeviceId);
        AtomicReference<Map<String, Object>> previousOwner =
                new AtomicReference<>();
        AtomicBoolean ownershipLocked = new AtomicBoolean(false);

        ownerRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData currentData) {
                Map<String, Object> existing = mapValue(currentData.getValue());
                previousOwner.set(new HashMap<>(existing));
                String existingParent = stringValue(
                        existing.get("parentUid"));
                String status = stringValue(existing.get("status"));
                long lastSeen = Math.max(
                        numberValue(existing.get("lastSeenAt")),
                        Math.max(numberValue(existing.get("updatedAt")),
                                numberValue(existing.get("linkedAt"))));
                boolean active = isBlank(status) || "active".equals(status);
                boolean stale = active && lastSeen > 0L
                        && linkedAt - lastSeen >= STALE_AFTER_MS;
                boolean removing = "removing".equals(status);
                if (removing || (!isBlank(existingParent)
                        && active && !stale
                        && !session.parentUid.equals(existingParent))) {
                    ownershipLocked.set(true);
                    return Transaction.abort();
                }

                Map<String, Object> owner = new HashMap<>();
                owner.put("parentUid", session.parentUid);
                owner.put("childAuthUid", child.getUid());
                owner.put("pairingSessionId", sessionId);
                owner.put("pairingKey", shareKey);
                owner.put("connectionId", connectionId);
                owner.put("linkedAt", linkedAt);
                owner.put("lastSeenAt", linkedAt);
                owner.put("updatedAt", linkedAt);
                owner.put("status", "active");
                currentData.setValue(owner);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed,
                    DataSnapshot snapshot) {
                if (error != null) {
                    Log.w(TAG, "Ownership transaction failed", error.toException());
                    future.complete(Result.error(
                            "Could not verify existing device ownership.",
                            "OWNERSHIP_CHECK_FAILED"));
                    return;
                }
                if (!committed || ownershipLocked.get()) {
                    future.complete(Result.error(
                            "This child device is already connected to another "
                                    + "parent account. Ask the current parent to "
                                    + "remove this child first.",
                            "OWNERSHIP_LOCKED"));
                    return;
                }
                writeConnectionData(
                        sessionId, session, child, previousOwner.get(),
                        childDeviceId, childDeviceName, childDeviceModel,
                        usageSchemaVersion, connectionId, linkedAt, future);
            }
        }, false);
    }

    private void writeConnectionData(String sessionId, PairingSession session,
            FirebaseUser child, Map<String, Object> previousOwner,
            String childDeviceId, String childDeviceName,
            String childDeviceModel, int usageSchemaVersion,
            String connectionId, long linkedAt,
            CompletableFuture<Result> future) {
        Map<String, Object> updates = new HashMap<>();
        String previousParentUid = previousOwner == null
                ? null : stringValue(previousOwner.get("parentUid"));
        if (!isBlank(previousParentUid)
                && !session.parentUid.equals(previousParentUid)) {
            updates.put("v2/parent_device_links/" + previousParentUid
                    + "/" + childDeviceId, null);
            updates.put("v2/parent_notification_state/" + previousParentUid
                    + "/" + childDeviceId, null);
            addDeviceDeletes(updates, childDeviceId);
        }

        String displayName = isBlank(childDeviceName)
                ? "Child Device" : childDeviceName.trim();
        updates.put("v2/device_removals/" + childDeviceId, null);

        Map<String, Object> device = new HashMap<>();
        device.put("deviceId", childDeviceId);
        device.put("deviceName", displayName);
        device.put("childName", displayName);
        device.put("userName", displayName);
        device.put("deviceModel", isBlank(childDeviceModel)
                ? displayName : childDeviceModel);
        device.put("deviceType", "child");
        device.put("platform", "android");
        device.put("childAuthUid", child.getUid());
        device.put("ownerParentUid", session.parentUid);
        device.put("parentDeviceId", session.parentDeviceId);
        device.put("pairingSessionId", sessionId);
        device.put("connectionId", connectionId);
        device.put("status", "active");
        device.put("linkedAt", linkedAt);
        device.put("updatedAt", linkedAt);
        updates.put("v2/devices/" + childDeviceId, device);

        Map<String, Object> link = new HashMap<>();
        link.put("deviceId", childDeviceId);
        link.put("deviceName", displayName);
        link.put("childName", displayName);
        link.put("userName", displayName);
        link.put("childAuthUid", child.getUid());
        link.put("pairingSessionId", sessionId);
        link.put("connectionId", connectionId);
        link.put("linkedAt", linkedAt);
        link.put("status", "active");
        updates.put("v2/parent_device_links/" + session.parentUid
                + "/" + childDeviceId, link);

        Map<String, Object> sessionConnection = new HashMap<>();
        sessionConnection.put("childDeviceId", childDeviceId);
        sessionConnection.put("childAuthUid", child.getUid());
        sessionConnection.put("connectionId", connectionId);
        sessionConnection.put("connectedAt", linkedAt);
        updates.put("v2/pairing_sessions/" + sessionId
                + "/connections/" + childDeviceId, sessionConnection);

        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("schemaVersion", 2);
        capabilities.put("usageSchemaVersion", usageSchemaVersion);
        capabilities.put("appLimitsV2", true);
        capabilities.put("locationV2", true);
        capabilities.put("updatedAt", linkedAt);
        updates.put("v2/client_capabilities/" + childDeviceId, capabilities);

        Map<String, Object> consent = new HashMap<>();
        consent.put("parentUid", session.parentUid);
        consent.put("childDeviceId", childDeviceId);
        consent.put("connectionId", connectionId);
        consent.put("parentDeviceId", session.parentDeviceId);
        consent.put("consentEventId", sessionId);
        consent.put("consentVersion", session.consentVersion);
        consent.put("policyVersion", session.policyVersion);
        consent.put("affirmedAt", linkedAt);
        consent.put("recordedAt", linkedAt);
        consent.put("status", "active");
        updates.put("v2/consents/" + session.parentUid
                + "/" + childDeviceId, consent);

        root.updateChildren(updates)
                .addOnSuccessListener(ignored -> future.complete(
                        new Result(true, "Connected", null,
                                session.parentUid, session.parentDeviceName,
                                connectionId, linkedAt)))
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Connection data write failed", error);
                    future.complete(Result.error(
                            "Ownership was reserved, but connection setup did "
                                    + "not finish. Scan the same parent QR again.",
                            "CONNECTION_WRITE_FAILED"));
                });
    }

    public CompletableFuture<Result> remove(String childDeviceId) {
        return remove(childDeviceId, "removed_by_parent");
    }

    public CompletableFuture<Result> remove(
            String childDeviceId, String reason) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        FirebaseUser parent = FirebaseAuth.getInstance().getCurrentUser();
        if (parent == null || parent.isAnonymous()) {
            future.complete(Result.error(
                    "A valid parent account is required.",
                    "INVALID_PARENT_AUTH"));
            return future;
        }
        if (isBlank(childDeviceId)) {
            future.complete(Result.error(
                    "Invalid child device.", "INVALID_DEVICE"));
            return future;
        }

        DatabaseReference ownerRef = root.child("v2")
                .child("device_owners").child(childDeviceId);
        long requestedAt = System.currentTimeMillis();
        String parentUid = parent.getUid();

        ownerRef.get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) {
                        Log.w(TAG, "Removal owner missing; clearing stale "
                                + "parent link only deviceId=" + childDeviceId
                                + " parentUid=" + parentUid);
                        completeAlreadyRemovedCleanup(
                                childDeviceId, parentUid, future);
                        return;
                    }

                    Map<String, Object> owner = mapValue(snapshot.getValue());
                    String ownerParentUid = stringValue(owner.get("parentUid"));
                    if (!parentUid.equals(ownerParentUid)) {
                        Log.w(TAG, "Removal owner mismatch; clearing stale "
                                + "parent link only "
                                + "deviceId=" + childDeviceId
                                + " authParentUid=" + parentUid
                                + " ownerParentUid=" + ownerParentUid
                                + " ownerStatus="
                                + stringValue(owner.get("status")));
                        completeAlreadyRemovedCleanup(
                                childDeviceId, parentUid, future);
                        return;
                    }
                    Map<String, Object> ownedDevice = new HashMap<>(owner);
                    if (isBlank(stringValue(ownedDevice.get("connectionId")))) {
                        resolveConnectionIdAndRemove(
                                childDeviceId, parentUid, reason,
                                ownedDevice, requestedAt, future);
                    } else {
                        completeRemoval(
                                childDeviceId, parentUid, reason,
                                ownedDevice, requestedAt, future);
                    }
                })
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Removal owner read failed deviceId="
                            + childDeviceId
                            + " parentUid=" + parentUid
                            + " message=" + error.getMessage(), error);
                    future.complete(Result.error(
                            "Could not verify child ownership for removal.",
                            "REMOVAL_OWNER_READ_FAILED"));
                });
        return future;
    }

    private void resolveConnectionIdAndRemove(String childDeviceId,
            String parentUid, String reason, Map<String, Object> owner,
            long requestedAt, CompletableFuture<Result> future) {
        root.child("v2").child("parent_device_links").child(parentUid)
                .child(childDeviceId).child("connectionId").get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        String connectionId = task.getResult()
                                .getValue(String.class);
                        if (!isBlank(connectionId)) {
                            owner.put("connectionId", connectionId);
                        }
                    } else {
                        Log.w(TAG, "Could not resolve legacy connection ID for "
                                + "removal deviceId=" + childDeviceId,
                                task.getException());
                    }
                    completeRemoval(childDeviceId, parentUid, reason,
                            owner, requestedAt, future);
                });
    }

    private void completeAlreadyRemovedCleanup(String childDeviceId,
            String parentUid, CompletableFuture<Result> future) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("v2/parent_device_links/" + parentUid
                + "/" + childDeviceId, null);
        updates.put("v2/parent_notification_state/" + parentUid
                + "/" + childDeviceId, null);
        root.updateChildren(updates)
                .addOnSuccessListener(ignored -> future.complete(
                        new Result(true, "Removed", null,
                                parentUid, null, null, 0L)))
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Stale parent removal cleanup failed deviceId="
                            + childDeviceId
                            + " parentUid=" + parentUid
                            + " message=" + error.getMessage(), error);
                    future.complete(Result.error(
                            "Could not clear stale child link. Please retry.",
                            "STALE_REMOVAL_CLEANUP_FAILED"));
                });
    }

    private void completeRemoval(String childDeviceId, String parentUid,
            String reason, Map<String, Object> owner, long issuedAt,
            CompletableFuture<Result> future) {
        Map<String, Object> marker = new HashMap<>();
        marker.put("trigger", true);
        marker.put("removed_by_parent", true);
        marker.put("childDeviceId", childDeviceId);
        marker.put("childAuthUid", stringValue(owner.get("childAuthUid")));
        marker.put("targetParentUid", parentUid);
        marker.put("targetConnectionId", defaultString(
                stringValue(owner.get("connectionId")), ""));
        marker.put("issuedAt", issuedAt);
        marker.put("expiresAt", issuedAt + REMOVAL_TTL_MS);
        marker.put("reason", isBlank(reason)
                ? "removed_by_parent" : reason);
        marker.put("status", "pending");
        marker.put("requires_qr_reconnection", true);
        marker.put("schemaVersion", 2);

        Map<String, Object> signalUpdates = new HashMap<>();
        signalUpdates.put("v2/device_owners/" + childDeviceId
                + "/status", "removing");
        signalUpdates.put("v2/device_owners/" + childDeviceId
                + "/removalRequestedAt", issuedAt);
        signalUpdates.put("v2/device_owners/" + childDeviceId
                + "/removalReason", marker.get("reason"));
        signalUpdates.put("v2/device_removals/" + childDeviceId, marker);

        root.updateChildren(signalUpdates)
                .addOnSuccessListener(ignored -> deleteRemovedDeviceData(
                        childDeviceId, parentUid, future))
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Child removal signal failed deviceId="
                            + childDeviceId
                            + " parentUid=" + parentUid
                            + " message=" + error.getMessage(), error);
                    future.complete(Result.error(
                            "Could not start child removal. Please retry.",
                            "REMOVAL_SIGNAL_FAILED"));
                });
    }

    private void deleteRemovedDeviceData(String childDeviceId,
            String parentUid, CompletableFuture<Result> future) {
        Map<String, Object> updates = new HashMap<>();
        addDeviceDeletes(updates, childDeviceId);
        updates.put("v2/devices/" + childDeviceId, null);
        updates.put("v2/device_owners/" + childDeviceId, null);
        updates.put("v2/parent_device_links/" + parentUid
                + "/" + childDeviceId, null);
        updates.put("v2/parent_notification_state/" + parentUid
                + "/" + childDeviceId, null);

        root.updateChildren(updates)
                .addOnSuccessListener(ignored -> future.complete(
                        new Result(true, "Removed", null,
                                parentUid, null, null, 0L)))
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Atomic child removal failed deviceId="
                            + childDeviceId
                            + " parentUid=" + parentUid
                            + " message=" + error.getMessage()
                            + "; retrying parent-scoped removal", error);
                    completeParentScopedRemoval(
                            childDeviceId, parentUid, future);
                });
    }

    private void completeParentScopedRemoval(String childDeviceId,
            String parentUid, CompletableFuture<Result> future) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("v2/parent_device_links/" + parentUid
                + "/" + childDeviceId, null);
        updates.put("v2/parent_notification_state/" + parentUid
                + "/" + childDeviceId, null);

        root.updateChildren(updates)
                .addOnSuccessListener(ignored -> future.complete(
                        new Result(true, "Removed", null,
                                parentUid, null, null, 0L)))
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Parent-scoped child removal failed deviceId="
                            + childDeviceId
                            + " parentUid=" + parentUid
                            + " message=" + error.getMessage(), error);
                    future.complete(Result.error(
                            "Could not complete child removal. Please retry.",
                            "REMOVAL_WRITE_FAILED"));
                });
    }

    private static void addDeviceDeletes(
            Map<String, Object> updates, String deviceId) {
        for (String rootName : DEVICE_ROOTS) {
            updates.put("v2/" + rootName + "/" + deviceId, null);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map) {
            return new HashMap<>((Map<String, Object>) value);
        }
        return new HashMap<>();
    }

    private static long numberValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class PairingSession {
        final String parentUid;
        final String parentDeviceId;
        final String parentDeviceName;
        final String baseQrKey;
        final String status;
        final boolean active;
        final long expiresAt;
        final String consentVersion;
        final String policyVersion;

        PairingSession(String parentUid, String parentDeviceId,
                String parentDeviceName, String baseQrKey,
                String status, boolean active, long expiresAt,
                String consentVersion, String policyVersion) {
            this.parentUid = parentUid;
            this.parentDeviceId = parentDeviceId;
            this.parentDeviceName = parentDeviceName;
            this.baseQrKey = baseQrKey;
            this.status = status;
            this.active = active;
            this.expiresAt = expiresAt;
            this.consentVersion = consentVersion;
            this.policyVersion = policyVersion;
        }

        static PairingSession from(DataSnapshot snapshot) {
            Boolean isActive = snapshot.child("isActive")
                    .getValue(Boolean.class);
            Long expiry = snapshot.child("expiresAt").getValue(Long.class);
            return new PairingSession(
                    snapshot.child("parentUid").getValue(String.class),
                    snapshot.child("parentDeviceId").getValue(String.class),
                    defaultString(snapshot.child("parentDeviceName")
                            .getValue(String.class), "Parent"),
                    snapshot.child("baseQRKey").getValue(String.class),
                    snapshot.child("status").getValue(String.class),
                    Boolean.TRUE.equals(isActive),
                    expiry == null ? 0L : expiry,
                    defaultString(snapshot.child("consentVersion")
                            .getValue(String.class), "guardian-monitoring-v2"),
                    defaultString(snapshot.child("policyVersion")
                            .getValue(String.class), "privacy-v2"));
        }

        boolean isValid(String shareKey, String expectedParentDeviceId,
                long now) {
            boolean sessionActive = active || "active".equals(status);
            return sessionActive && expiresAt >= now
                    && !isBlank(parentUid)
                    && baseQrKey != null && baseQrKey.equals(shareKey)
                    && parentDeviceId != null
                    && parentDeviceId.equals(expectedParentDeviceId);
        }
    }

    private static String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    public static final class Result {
        public final boolean success;
        public final String message;
        public final String code;
        public final String parentUid;
        public final String parentDeviceName;
        public final String connectionId;
        public final long linkedAt;

        Result(boolean success, String message, String code,
                String parentUid, String parentDeviceName,
                String connectionId, long linkedAt) {
            this.success = success;
            this.message = message;
            this.code = code;
            this.parentUid = parentUid;
            this.parentDeviceName = parentDeviceName;
            this.connectionId = connectionId;
            this.linkedAt = linkedAt;
        }

        static Result error(String message, String code) {
            return new Result(
                    false, message, code, null, null, null, 0L);
        }
    }
}
