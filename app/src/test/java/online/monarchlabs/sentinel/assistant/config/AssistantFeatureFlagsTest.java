package online.monarchlabs.sentinel.assistant.config;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AssistantFeatureFlagsTest {
    @Test
    public void assistantIsDisabledByDefaultUntilRemoteFlagEnablesIt() {
        AssistantFeatureFlags flags = new AssistantFeatureFlags();

        assertFalse(flags.isEnabled(AssistantFeatureFlags.ASSISTANT_ENABLED));
        assertTrue(flags.isEnabled(AssistantFeatureFlags.ASSISTANT_HINGLISH_ENABLED));
    }

    @Test
    public void remoteFlagsOverrideDefaults() {
        Map<String, Boolean> remote = new HashMap<>();
        remote.put(AssistantFeatureFlags.ASSISTANT_ENABLED, true);

        AssistantFeatureFlags flags = new AssistantFeatureFlags(remote);

        assertTrue(flags.isEnabled(AssistantFeatureFlags.ASSISTANT_ENABLED));
    }
}
