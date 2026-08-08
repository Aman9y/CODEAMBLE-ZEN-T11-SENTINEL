package online.monarchlabs.sentinel;

import android.content.Context;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.List;

import online.monarchlabs.sentinel.services.RelationshipService;
import online.monarchlabs.sentinel.utils.InstalledAppsManager;
import online.monarchlabs.sentinel.utils.SUsageDataManager;

/** Connects a child through Firebase v2 ownership transactions. */
public final class ChildConnectionManager {
    private static final String TAG = "ChildConnectionManager";
    private static final String OWNERSHIP_LOCK_MESSAGE =
            "This child device is already connected to another parent account. "
                    + "Ask the current parent to remove this child first.";

    private final Context context;
    private final RelationshipService relationshipService;

    public interface OnConnectionListener {
        void onSuccess(String parentUserId);
        void onError(String error);
    }

    public ChildConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        relationshipService = new RelationshipService(this.context);
    }

    public void connectToParent(String shareKey, String sessionId,
            String parentDeviceId, String ignoredParentDeviceName,
            String childDeviceId, String childDeviceName,
            List<AppInfo> ignoredChildApps, OnConnectionListener listener) {
        if (isBlank(sessionId) || isBlank(shareKey)
                || isBlank(parentDeviceId) || isBlank(childDeviceId)) {
            listener.onError(
                    "This QR code is outdated. Generate and scan a new code.");
            return;
        }

        ensureAnonymousChildAuth(listener, childUser ->
                relationshipService.pair(
                                sessionId,
                                shareKey,
                                parentDeviceId,
                                childDeviceId,
                                childDeviceName,
                                ChildAppUtils.getChildDeviceName(),
                                SUsageDataManager.USAGE_SCHEMA_VERSION)
                        .thenAccept(result -> {
                            if (!result.success) {
                                listener.onError(
                                        "OWNERSHIP_LOCKED".equals(result.code)
                                                ? OWNERSHIP_LOCK_MESSAGE
                                                : safeMessage(result.message));
                                return;
                            }
                            if (isBlank(result.parentUid)
                                    || isBlank(result.connectionId)
                                    || result.linkedAt <= 0L) {
                                listener.onError(
                                        "The pairing operation returned "
                                                + "an incomplete connection.");
                                return;
                            }

                            SUsageDataManager.resetUploadStateForNewRelationship(context);
                            syncInventory(childDeviceId);
                            listener.onSuccess(
                                    "CONNECTED_FAST:" + result.parentUid + "|"
                                            + result.connectionId + "|"
                                            + result.linkedAt);
                        })
                        .exceptionally(error -> {
                            Log.e(TAG, "V2 pairing failed", error);
                            listener.onError(
                                    "Could not connect this child device. "
                                            + "Please try again.");
                            return null;
                        }));
    }

    private void ensureAnonymousChildAuth(OnConnectionListener listener,
            java.util.function.Consumer<FirebaseUser> onReady) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser current = auth.getCurrentUser();
        if (current != null && current.isAnonymous()) {
            onReady.accept(current);
            return;
        }
        if (current != null) {
            auth.signOut();
        }
        auth.signInAnonymously()
                .addOnSuccessListener(result -> onReady.accept(result.getUser()))
                .addOnFailureListener(error -> {
                    Log.e(TAG, "Child authentication failed", error);
                    listener.onError(
                            "Could not authenticate this child device.");
                });
    }

    public void refreshDeviceAppList(String deviceId,
            OnConnectionListener listener) {
        InstalledAppsManager.getInstance(context).syncInstalledApps(
                deviceId,
                true,
                new InstalledAppsManager.OnSyncCompleteListener() {
                    @Override
                    public void onSuccess(int appCount) {
                        listener.onSuccess(String.valueOf(appCount));
                    }

                    @Override
                    public void onError(String error) {
                        listener.onError(
                                error == null
                                        ? "Inventory upload failed" : error);
                    }
                });
    }

    private void syncInventory(String deviceId) {
        InstalledAppsManager.getInstance(context).syncInstalledApps(
                deviceId,
                true,
                new InstalledAppsManager.OnSyncCompleteListener() {
                    @Override
                    public void onSuccess(int appCount) {
                        Log.d(TAG,
                                "Post-pairing inventory synced: " + appCount);
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG,
                                "Post-pairing inventory deferred: " + error);
                    }
                });
    }

    private static String safeMessage(String message) {
        return isBlank(message)
                ? "Could not connect this child device. Please try again."
                : message;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
