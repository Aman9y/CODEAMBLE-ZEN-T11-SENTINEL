package online.monarchlabs.sentinel.assistant.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository;

public class LimitsService {
    private final AssistantLiveStateRepository.LiveStateSnapshot snapshot;

    public LimitsService(AssistantLiveStateRepository.LiveStateSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public List<String> getActiveTimers() {
        if (snapshot != null && snapshot.activeTimerPackages != null) {
            return new ArrayList<>(snapshot.activeTimerPackages);
        }
        return Collections.emptyList();
    }

    public List<String> getActiveLimits() {
        // App limits aren't explicitly loaded in the snapshot yet,
        // returning timers as a proxy for limits initially.
        return getActiveTimers();
    }
}
