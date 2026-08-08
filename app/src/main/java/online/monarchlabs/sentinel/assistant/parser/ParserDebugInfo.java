package online.monarchlabs.sentinel.assistant.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;

public class ParserDebugInfo {
    private final String rawInput;
    private String normalizedInput;
    private final List<String> matchedAliases = new ArrayList<>();
    private AssistantIntent detectedIntent;
    private float intentConfidence;
    private ExtractedSlots extractedSlots;
    private final List<String> missingSlots = new ArrayList<>();
    private final List<String> ambiguities = new ArrayList<>();

    public ParserDebugInfo(String rawInput) {
        this.rawInput = rawInput;
    }

    public String getRawInput() {
        return rawInput;
    }

    public String getNormalizedInput() {
        return normalizedInput;
    }

    public void setNormalizedInput(String normalizedInput) {
        this.normalizedInput = normalizedInput;
    }

    public void addMatchedAlias(String alias) {
        matchedAliases.add(alias);
    }

    public List<String> getMatchedAliases() {
        return Collections.unmodifiableList(matchedAliases);
    }

    public AssistantIntent getDetectedIntent() {
        return detectedIntent;
    }

    public void setDetectedIntent(AssistantIntent detectedIntent) {
        this.detectedIntent = detectedIntent;
    }

    public float getIntentConfidence() {
        return intentConfidence;
    }

    public void setIntentConfidence(float intentConfidence) {
        this.intentConfidence = intentConfidence;
    }

    public ExtractedSlots getExtractedSlots() {
        return extractedSlots;
    }

    public void setExtractedSlots(ExtractedSlots extractedSlots) {
        this.extractedSlots = extractedSlots;
    }

    public void addMissingSlot(String slot) {
        missingSlots.add(slot);
    }

    public List<String> getMissingSlots() {
        return Collections.unmodifiableList(missingSlots);
    }

    public void addAmbiguity(String ambiguity) {
        ambiguities.add(ambiguity);
    }

    public List<String> getAmbiguities() {
        return Collections.unmodifiableList(ambiguities);
    }
}
