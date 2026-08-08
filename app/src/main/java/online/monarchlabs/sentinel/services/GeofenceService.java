package online.monarchlabs.sentinel.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

import online.monarchlabs.sentinel.data.FirebaseSchemaV2Repository;

public final class GeofenceService {
    private static final String TAG = "GeofenceService";
    private static final String PREFS_PREFIX = "sentinel_geofence_state_";

    private GeofenceService() {
    }

    public static Task<Void> createSafeZone(String childDeviceId, String parentUid, String name,
            double centerLat, double centerLng, int radiusMeters) {
        String geofenceId = FirebaseDatabase.getInstance()
                .getReference(FirebaseSchemaV2Repository.ROOT)
                .child("geofences")
                .child(childDeviceId)
                .push()
                .getKey();
        Map<String, Object> data = new HashMap<>();
        data.put("geofenceId", geofenceId);
        data.put("childDeviceId", childDeviceId);
        data.put("createdByParentUid", parentUid);
        data.put("name", name);
        data.put("centerLat", centerLat);
        data.put("centerLng", centerLng);
        data.put("radiusMeters", radiusMeters);
        data.put("alertOnEnter", true);
        data.put("alertOnExit", true);
        data.put("active", true);
        data.put("createdAt", ServerValue.TIMESTAMP);
        data.put("updatedAt", ServerValue.TIMESTAMP);
        return FirebaseDatabase.getInstance()
                .getReference(FirebaseSchemaV2Repository.ROOT)
                .child("geofences")
                .child(childDeviceId)
                .child(geofenceId)
                .setValue(data);
    }

    public static void evaluateAndPublish(Context context, String childDeviceId, double lat, double lng) {
        if (context == null || childDeviceId == null || childDeviceId.isEmpty()) {
            return;
        }
        FirebaseDatabase.getInstance()
                .getReference(FirebaseSchemaV2Repository.ROOT)
                .child("geofences")
                .child(childDeviceId)
                .get()
                .addOnSuccessListener(snapshot -> evaluateSnapshot(context, childDeviceId, lat, lng, snapshot))
                .addOnFailureListener(error -> Log.w(TAG, "Geofence read failed: " + error.getMessage()));
    }

    private static void evaluateSnapshot(Context context, String childDeviceId, double lat, double lng,
            DataSnapshot snapshot) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_PREFIX + childDeviceId, Context.MODE_PRIVATE);
        for (DataSnapshot child : snapshot.getChildren()) {
            String geofenceId = child.getKey();
            Boolean active = child.child("active").getValue(Boolean.class);
            Double centerLat = child.child("centerLat").getValue(Double.class);
            Double centerLng = child.child("centerLng").getValue(Double.class);
            Integer radiusMeters = child.child("radiusMeters").getValue(Integer.class);
            if (geofenceId == null || !Boolean.TRUE.equals(active)
                    || centerLat == null || centerLng == null || radiusMeters == null) {
                continue;
            }

            boolean inside = distanceMeters(lat, lng, centerLat, centerLng) <= radiusMeters;
            if (!prefs.contains(geofenceId)) {
                prefs.edit().putBoolean(geofenceId, inside).apply();
                continue;
            }

            boolean wasInside = prefs.getBoolean(geofenceId, false);
            if (wasInside == inside) {
                continue;
            }
            prefs.edit().putBoolean(geofenceId, inside).apply();

            boolean shouldAlert = inside
                    ? Boolean.TRUE.equals(child.child("alertOnEnter").getValue(Boolean.class))
                    : Boolean.TRUE.equals(child.child("alertOnExit").getValue(Boolean.class));
            if (shouldAlert) {
                publishEvent(childDeviceId, geofenceId, child, inside ? "enter" : "exit", lat, lng, radiusMeters);
            }
        }
    }

    private static float distanceMeters(double lat, double lng, double centerLat, double centerLng) {
        float[] result = new float[1];
        Location.distanceBetween(lat, lng, centerLat, centerLng, result);
        return result[0];
    }

    private static void publishEvent(String childDeviceId, String geofenceId, DataSnapshot geofence,
            String transition, double lat, double lng, int radiusMeters) {
        String eventId = FirebaseDatabase.getInstance()
                .getReference(FirebaseSchemaV2Repository.ROOT)
                .child("geofence_events")
                .child(childDeviceId)
                .push()
                .getKey();
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", eventId);
        event.put("childDeviceId", childDeviceId);
        event.put("geofenceId", geofenceId);
        event.put("geofenceName", geofence.child("name").getValue(String.class));
        event.put("transition", transition);
        event.put("lat", lat);
        event.put("lng", lng);
        event.put("radiusMeters", radiusMeters);
        event.put("status", "unread");
        event.put("createdAt", ServerValue.TIMESTAMP);
        FirebaseDatabase.getInstance()
                .getReference(FirebaseSchemaV2Repository.ROOT)
                .child("geofence_events")
                .child(childDeviceId)
                .child(eventId)
                .setValue(event);
    }
}
