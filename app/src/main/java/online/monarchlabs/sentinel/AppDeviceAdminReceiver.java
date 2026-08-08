package online.monarchlabs.sentinel;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import online.monarchlabs.sentinel.models.PermissionEvent;

/**
 * Device Admin receiver for uninstall protection.
 */
public class AppDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "DeviceAdmin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.d(TAG, "Device Admin enabled - uninstall protection active");
        Toast.makeText(context, "Uninstall Protection is active", Toast.LENGTH_SHORT).show();
        reportProtectionState(context, true);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.d(TAG, "Device Admin disabled - uninstall protection deactivated");
        Toast.makeText(context, "Uninstall Protection deactivated", Toast.LENGTH_SHORT).show();
        reportProtectionState(context, false);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        Log.w(TAG, "Device Admin disable requested");
        return "Sentinel Device Admin adds Android's verification step before uninstall. The parent dashboard will reflect the updated protection status.";
    }

    private void reportProtectionState(Context context, boolean active) {
        try {
            SessionManager sessionManager = new SessionManager(context);
            String routingKey = sessionManager.getChildDeviceId();
            String parentUserId = sessionManager.getParentUserId();
            String childName = sessionManager.getChildName();

            if (routingKey == null || routingKey.isEmpty()) {
                Log.w(TAG, "Cannot report Uninstall Protection state: child connection is unavailable");
                return;
            }
            if (childName == null || childName.trim().isEmpty()) {
                childName = "Child device";
            }
            final String displayName = childName;

            long timestamp = System.currentTimeMillis();
            Map<String, Object> status = new HashMap<>();
            status.put("uninstallProtectionActive", active);
            status.put("childName", displayName);
            status.put("deviceAdminCheckedAt", timestamp);
            status.put("updatedAt", timestamp);
            status.put("schemaVersion", 2);
            status.put("source", "device_admin_receiver");
            status.put("lastOnlineStatus", "Online at event time");

            FirebaseDatabase.getInstance()
                    .getReference("v2")
                    .child("device_status")
                    .child(routingKey)
                    .updateChildren(status)
                    .addOnFailureListener(error ->
                            Log.e(TAG, "Failed to report Uninstall Protection status", error));

            if (parentUserId == null || parentUserId.isEmpty()) {
                return;
            }

            pushProtectionEvent(parentUserId, routingKey, active, timestamp);
        } catch (Exception error) {
            Log.e(TAG, "Failed to report Device Admin state", error);
        }
    }

    private void pushProtectionEvent(String parentUserId, String routingKey,
            boolean active, long timestamp) {
        Date eventDate = new Date(timestamp);
        PermissionEvent event = new PermissionEvent(
                "Uninstall Protection",
                active ? "ACTIVATED" : "DEACTIVATED",
                active
                        ? "Uninstall Protection is active."
                        : "Uninstall Protection deactivated.",
                timestamp,
                new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(eventDate),
                new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(eventDate));

        online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository
                .appendPermissionEvent(
                        routingKey,
                        "device_admin_" + timestamp,
                        event)
                .addOnFailureListener(error ->
                        Log.e(TAG, "Failed to add Uninstall Protection event", error));
    }}
