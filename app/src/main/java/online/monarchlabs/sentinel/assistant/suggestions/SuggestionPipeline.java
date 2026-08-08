package online.monarchlabs.sentinel.assistant.suggestions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class SuggestionPipeline {
    private final SuggestionEngine engine;
    private final SuggestionRepository repository;

    public SuggestionPipeline(SuggestionEngine engine, SuggestionRepository repository) {
        this.engine = engine;
        this.repository = repository;
    }

    public List<AssistantSuggestion> process(SuggestionContext context) {
        List<AssistantSuggestion> raw = engine.getSuggestions(context);
        List<AssistantSuggestion> filtered = new ArrayList<>();

        long now = context.currentTimeMillis;
        SuggestionConfig config = context.config != null ? context.config : SuggestionConfig.defaultConfig();

        // 1. Filter pipeline
        for (AssistantSuggestion suggestion : raw) {
            // Filter expired suggestions
            if (suggestion.expiresAt > 0 && now >= suggestion.expiresAt) {
                continue;
            }
            // Filter dismissed suggestions
            if (repository.isDismissed(suggestion.id)) {
                continue;
            }
            // Valid suggestions
            filtered.add(suggestion);
        }

        // 2. Sort by: Priority (enum ordinals), then Score (descending), then ExpiresAt (ascending)
        Collections.sort(filtered, new Comparator<AssistantSuggestion>() {
            @Override
            public int compare(AssistantSuggestion o1, AssistantSuggestion o2) {
                // Priority (lower ordinal is higher priority: HIGH, MEDIUM, LOW)
                int priorityComp = Integer.compare(o1.priority.ordinal(), o2.priority.ordinal());
                if (priorityComp != 0) {
                    return priorityComp;
                }
                // Score (higher score first)
                int scoreComp = Integer.compare(o2.score, o1.score);
                if (scoreComp != 0) {
                    return scoreComp;
                }
                // Expiry
                return Long.compare(o1.expiresAt, o2.expiresAt);
            }
        });

        // 3. Apply maximum suggestion limit
        int limit = config.maxSuggestionsToShow;
        if (filtered.size() > limit) {
            return filtered.subList(0, limit);
        }

        return filtered;
    }
}
