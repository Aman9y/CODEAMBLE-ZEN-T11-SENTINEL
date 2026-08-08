package online.monarchlabs.sentinel.assistant.suggestions;

import java.util.List;

public interface SuggestionRule {
    List<AssistantSuggestion> evaluate(SuggestionContext context);
}
