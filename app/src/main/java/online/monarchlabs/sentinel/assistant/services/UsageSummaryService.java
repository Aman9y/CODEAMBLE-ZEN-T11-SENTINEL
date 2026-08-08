package online.monarchlabs.sentinel.assistant.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import online.monarchlabs.sentinel.assistant.models.UsageSummary;
import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository;

public class UsageSummaryService {
    private final AssistantLiveStateRepository.LiveStateSnapshot snapshot;

    public UsageSummaryService(AssistantLiveStateRepository.LiveStateSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public UsageSummary getTodaySummary() {
        return buildSummary();
    }

    public long getTotalUsage() {
        long total = 0;
        if (snapshot != null && snapshot.appUsageMillis != null) {
            for (Long usage : snapshot.appUsageMillis.values()) {
                total += usage;
            }
        }
        return total;
    }

    public List<String> getTopApps(int limit) {
        if (snapshot == null || snapshot.appUsageMillis == null || snapshot.appUsageMillis.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(snapshot.appUsageMillis.entrySet());
        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            top.add(entries.get(i).getKey());
        }
        return top;
    }

    public String getMostUsedApp() {
        List<String> top = getTopApps(1);
        return top.isEmpty() ? null : top.get(0);
    }

    public long getUsageForPackage(String packageName) {
        if (snapshot != null && snapshot.appUsageMillis != null && packageName != null) {
            Long usage = snapshot.appUsageMillis.get(packageName.toLowerCase(java.util.Locale.US));
            return usage != null ? usage : 0;
        }
        return 0;
    }

    public UsageSummary getOverview() {
        return buildSummary();
    }

    private UsageSummary buildSummary() {
        if (snapshot == null) {
            return new UsageSummary(0, null, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    null, Collections.emptyMap(), 0, System.currentTimeMillis());
        }
        return new UsageSummary(
                getTotalUsage(),
                snapshot.foregroundApp,
                new ArrayList<>(snapshot.blockedPackages),
                new ArrayList<>(), // TODO: activeLimits from snapshot (currently only activeTimers exist)
                new ArrayList<>(snapshot.activeTimerPackages),
                getTopApps(5),
                getMostUsedApp(),
                snapshot.appUsageMillis,
                snapshot.refreshedAtMillis,
                System.currentTimeMillis()
        );
    }
}
