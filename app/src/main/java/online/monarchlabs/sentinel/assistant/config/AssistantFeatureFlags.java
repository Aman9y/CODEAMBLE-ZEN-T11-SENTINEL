package online.monarchlabs.sentinel.assistant.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class AssistantFeatureFlags {
    public static final String ASSISTANT_ENABLED = "assistant_enabled";
    public static final String ASSISTANT_VOICE_ENABLED = "assistant_voice_enabled";
    public static final String ASSISTANT_HINGLISH_ENABLED = "assistant_hinglish_enabled";
    public static final String ASSISTANT_ROUTINES_ENABLED = "assistant_routines_enabled";
    public static final String ASSISTANT_ALL_CHILDREN_ENABLED = "assistant_all_children_enabled";
    public static final String ASSISTANT_UNDO_ENABLED = "assistant_undo_enabled";
    public static final String ASSISTANT_SUGGESTIONS_ENABLED = "assistant_suggestions_enabled";
    public static final String ASSISTANT_DEBUG_LOGS_ENABLED = "assistant_debug_logs_enabled";
    public static final String ASSISTANT_LOCAL_OUTBOX_ENABLED = "assistant_local_outbox_enabled";

    private final Map<String, Boolean> flags;

    public AssistantFeatureFlags() {
        Map<String, Boolean> defaults = new HashMap<>();
        defaults.put(ASSISTANT_ENABLED, false);
        defaults.put(ASSISTANT_VOICE_ENABLED, false);
        defaults.put(ASSISTANT_HINGLISH_ENABLED, true);
        defaults.put(ASSISTANT_ROUTINES_ENABLED, false);
        defaults.put(ASSISTANT_ALL_CHILDREN_ENABLED, false);
        defaults.put(ASSISTANT_UNDO_ENABLED, true);
        defaults.put(ASSISTANT_SUGGESTIONS_ENABLED, true);
        defaults.put(ASSISTANT_DEBUG_LOGS_ENABLED, false);
        defaults.put(ASSISTANT_LOCAL_OUTBOX_ENABLED, true);
        this.flags = defaults;
    }

    public AssistantFeatureFlags(Map<String, Boolean> remoteFlags) {
        this();
        if (remoteFlags != null) {
            flags.putAll(remoteFlags);
        }
    }

    public boolean isEnabled(String key) {
        Boolean value = flags.get(key);
        return value != null && value;
    }

    public Map<String, Boolean> asMap() {
        return Collections.unmodifiableMap(flags);
    }
}
