package online.monarchlabs.sentinel.assistant.context;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

public class AssistantConversationStore {
    private static final String PREF_NAME = "assistant_conversation_context";
    private static final String KEY_STATE = "state";

    private final SharedPreferences preferences;
    private final Gson gson;
    private final String childId;

    public AssistantConversationStore(Context context, String childId) {
        if (context != null) {
            this.preferences = context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } else {
            this.preferences = null;
        }
        this.gson = new Gson();
        this.childId = childId;
    }

    private String getScopedKey() {
        return KEY_STATE + "_" + (childId != null ? childId : "default");
    }

    public synchronized void save(AssistantConversationState state) {
        if (state == null || preferences == null) {
            return;
        }
        preferences.edit()
                .putString(getScopedKey(), gson.toJson(state.toSnapshot()))
                .apply();
    }

    public synchronized void restoreInto(AssistantConversationState state) {
        if (state == null || preferences == null) {
            return;
        }
        String json = preferences.getString(getScopedKey(), null);
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        AssistantConversationState.Snapshot snapshot =
                gson.fromJson(json, AssistantConversationState.Snapshot.class);
        state.restore(snapshot, System.currentTimeMillis());
    }

    public synchronized void clear() {
        preferences.edit().remove(getScopedKey()).apply();
    }
}