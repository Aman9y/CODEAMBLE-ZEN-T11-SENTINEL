package online.monarchlabs.sentinel.assistant.handlers;

import java.util.ArrayList;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.reliability.AssistantResult;

public class AssistantHandlerRegistry {
    private final List<AssistantHandler> handlers = new ArrayList<>();

    public void register(AssistantHandler handler) {
        handlers.add(handler);
    }

    public AssistantResult<?> dispatch(AssistantPlanningRequest request, AssistantPlanningResult planResult,
                                       online.monarchlabs.sentinel.assistant.context.AssistantConversationState state) {
        if (planResult == null || planResult.getPlan() == null) {
            return AssistantResult.success(planResult); // Default pass-through if no intent
        }

        AssistantIntent intent = planResult.getPlan().getIntent();
        AssistantIntent.Category category = intent.getCategory();

        for (AssistantHandler handler : handlers) {
            if (handler.getSupportedCategories().contains(category)) {
                return handler.handle(request, planResult, state);
            }
        }

        // Fallback
        return AssistantResult.success(planResult);
    }
}
