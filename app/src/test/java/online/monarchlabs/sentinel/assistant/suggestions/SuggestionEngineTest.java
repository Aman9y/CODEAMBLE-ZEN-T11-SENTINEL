package online.monarchlabs.sentinel.assistant.suggestions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository.LiveStateSnapshot;
import org.junit.Test;

public final class SuggestionEngineTest {

    @Test
    public void testTimerRecommendationRule() {
        LiveStateSnapshot snapshot = new LiveStateSnapshot();
        snapshot.appUsageMillis.put("com.roblox.client", TimeUnit.HOURS.toMillis(2)); // 2 hours
        snapshot.packageToAppName.put("com.roblox.client", "Roblox");

        SuggestionConfig config = SuggestionConfig.defaultConfig();
        SuggestionContext context = new SuggestionContext(snapshot, System.currentTimeMillis(), config);

        AppTimerRecommendationRule rule = new AppTimerRecommendationRule();
        List<AssistantSuggestion> suggestions = rule.evaluate(context);

        assertEquals(1, suggestions.size());
        AssistantSuggestion suggestion = suggestions.get(0);
        assertEquals("APP_TIMER:com.roblox.client", suggestion.id);
        assertEquals(AssistantSuggestion.Type.APP_TIMER_RECOMMENDATION, suggestion.type);
        assertEquals(AssistantSuggestion.ActionType.SET_TIMER, suggestion.actionType);
        assertEquals("com.roblox.client", suggestion.metadata.get("packageName"));
        assertEquals("Roblox", suggestion.metadata.get("appName"));

        // Recommended limit should be computed dynamically (~67% of 2h rounded to nearest 30 mins)
        // 2h * 0.67 = 1.34h = 80.4 minutes -> round to nearest 30 mins -> 90 minutes = 5400000 ms
        long expectedLimit = TimeUnit.MINUTES.toMillis(90);
        assertEquals(expectedLimit, suggestion.metadata.get("limitMillis"));
    }

    @Test
    public void testPolicyAwareness_WithExistingTimer() {
        LiveStateSnapshot snapshot = new LiveStateSnapshot();
        snapshot.appUsageMillis.put("com.roblox.client", TimeUnit.HOURS.toMillis(2));
        snapshot.packageToAppName.put("com.roblox.client", "Roblox");
        snapshot.activeTimerPackages.add("com.roblox.client"); // Existing active timer policy

        SuggestionConfig config = SuggestionConfig.defaultConfig();
        SuggestionContext context = new SuggestionContext(snapshot, System.currentTimeMillis(), config);

        AppTimerRecommendationRule rule = new AppTimerRecommendationRule();
        List<AssistantSuggestion> suggestions = rule.evaluate(context);

        assertTrue(suggestions.isEmpty()); // Should not suggest if active timer already exists
    }

    @Test
    public void testPolicyAwareness_WithBlockedApp() {
        LiveStateSnapshot snapshot = new LiveStateSnapshot();
        snapshot.appUsageMillis.put("com.roblox.client", TimeUnit.HOURS.toMillis(2));
        snapshot.packageToAppName.put("com.roblox.client", "Roblox");
        snapshot.blockedPackages.add("com.roblox.client"); // Existing block policy

        SuggestionConfig config = SuggestionConfig.defaultConfig();
        SuggestionContext context = new SuggestionContext(snapshot, System.currentTimeMillis(), config);

        AppTimerRecommendationRule rule = new AppTimerRecommendationRule();
        List<AssistantSuggestion> suggestions = rule.evaluate(context);

        assertTrue(suggestions.isEmpty()); // Should not suggest if app is already blocked
    }
}
