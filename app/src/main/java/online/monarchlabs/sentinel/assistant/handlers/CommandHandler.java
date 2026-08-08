package online.monarchlabs.sentinel.assistant.handlers;

import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.planner.AssistantPlan;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.reliability.AssistantResult;

public class CommandHandler implements AssistantHandler {

    @Override
    public List<AssistantIntent.Category> getSupportedCategories() {
        return Collections.singletonList(AssistantIntent.Category.ACTION);
    }

    @Override
    public AssistantResult<?> handle(AssistantPlanningRequest request, AssistantPlanningResult planResult,
                                     online.monarchlabs.sentinel.assistant.context.AssistantConversationState state) {
        return AssistantResult.success(new CommandResult(planResult.getPlan()));
    }

    public static class CommandResult {
        public final AssistantPlan plan;

        public CommandResult(AssistantPlan plan) {
            this.plan = plan;
        }
    }
}
