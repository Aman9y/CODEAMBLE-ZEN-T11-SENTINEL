package online.monarchlabs.sentinel.assistant.handlers;

import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.models.UsageSummary;
import java.util.function.Supplier;
import online.monarchlabs.sentinel.assistant.reliability.AssistantResult;
import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository;
import online.monarchlabs.sentinel.assistant.services.UsageSummaryService;

public class KnowledgeHandler implements AssistantHandler {
    private final Supplier<AssistantLiveStateRepository.LiveStateSnapshot> snapshotSupplier;

    public KnowledgeHandler(Supplier<AssistantLiveStateRepository.LiveStateSnapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override
    public List<AssistantIntent.Category> getSupportedCategories() {
        return Collections.singletonList(AssistantIntent.Category.QUERY);
    }

    @Override
    public AssistantResult<?> handle(AssistantPlanningRequest request, AssistantPlanningResult planResult,
                                     online.monarchlabs.sentinel.assistant.context.AssistantConversationState state) {
        UsageSummaryService usageService = new UsageSummaryService(snapshotSupplier.get());
        UsageSummary summary = usageService.getTodaySummary();
        return AssistantResult.success(new KnowledgeResult(planResult.getPlan().getIntent(), summary));
    }

    public static class KnowledgeResult {
        public final AssistantIntent intent;
        public final UsageSummary usageSummary;

        public KnowledgeResult(AssistantIntent intent, UsageSummary usageSummary) {
            this.intent = intent;
            this.usageSummary = usageSummary;
        }
    }
}
