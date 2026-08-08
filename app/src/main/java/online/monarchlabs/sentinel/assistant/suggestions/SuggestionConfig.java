package online.monarchlabs.sentinel.assistant.suggestions;

import java.util.concurrent.TimeUnit;

public final class SuggestionConfig {
    public final long usageThresholdMillis;
    public final double recommendationRatio;
    public final int maxSuggestionsToShow;
    public final long defaultCooldownMillis;

    public SuggestionConfig(long usageThresholdMillis, double recommendationRatio, int maxSuggestionsToShow, long defaultCooldownMillis) {
        this.usageThresholdMillis = usageThresholdMillis;
        this.recommendationRatio = recommendationRatio;
        this.maxSuggestionsToShow = maxSuggestionsToShow;
        this.defaultCooldownMillis = defaultCooldownMillis;
    }

    public static SuggestionConfig defaultConfig() {
        return new SuggestionConfig(
                TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(30), // 1.5 hours
                0.67, // ~67% of current usage
                3, // max 3 suggestions
                TimeUnit.DAYS.toMillis(1) // 24 hours cooldown
        );
    }
}
