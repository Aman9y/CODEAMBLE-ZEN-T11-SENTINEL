package online.monarchlabs.sentinel.assistant.services;

import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository;

public class DeviceStateService {
    private final AssistantLiveStateRepository.LiveStateSnapshot snapshot;

    public DeviceStateService(AssistantLiveStateRepository.LiveStateSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public String getCurrentForegroundApp() {
        if (snapshot != null) {
            return snapshot.foregroundApp;
        }
        return null;
    }

    public boolean isOnline() {
        // Fallback: Check if we have recent data within the last 15 minutes
        if (snapshot != null) {
            return (System.currentTimeMillis() - snapshot.refreshedAtMillis) < 15 * 60 * 1000L;
        }
        return false;
    }

    public long getLastSync() {
        if (snapshot != null) {
            return snapshot.refreshedAtMillis;
        }
        return 0;
    }
}
