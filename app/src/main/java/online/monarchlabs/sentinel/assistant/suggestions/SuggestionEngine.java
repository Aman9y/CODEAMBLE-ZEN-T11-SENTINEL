package online.monarchlabs.sentinel.assistant.suggestions;

import java.util.ArrayList;
import java.util.List;

public final class SuggestionEngine {
    private final List<SuggestionRule> rules = new ArrayList<>();

    public SuggestionEngine() {
    }

    public void registerRule(SuggestionRule rule) {
        if (rule != null) {
            rules.add(rule);
        }
    }

    public List<AssistantSuggestion> getSuggestions(SuggestionContext context) {
        List<AssistantSuggestion> allSuggestions = new ArrayList<>();
        if (context == null || context.snapshot == null) {
            return allSuggestions;
        }

        for (SuggestionRule rule : rules) {
            try {
                List<AssistantSuggestion> suggestions = rule.evaluate(context);
                if (suggestions != null) {
                    allSuggestions.addAll(suggestions);
                }
            } catch (Exception e) {
                android.util.Log.e("SuggestionEngine", "Error executing rule: " + e.getMessage());
            }
        }
        return allSuggestions;
    }
}
