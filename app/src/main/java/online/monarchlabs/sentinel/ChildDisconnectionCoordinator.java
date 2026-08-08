package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Canonical local-first child disconnection flow for the v2 relationship.
 */
public final class ChildDisconnectionCoordinator {
    private static final String TAG = "ChildDisconnect";
    private static final String STATE_PREFS = "disconnection_state";
    private static final AtomicBoolean PROCESSING = new AtomicBoolean(false);

    private ChildDisconnectionCoordinator() {
    }

    public static void disconnectCurrentSession(Context context, String reason) {
        disconnect(
                context.getApplicationContext(),
                isValid(reason) ? reason : "local_disconnect",
                null);
    }
    public static void processRemovalMarker(
            @NonNull Context context,
            @NonNull DataSnapshot marker,
            @NonNull DatabaseReference markerRef) {
        SessionManager session = new SessionManager(context.getApplicationContext());
        if (!isRemovalForCurrentSession(session, marker)) {
            Log.w(TAG, "Ignoring removal marker that does not target the current child session");
            return;
        }
        disconnect(context, "removed_by_parent", markerRef);
    }

    public static void validateCurrentOwnership(@NonNull Context context, String source) {
        Context appContext = context.getApplicationContext();
        SessionManager session = new SessionManager(appContext);
        if (!isCompleteChildSession(session)) {
            return;
        }

        String deviceId = session.getChildDeviceId();
        String expectedParentUid = session.getParentUserId();
        String expectedConnectionId = session.getConnectionId();
        FirebaseUser childUser = FirebaseAuth.getInstance().getCurrentUser();
        String expectedChildUid = childUser != null ? childUser.getUid() : "";

        FirebaseDatabase.getInstance().getReference("v2")
                .child("device_owners")
                .child(deviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot owner) {
                        SessionManager current = new SessionManager(appContext);
                        if (!sameLocalSession(current, deviceId, expectedParentUid, expectedConnectionId)) {
                            return;
                        }

                        String status = owner.child("status").getValue(String.class);
                        String parentUid = owner.child("parentUid").getValue(String.class);
                        String connectionId = owner.child("connectionId").getValue(String.class);
                        String childAuthUid = owner.child("childAuthUid").getValue(String.class);
                        boolean active = owner.exists()
                                && (status == null || status.isEmpty()
                                || "active".equalsIgnoreCase(status));
                        boolean matches = active
                                && expectedParentUid.equals(parentUid)
                                && expectedConnectionId.equals(connectionId)
                                && (childAuthUid == null || childAuthUid.isEmpty()
                                || childAuthUid.equals(expectedChildUid));
                        if (!matches) {
                            Log.w(TAG, "Ownership validation failed at " + source
                                    + "; disconnecting stale local session");
                            disconnect(appContext, "ownership_missing_or_mismatched", null);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        // A transient network/rules failure must not log out a valid child.
                        Log.w(TAG, "Ownership validation deferred at " + source + ": "
                                + error.getMessage());
                    }
                });
    }

    public static boolean isRemovalForCurrentSession(
            @NonNull SessionManager session,
            @NonNull DataSnapshot marker) {
        if (!isCompleteChildSession(session)) {
            return false;
        }

        Boolean trigger = marker.child("trigger").getValue(Boolean.class);
        Boolean removedByParent = marker.child("removed_by_parent").getValue(Boolean.class);
        if (!Boolean.TRUE.equals(trigger) && !Boolean.TRUE.equals(removedByParent)) {
            return false;
        }

        String targetParentUid = marker.child("targetParentUid").getValue(String.class);
        String targetConnectionId = marker.child("targetConnectionId").getValue(String.class);
        String targetChildAuthUid = marker.child("childAuthUid").getValue(String.class);
        Long expiresAt = marker.child("expiresAt").getValue(Long.class);
        Long issuedAt = marker.child("issuedAt").getValue(Long.class);
        long now = System.currentTimeMillis();
        if (expiresAt != null && expiresAt > 0 && expiresAt < now) {
            return false;
        }
        if (issuedAt != null && session.getConnectionLinkedAt() > 0
                && issuedAt < session.getConnectionLinkedAt()) {
            return false;
        }
        if (targetParentUid == null || targetConnectionId == null
                || !targetParentUid.equals(session.getParentUserId())
                || !targetConnectionId.equals(session.getConnectionId())) {
            return false;
        }

        FirebaseUser childUser = FirebaseAuth.getInstance().getCurrentUser();
        return targetChildAuthUid == null || targetChildAuthUid.isEmpty()
                || (childUser != null && targetChildAuthUid.equals(childUser.getUid()));
    }

    public static void disconnect(
            @NonNull Context context,
            @NonNull String reason,
            DatabaseReference markerRef) {
        Context appContext = context.getApplicationContext();
        SessionManager session = new SessionManager(appContext);
        if (!isCompleteChildSession(session)) {
            completePendingLocalCleanup(appContext, session);
            navigateToGetStarted(appContext, reason);
            return;
        }
        if (!PROCESSING.compareAndSet(false, true)) {
            return;
        }

        String deviceId = session.getChildDeviceId();
        String parentUid = session.getParentUserId();
        String connectionId = session.getConnectionId();
        long disconnectedAt = System.currentTimeMillis();

        try {
            // Commit the authoritative local state before any network or service work.
            SharedPreferences.Editor state = appContext
                    .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("device_was_removed", true)
                    .putBoolean("require_qr_reconnection", true)
                    .putBoolean("bulletproof_logout_completed", true)
                    .putBoolean("local_cleanup_complete", false)
                    .putLong("logout_timestamp", disconnectedAt)
                    .putLong("disconnection_timestamp", disconnectedAt)
                    .putString("removed_device_id", deviceId)
                    .putString("removed_parent_uid", parentUid)
                    .putString("removed_connection_id", connectionId)
                    .putString("logout_reason", reason)
                    .putString("disconnection_reason", reason);
            if (!state.commit()) {
                throw new IllegalStateException("Could not persist local disconnection state");
            }

            session.setConnectionActive(false);
            session.logoutUser();
            ChildServiceCoordinator.stopForDisconnect(appContext, deviceId);

            appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("require_qr_reconnection", false)
                    .putBoolean("local_cleanup_complete", true)
                    .putLong("local_cleanup_completed_at", System.currentTimeMillis())
                    .commit();

            sendLogoutBroadcast(appContext, reason, true);
            navigateToGetStarted(appContext, reason);
            acknowledgeMarker(markerRef);
            Log.i(TAG, "Disconnected child session " + connectionId + " locally");
        } catch (Exception error) {
            Log.e(TAG, "Local child disconnection failed", error);
            // The persisted flag still prevents the old dashboard from being trusted.
            navigateToGetStarted(appContext, reason);
        } finally {
            PROCESSING.set(false);
        }
    }

    private static void completePendingLocalCleanup(
            Context appContext, SessionManager session) {
        SharedPreferences state = appContext.getSharedPreferences(
                STATE_PREFS, Context.MODE_PRIVATE);
        boolean pending = state.getBoolean("device_was_removed", false)
                && state.getBoolean("require_qr_reconnection", false)
                && !state.getBoolean("local_cleanup_complete", false);
        if (!pending) {
            return;
        }

        String removedDeviceId = state.getString(
                "removed_device_id", session.getChildDeviceId());
        session.setConnectionActive(false);
        session.logoutUser();
        ChildServiceCoordinator.stopForDisconnect(appContext, removedDeviceId);
        state.edit()
                .putBoolean("require_qr_reconnection", false)
                .putBoolean("local_cleanup_complete", true)
                .putLong("local_cleanup_completed_at", System.currentTimeMillis())
                .commit();
        sendLogoutBroadcast(appContext, "pending_cleanup", true);
        Log.i(TAG, "Completed interrupted local child disconnection cleanup");
    }

    private static void acknowledgeMarker(DatabaseReference markerRef) {
        if (markerRef == null) {
            return;
        }
        Map<String, Object> acknowledgement = new HashMap<>();
        acknowledgement.put("acknowledgedAt", ServerValue.TIMESTAMP);
        acknowledgement.put("localCleanupComplete", true);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            acknowledgement.put("acknowledgedByChildUid", user.getUid());
        }
        markerRef.updateChildren(acknowledgement)
                .addOnFailureListener(error -> Log.w(TAG,
                        "Removal acknowledgement deferred: " + error.getMessage()));
    }

    private static void sendLogoutBroadcast(Context context, String reason,
            boolean goToGetStarted) {
        Intent logout = new Intent("online.monarchlabs.sentinel.CHILD_LOGOUT");
        logout.setPackage(context.getPackageName());
        logout.putExtra("logout_reason", reason);
        logout.putExtra("go_to_get_started", goToGetStarted);
        context.sendBroadcast(logout);
    }

    private static void navigateToGetStarted(Context context, String reason) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("logout_message", "Device was removed by parent");
            intent.putExtra("logout_reason", reason);
            intent.putExtra("force_login_screen", true);
            intent.putExtra("device_was_removed", true);
            context.startActivity(intent);
        });
    }

    private static boolean isCompleteChildSession(SessionManager session) {
        return session.isLoggedIn()
                && "child".equals(session.getUserType())
                && isValid(session.getChildDeviceId())
                && isValid(session.getParentUserId())
                && isValid(session.getConnectionId());
    }

    private static boolean sameLocalSession(SessionManager session, String deviceId,
            String parentUid, String connectionId) {
        return isCompleteChildSession(session)
                && deviceId.equals(session.getChildDeviceId())
                && parentUid.equals(session.getParentUserId())
                && connectionId.equals(session.getConnectionId());
    }

    private static boolean isValid(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
