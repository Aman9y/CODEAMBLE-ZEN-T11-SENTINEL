package online.monarchlabs.sentinel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import online.monarchlabs.sentinel.workers.UsageUploadScheduler;

import java.util.UUID;

/**
 * Prepares local child state after a successful v2 pairing.
 *
 * Parent-owned Firebase data is never deleted here. Canonical removal owns remote
 * cleanup; a same-parent reinstall/reconnect must preserve policies and history.
 */
public final class FreshConnectionManager {
    private static final String TAG = "FreshConnectionManager";
    private static final String PREFS_NAME = "connection_state";
    private static final String KEY_LAST_CONNECTION_TIME = "last_connection_time";
    private static final String KEY_CONNECTION_SESSION_ID = "connection_session_id";

    private final Context context;
    private final String deviceId;
    private final SharedPreferences prefs;

    public FreshConnectionManager(Context context, String deviceId) {
        this.context = context.getApplicationContext();
        this.deviceId = deviceId == null ? "" : deviceId;
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void handleFreshConnection() {
        if (deviceId.isEmpty()) {
            Log.w(TAG, "Fresh connection ignored because device ID is missing");
            return;
        }
        ChildConnectionDataCleaner.clear(context, deviceId);
        long now = System.currentTimeMillis();
        String sessionId = UUID.randomUUID().toString();
        prefs.edit()
                .putLong(KEY_LAST_CONNECTION_TIME, now)
                .putString(KEY_CONNECTION_SESSION_ID, sessionId)
                .commit();

        String connectionId = new SessionManager(context).getConnectionId();
        UsageUploadScheduler.triggerBootstrapUpload(context, connectionId);
        Log.d(TAG, "Prepared local v2 state for connection " + connectionId);
    }

    public boolean shouldClearDataOnConnection() {
        return prefs.getLong(KEY_LAST_CONNECTION_TIME, 0L) == 0L;
    }

    public boolean isFreshlyConnected() {
        long connectedAt = prefs.getLong(KEY_LAST_CONNECTION_TIME, 0L);
        return connectedAt > 0L
                && System.currentTimeMillis() - connectedAt < 30_000L;
    }

    public void markConnectionEstablished() {
        prefs.edit()
                .putLong(KEY_LAST_CONNECTION_TIME, System.currentTimeMillis())
                .putString(KEY_CONNECTION_SESSION_ID, UUID.randomUUID().toString())
                .commit();
    }

    public String getCurrentSessionId() {
        return prefs.getString(KEY_CONNECTION_SESSION_ID, "");
    }

    public void forceCompleteReset() {
        ChildConnectionDataCleaner.clear(context, deviceId);
        prefs.edit().clear().commit();
    }
}