package online.monarchlabs.sentinel.assistant.suggestions;

import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository.LiveStateSnapshot;

public final class SuggestionContext {
    public final LiveStateSnapshot snapshot;
    public final long currentTimeMillis;
    public final SuggestionConfig config;

    public SuggestionContext(LiveStateSnapshot snapshot, long currentTimeMillis, SuggestionConfig config) {
        this.snapshot = snapshot;
        this.currentTimeMillis = currentTimeMillis;
        this.config = config;
    }
}
