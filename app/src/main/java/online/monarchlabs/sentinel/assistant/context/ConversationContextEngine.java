package online.monarchlabs.sentinel.assistant.context;

import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.parser.IntentResult;
import online.monarchlabs.sentinel.assistant.parser.IntentDetector;
import online.monarchlabs.sentinel.assistant.parser.SlotExtractor;
import online.monarchlabs.sentinel.assistant.parser.TimeParser;

public class ConversationContextEngine {

    private final IntentDetector intentDetector;
    private final SlotExtractor slotExtractor;

    public ConversationContextEngine() {
        this.intentDetector = new IntentDetector();
        this.slotExtractor = new SlotExtractor(new TimeParser());
    }

    public void processContext(AssistantPlanningRequest request, AssistantConversationState state) {
        if (request == null || state == null) {
            return;
        }

        String input = request.getInputText();
        if (input == null || input.trim().isEmpty()) {
            return;
        }

        online.monarchlabs.sentinel.assistant.parser.ExtractedSlots slots = slotExtractor.extract(input.toLowerCase(java.util.Locale.US));
        if (slots != null) {
            if (!slots.getAppTargets().isEmpty()) {
                state.setSubject(slots.getAppTargets());
            }
        }
    }
}
