package online.monarchlabs.sentinel.assistant.parser;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;

public class IntentResult {
    private final AssistantIntent intent;
    private final float confidence;

    public IntentResult(AssistantIntent intent, float confidence) {
        this.intent = intent;
        this.confidence = confidence;
    }

    public AssistantIntent getIntent() {
        return intent;
    }

    public float getConfidence() {
        return confidence;
    }
}
