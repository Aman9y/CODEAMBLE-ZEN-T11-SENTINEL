package online.monarchlabs.sentinel.assistant.providers;

import java.util.ArrayList;
import java.util.List;

import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository;
import online.monarchlabs.sentinel.assistant.services.UsageSummaryService;
import online.monarchlabs.sentinel.assistant.models.UsageSummary;

public class AssistantHomeProvider {

    public List<AssistantCard> buildHomeCards(AssistantLiveStateRepository.LiveStateSnapshot snapshot) {
        List<AssistantCard> cards = new ArrayList<>();
        if (snapshot == null) {
            return cards;
        }

        UsageSummaryService summaryService = new UsageSummaryService(snapshot);
        UsageSummary overview = summaryService.getOverview();

        cards.add(UsageCardBuilder.buildOverview(overview));

        return cards;
    }
}
