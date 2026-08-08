package online.monarchlabs.sentinel.assistant.handlers;

import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.reliability.AssistantResult;

public class SystemHandler implements AssistantHandler {

    @Override
    public List<AssistantIntent.Category> getSupportedCategories() {
        return Collections.singletonList(AssistantIntent.Category.SYSTEM);
    }

    @Override
    public AssistantResult<?> handle(AssistantPlanningRequest request, AssistantPlanningResult planResult,
                                     online.monarchlabs.sentinel.assistant.context.AssistantConversationState state) {
        return AssistantResult.success(new SystemResult(planResult));
    }

    public static class SystemResult {
        public final AssistantPlanningResult rawPlanResult;

        public SystemResult(AssistantPlanningResult rawPlanResult) {
            this.rawPlanResult = rawPlanResult;
        }
    }
}
