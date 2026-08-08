package online.monarchlabs.sentinel.services;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.text.TextUtils;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;

public final class SosService {
    private SosService() {
    }

    public static Task<Void> sendSos(Context context, String parentUid, String childDeviceId,
            String childName, String deviceName, String reason) {
        if (TextUtils.isEmpty(parentUid) || TextUtils.isEmpty(childDeviceId)) {
            return Tasks.forException(new IllegalArgumentException("Missing SOS connection details."));
        }

        return FirebaseDatabase.getInstance().getReference()
                .child(FirebaseSchemaV2Repository.ROOT)
                .child("locations")
                .child(childDeviceId)
                .get()
                .continueWithTask(locationTask -> {
                    Map<String, Object> event = new HashMap<>();
                    String eventId = UUID.randomUUID().toString();
                    event.put("eventId", eventId);
                    event.put("parentUid", parentUid);
                    event.put("childDeviceId", childDeviceId);
                    event.put("childName", valueOr(childName, "Child"));
                    event.put("deviceName", valueOr(deviceName, Build.MODEL));
                    event.put("reason", valueOr(reason, "I need help"));
                    event.put("status", "active");
                    event.put("createdAt", ServerValue.TIMESTAMP);
                    event.put("updatedAt", ServerValue.TIMESTAMP);
                    event.put("batteryPercent", batteryPercent(context));

                    if (locationTask.isSuccessful() && locationTask.getResult() != null
                            && locationTask.getResult().exists()) {
                        Object lat = locationTask.getResult().child("latitude").getValue();
                        Object lng = locationTask.getResult().child("longitude").getValue();
                        Object accuracy = locationTask.getResult().child("accuracy").getValue();
                        Object timestamp = locationTask.getResult().child("timestamp").getValue();
                        if (lat != null && lng != null) {
                            Map<String, Object> location = new HashMap<>();
                            location.put("latitude", lat);
                            location.put("longitude", lng);
                            if (accuracy != null) {
                                location.put("accuracy", accuracy);
                            }
                            if (timestamp != null) {
                                location.put("timestamp", timestamp);
                            }
                            event.put("location", location);
                        }
                    }

                    Map<String, Object> updates = new HashMap<>();
                    updates.put(FirebaseSchemaV2Repository.ROOT + "/sos_events/"
                            + parentUid + "/" + eventId, event);
                    updates.put(FirebaseSchemaV2Repository.ROOT + "/sos_active_by_device/"
                            + childDeviceId, event);
                    return FirebaseDatabase.getInstance().getReference().updateChildren(updates);
                });
    }

    private static int batteryPercent(Context context) {
        try {
            Intent batteryStatus = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus == null) {
                return -1;
            }
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) {
                return -1;
            }
            return Math.round(level * 100f / scale);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String valueOr(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }
}
