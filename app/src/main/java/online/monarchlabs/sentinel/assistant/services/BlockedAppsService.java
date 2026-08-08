package online.monarchlabs.sentinel.assistant.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository;

public class BlockedAppsService {
    private final AssistantLiveStateRepository.LiveStateSnapshot snapshot;

    public BlockedAppsService(AssistantLiveStateRepository.LiveStateSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public List<String> getBlockedApps() {
        if (snapshot != null && snapshot.blockedPackages != null) {
            return new ArrayList<>(snapshot.blockedPackages);
        }
        return Collections.emptyList();
    }

    public int getBlockedAppsCount() {
        return getBlockedApps().size();
    }
}
