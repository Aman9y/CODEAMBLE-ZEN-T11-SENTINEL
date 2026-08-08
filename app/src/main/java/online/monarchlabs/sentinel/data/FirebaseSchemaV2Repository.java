package online.monarchlabs.sentinel.data;

import com.google.android.gms.tasks.Task;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/** Canonical client repository for the v2 Firebase schema. */
public final class FirebaseSchemaV2Repository {
    public static final String ROOT = "v2";
    public static final int SCHEMA_VERSION = 2;
    public static final int APP_LIMITS_SCHEMA_VERSION = 2;

    private FirebaseSchemaV2Repository() {
    }

    public static Task<Void> syncParentIdentity(String uid, String displayName, String email,
            String phone, String deviceId, Long createdAt) {
        Map<String, Object> updates = new HashMap<>();
        addParentIdentityUpdates(updates, uid, displayName, email, phone, deviceId, createdAt);
        return FirebaseDatabase.getInstance().getReference().updateChildren(updates);
    }

    public static void addParentIdentityUpdates(Map<String, Object> updates, String uid,
            String displayName, String email, String phone, String deviceId, Long createdAt) {
        if (updates == null || isBlank(uid)) {
            return;
        }

        String userBase = ROOT + "/users/" + uid;
        updates.put(userBase + "/authUid", uid);
        updates.put(userBase + "/role", "parent");
        updates.put(userBase + "/status", "active");
        updates.put(userBase + "/updatedAt", ServerValue.TIMESTAMP);
        if (createdAt != null && createdAt > 0) {
            updates.put(userBase + "/createdAt", createdAt);
        }

        String profileBase = ROOT + "/parent_profiles/" + uid;
        updates.put(profileBase + "/displayName", valueOrEmpty(displayName));
        updates.put(profileBase + "/email", valueOrEmpty(email));
        updates.put(profileBase + "/phone", valueOrEmpty(phone));
        updates.put(profileBase + "/primaryDeviceId", valueOrEmpty(deviceId));
        updates.put(profileBase + "/updatedAt", ServerValue.TIMESTAMP);
    }

    public static Task<Void> syncUsagePatch(String deviceId, Map<String, Object> relativeUpdates) {
        return syncUsagePatch(deviceId, relativeUpdates, null, null);
    }

    public static Task<Void> syncUsagePatch(String deviceId,
            Map<String, Object> relativeUpdates,
            Map<String, Object> appCatalogUpdates,
            Map<String, Object> bootstrapMetadata) {
        Map<String, Object> updates = new HashMap<>();
        if (relativeUpdates != null) {
            for (Map.Entry<String, Object> entry : relativeUpdates.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isEmpty()) {
                    updates.put(
                            ROOT + "/usage_daily/" + deviceId + "/" + entry.getKey(),
                            entry.getValue());
                }
            }
        }
        if (appCatalogUpdates != null) {
            for (Map.Entry<String, Object> entry : appCatalogUpdates.entrySet()) {
                if (!isBlank(entry.getKey())) {
                    updates.put(
                            ROOT + "/app_catalog/" + deviceId + "/" + entry.getKey(),
                            entry.getValue());
                }
            }
        }
        if (bootstrapMetadata != null) {
            updates.put(
                    ROOT + "/device_status/" + deviceId + "/usageBootstrap",
                    bootstrapMetadata);
        }
        if (updates.isEmpty()) {
            return com.google.android.gms.tasks.Tasks.forResult(null);
        }
        return FirebaseDatabase.getInstance().getReference().updateChildren(updates);
    }
    public static Task<Void> syncDeviceInstalls(String deviceId, Map<String, Object> inventory) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_installs").child(deviceId).setValue(inventory);
    }

    public static Task<Void> syncSingleDeviceInstall(String deviceId, String appKey,
            Map<String, Object> appData) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_installs").child(deviceId)
                .child("apps").child(appKey).setValue(appData);
    }

    public static Task<Void> removeSingleDeviceInstall(String deviceId, String appKey) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_installs").child(deviceId)
                .child("apps").child(appKey).removeValue();
    }

    public static Task<Void> syncDeviceInstallPatch(String deviceId,
            Map<String, Object> relativeUpdates) {
        Map<String, Object> updates = new HashMap<>();
        if (relativeUpdates != null) {
            for (Map.Entry<String, Object> entry : relativeUpdates.entrySet()) {
                if (!isBlank(entry.getKey())) {
                    updates.put(ROOT + "/device_installs/" + deviceId + "/" + entry.getKey(),
                            entry.getValue());
                }
            }
        }
        return FirebaseDatabase.getInstance().getReference().updateChildren(updates);
    }

    public static Task<Void> syncAppBlockPolicy(String deviceId, String appKey,
            Map<String, Object> policy) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_policies").child(deviceId)
                .child("blocked_apps").child(appKey).setValue(policy);
    }

    public static Task<Void> syncAppBlockState(String deviceId, String appKey,
            Map<String, Object> state) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("app_block_state").child(deviceId)
                .child(appKey).setValue(state);
    }

    public static Task<Void> syncAppTimerPolicy(String deviceId, String appKey,
            Map<String, Object> timerPolicy) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_policies").child(deviceId)
                .child("app_timers").child(appKey).setValue(timerPolicy);
    }

    public static Task<Void> removeAppTimerPolicy(String deviceId, String appKey) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_policies").child(deviceId)
                .child("app_timers").child(appKey).removeValue();
    }

    public static Task<Void> syncAppTimerPolicies(String deviceId,
            Map<String, Object> timerPolicies) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_policies").child(deviceId)
                .child("app_timers").setValue(timerPolicies);
    }

    public static Task<Void> syncContentFilteringPolicy(String deviceId,
            Map<String, Object> policy) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_policies").child(deviceId)
                .child("content_filtering").setValue(policy);
    }

    public static Task<Void> syncDeviceStatus(String deviceId, Map<String, Object> status) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_status").child(deviceId).setValue(status);
    }

    public static Task<Void> publishAppLimitsCapability(String deviceId) {
        Map<String, Object> updates = new HashMap<>();
        String statusBase = ROOT + "/device_status/" + deviceId;
        String capabilityBase = ROOT + "/devices/" + deviceId + "/capabilities";
        updates.put(
                statusBase + "/appLimitsSchemaVersion",
                APP_LIMITS_SCHEMA_VERSION);
        updates.put(
                statusBase + "/appLimitsCapabilityUpdatedAt",
                ServerValue.TIMESTAMP);
        updates.put(
                capabilityBase + "/appLimitsSchemaVersion",
                APP_LIMITS_SCHEMA_VERSION);
        updates.put(
                capabilityBase + "/updatedAt",
                ServerValue.TIMESTAMP);
        return FirebaseDatabase.getInstance().getReference().updateChildren(updates);
    }

    public static Task<Void> syncPermissionsCurrent(String deviceId, Map<String, Object> status) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("permissions_current").child(deviceId).setValue(status);
    }

    public static Task<Void> appendPermissionEvent(String deviceId, String eventId, Object event) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("permission_logs").child(deviceId).child(eventId).setValue(event);
    }

    public static Task<Void> syncDeviceHealth(String deviceId, Map<String, Object> health) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("device_health").child(deviceId).setValue(health);
    }

    public static Task<Void> syncLocation(String deviceId, Map<String, Object> location) {
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("locations").child(deviceId).setValue(location);
    }

    public static Task<Void> patchLocation(String deviceId, Map<String, Object> locationPatch) {
        if (locationPatch == null || locationPatch.isEmpty()) {
            return com.google.android.gms.tasks.Tasks.forResult(null);
        }
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("locations").child(deviceId)
                .updateChildren(locationPatch);
    }
    public static Task<Void> requestLocationRefresh(String deviceId) {
        Map<String, Object> command = new HashMap<>();
        command.put("command", "refresh_location");
        command.put("status", "pending");
        command.put("issuedAt", System.currentTimeMillis());
        command.put("requestId", java.util.UUID.randomUUID().toString());
        return FirebaseDatabase.getInstance().getReference()
                .child(ROOT).child("commands").child(deviceId)
                .child("location_refresh").setValue(command);
    }
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
