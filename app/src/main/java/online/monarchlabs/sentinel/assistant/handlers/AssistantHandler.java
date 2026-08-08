package online.monarchlabs.sentinel.assistant.handlers;

import java.util.List;
import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.reliability.AssistantResult;

public interface AssistantHandler {
    List<AssistantIntent.Category> getSupportedCategories();
    AssistantResult<?> handle(AssistantPlanningRequest request, AssistantPlanningResult planResult,
                              online.monarchlabs.sentinel.assistant.context.AssistantConversationState state);
}
