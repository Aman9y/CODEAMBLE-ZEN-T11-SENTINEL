package online.monarchlabs.sentinel.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import online.monarchlabs.sentinel.ParentDashboardActivity;
import online.monarchlabs.sentinel.R;
import online.monarchlabs.sentinel.SessionManager;
import online.monarchlabs.sentinel.Master2Application;
import online.monarchlabs.sentinel.models.PermissionEvent;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

import online.monarchlabs.sentinel.utils.ChildDisplayName;

/**
 * Background service that listens for child device permission changes
 * and shows Android system notifications on parent device
 */
public class PermissionEventListener extends Service {
    private static final String TAG = "PermissionEventListener";
    private static final String CHANNEL_ID = "child_service_alerts";
    private static final String FOREGROUND_CHANNEL_ID = "permission_listener_fg";
    private static final int FOREGROUND_NOTIFICATION_ID = 9002;
    private static final String DELIVERY_PREFS = "permission_event_delivery";
    private static final long INITIAL_EVENT_LOOKBACK_MS = 5 * 60 * 1000L;

    private SessionManager sessionManager;
    private DatabaseReference permissionEventsRef;
    private DatabaseReference sosEventsRef;
    private ChildEventListener sosEventsListener;
    private Map<String, ChildEventListener> childListeners = new HashMap<>();
    private Map<String, DatabaseReference> childEventRefs = new HashMap<>();
    private Map<String, String> childNames = new HashMap<>(); // deviceId -> childName
    private SharedPreferences deliveryPrefs;
    private long serviceStartTime;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Ã°Å¸â€â€ PermissionEventListener service starting...");

        sessionManager = new SessionManager(this);
        deliveryPrefs = getSharedPreferences(DELIVERY_PREFS, MODE_PRIVATE);
        serviceStartTime = System.currentTimeMillis();
        createNotificationChannels();
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification());

        // Check if parent is logged in
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null
                || !"parent".equals(sessionManager.getUserType())) {
            Log.w(TAG, "Ã¢Å¡Â Ã¯Â¸Â Parent not logged in, stopping service");
            stopSelf();
            return;
        }

        String parentUserId = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                : sessionManager.getParentUserId();

        if (parentUserId == null || parentUserId.isEmpty()) {
            Log.e(TAG, "Ã¢ÂÅ’ No parent user ID, cannot listen for events");
            stopSelf();
            return;
        }

        setupFirebaseListeners(parentUserId);
        setupSosListener(parentUserId);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);

            // Channel for foreground service
            NotificationChannel fgChannel = new NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    "Service Monitor",
                    NotificationManager.IMPORTANCE_LOW);
            fgChannel.setDescription("Keeps service running in background");
            manager.createNotificationChannel(fgChannel);

            // Channel for child device alerts
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Child Device Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("Notifications when child device services are activated/deactivated");
            alertChannel.enableVibration(true);
            manager.createNotificationChannel(alertChannel);
        }
    }

    private Notification buildForegroundNotification() {
        return new NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                .setContentTitle("Monitoring Child Devices")
                .setContentText("Listening for service status changes")
                .setSmallIcon(R.drawable.ic_child)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void setupFirebaseListeners(String parentUserId) {
        Log.d(TAG, "Ã°Å¸â€œÂ¡ Setting up Firebase listeners for parent: " + parentUserId);

        permissionEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("parent_device_links")
                .child(parentUserId);

        // Listen for all children under this parent
        permissionEventsRef.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot childSnapshot, String previousChildKey) {
                String childDeviceId = childSnapshot.getKey();
                Log.d(TAG, "Ã°Å¸â€ â€¢ Detected child device: " + childDeviceId);
                String displayName = ChildDisplayName.resolve(childDeviceId,
                        childSnapshot.child("childName").getValue(String.class),
                        childSnapshot.child("userName").getValue(String.class),
                        childSnapshot.child("childDeviceName").getValue(String.class),
                        childSnapshot.child("deviceName").getValue(String.class));
                if (childDeviceId != null) {
                    childNames.put(childDeviceId, displayName);
                }
                listenToChildEvents(childDeviceId);
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildName) {
                // Not needed
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
                String childDeviceId = snapshot.getKey();
                Log.d(TAG, "Ã¢ÂÅ’ Child device removed: " + childDeviceId);
                stopListeningToChild(childDeviceId);
            }

            @Override
            public void onChildMoved(DataSnapshot snapshot, String previousChildName) {
                // Not needed
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Firebase listener cancelled: " + error.getMessage());
            }
        });
    }

    private void listenToChildEvents(String childDeviceId) {
        if (childListeners.containsKey(childDeviceId)) {
            Log.d(TAG, "Already listening to child: " + childDeviceId);
            return;
        }

        DatabaseReference eventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("permission_logs")
                .child(childDeviceId);

        ChildEventListener listener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot eventSnapshot, String previousChildKey) {
                PermissionEvent event = eventSnapshot.getValue(PermissionEvent.class);
                if (event != null) {
                    Log.d(TAG, "Ã°Å¸â€â€ New permission event: " + event.getPermissionName() + " -> " + event.getAction());
                    long lastDelivered = deliveryPrefs.getLong(
                            childDeviceId,
                            serviceStartTime - INITIAL_EVENT_LOOKBACK_MS);
                    if (event.getTimestamp() <= lastDelivered) {
                        return;
                    }
                    deliveryPrefs.edit()
                            .putLong(childDeviceId, event.getTimestamp())
                            .apply();
                    // Permission changes are security-relevant and must reach the
                    // parent even when the parent app UI is in the background.
                    showSystemNotification(childDeviceId, event);
                }
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildName) {
                // Not needed
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
                // Not needed
            }

            @Override
            public void onChildMoved(DataSnapshot snapshot, String previousChildName) {
                // Not needed
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Events listener cancelled: " + error.getMessage());
            }
        };

        eventsRef.addChildEventListener(listener);
        childListeners.put(childDeviceId, listener);
        childEventRefs.put(childDeviceId, eventsRef);
        Log.d(TAG, "Ã¢Å“â€¦ Now listening to events for: " + childDeviceId);
    }

    private void setupSosListener(String parentUserId) {
        sosEventsRef = FirebaseDatabase.getInstance()
                .getReference("v2")
                .child("sos_events")
                .child(parentUserId);
        sosEventsListener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String previousChildKey) {
                handleSosEvent(snapshot);
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildKey) {
                handleSosEvent(snapshot);
            }

            @Override public void onChildRemoved(DataSnapshot snapshot) {}
            @Override public void onChildMoved(DataSnapshot snapshot, String previousChildKey) {}

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "SOS listener cancelled: " + error.getMessage());
            }
        };
        sosEventsRef.addChildEventListener(sosEventsListener);
        Log.d(TAG, "SOS listener attached for parent: " + parentUserId);
    }

    private void handleSosEvent(DataSnapshot snapshot) {
        String status = snapshot.child("status").getValue(String.class);
        if (!"active".equals(status)) {
            return;
        }
        String eventId = snapshot.child("eventId").getValue(String.class);
        if (eventId == null || eventId.isEmpty()) {
            eventId = snapshot.getKey();
        }
        Long createdAt = snapshot.child("createdAt").getValue(Long.class);
        long eventTime = createdAt != null ? createdAt : System.currentTimeMillis();
        String deliveryKey = "sos_" + eventId;
        long lastDelivered = deliveryPrefs.getLong(
                deliveryKey,
                serviceStartTime - INITIAL_EVENT_LOOKBACK_MS);
        if (eventTime <= lastDelivered) {
            return;
        }
        deliveryPrefs.edit().putLong(deliveryKey, eventTime).apply();

        String childName = ChildDisplayName.resolve(
                snapshot.child("childDeviceId").getValue(String.class),
                snapshot.child("childName").getValue(String.class));
        String reason = snapshot.child("reason").getValue(String.class);
        showSosSystemNotification(eventId, childName, reason);
    }

    private void stopListeningToChild(String childDeviceId) {
        ChildEventListener listener = childListeners.remove(childDeviceId);
        if (listener != null) {
            DatabaseReference eventsRef = childEventRefs.remove(childDeviceId);
            if (eventsRef != null) {
                eventsRef.removeEventListener(listener);
            }
            Log.d(TAG, "Ã°Å¸â€ºâ€˜ Stopped listening to: " + childDeviceId);
        }
    }

    private void showSystemNotification(String childDeviceId, PermissionEvent event) {
        String childName = childNames.getOrDefault(childDeviceId, "Child Device");
        String permissionName = cleanDisplayText(event.getPermissionName());
        String action = cleanDisplayText(event.getAction());
        String effect = cleanDisplayText(event.getEffect());
        boolean isActivated = "ACTIVATED".equals(action);

        // Build notification
        Intent intent = new Intent(this, ParentDashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Choose icon and color
        int icon = isActivated ? R.drawable.ic_child : R.drawable.ic_refresh;
        int color = isActivated ? 0xFF4CAF50 : 0xFFF44336; // Green or Red

        boolean protectionStateChanged = "Uninstall Protection".equals(permissionName);
        String title = protectionStateChanged
                ? (isActivated ? "Protection active - " : "Protection deactivated - ") + childName
                : childName + ": " + permissionName + " " + action;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(effect)
                .setSmallIcon(icon)
                .setColor(color)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(effect))
                .setGroup("child_" + childDeviceId); // Group by child device

        // Show notification
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        int notificationId = 10000
                + ((childDeviceId + ":" + event.getPermissionName()).hashCode() & 0x7fffffff) % 100000;
        notificationManager.notify(notificationId, builder.build());

        Log.d(TAG, "Ã°Å¸â€œÂ² Notification shown: " + title);
    }

    private String cleanDisplayText(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\uFFFD", "");
            cleaned = cleaned.replaceAll("[\u00C3\u00C2\u00E2\u00F0][^\\s]*\\s*", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.contains("App Blocked")) {
            return "App Blocked";
        }
        if (cleaned.contains("App Unblocked")) {
            return "App Unblocked";
        }
        if (cleaned.contains("Uninstall Protection")) {
            return cleaned.substring(cleaned.indexOf("Uninstall Protection"));
        }
        if (cleaned.contains("Protection Restored")) {
            return cleaned.substring(cleaned.indexOf("Protection Restored"));
        }
        if (cleaned.contains("Usage synced")) {
            return cleaned.substring(cleaned.indexOf("Usage synced"));
        }
        return cleaned;
    }

    private void showSosSystemNotification(String eventId, String childName, String reason) {
        Intent intent = new Intent(this, ParentDashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                eventId != null ? eventId.hashCode() : 9001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String name = childName != null && !childName.isEmpty() ? childName : "Child";
        String message = reason != null && !reason.isEmpty() ? reason : "I need help";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SOS from " + name)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_warning)
                .setColor(0xFFDC2626)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message));

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        int notificationId = 900000
                + ((eventId != null ? eventId : name).hashCode() & 0x7fffffff) % 100000;
        notificationManager.notify(notificationId, builder.build());
        Log.d(TAG, "SOS notification shown for: " + name);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        return START_STICKY;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, PermissionEventListener.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to start parent permission listener", error);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Clean up all listeners
        if (permissionEventsRef != null) {
            for (Map.Entry<String, ChildEventListener> entry : childListeners.entrySet()) {
                DatabaseReference eventsRef = childEventRefs.get(entry.getKey());
                if (eventsRef != null) {
                    eventsRef.removeEventListener(entry.getValue());
                }
            }
        }
        childListeners.clear();
        childEventRefs.clear();
        if (sosEventsRef != null && sosEventsListener != null) {
            sosEventsRef.removeEventListener(sosEventsListener);
        }
        sosEventsRef = null;
        sosEventsListener = null;

        Log.d(TAG, "PermissionEventListener destroyed");
    }
}
