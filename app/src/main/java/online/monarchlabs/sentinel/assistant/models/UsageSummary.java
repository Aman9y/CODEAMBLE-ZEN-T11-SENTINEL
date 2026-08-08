package online.monarchlabs.sentinel.assistant.models;

import java.util.List;
import java.util.Map;

public class UsageSummary {
    public final long totalScreenTime;
    public final String currentForegroundApp;
    public final List<String> blockedApps;
    public final List<String> activeLimits;
    public final List<String> activeTimers;
    public final List<String> topApps;
    public final String mostUsedApp;
    public final Map<String, Long> usageByPackage;
    public final long lastUpdated;
    public final long timestamp;

    public UsageSummary(long totalScreenTime, String currentForegroundApp, List<String> blockedApps,
                        List<String> activeLimits, List<String> activeTimers, List<String> topApps,
                        String mostUsedApp, Map<String, Long> usageByPackage, long lastUpdated, long timestamp) {
        this.totalScreenTime = totalScreenTime;
        this.currentForegroundApp = currentForegroundApp;
        this.blockedApps = blockedApps;
        this.activeLimits = activeLimits;
        this.activeTimers = activeTimers;
        this.topApps = topApps;
        this.mostUsedApp = mostUsedApp;
        this.usageByPackage = usageByPackage;
        this.lastUpdated = lastUpdated;
        this.timestamp = timestamp;
    }
}
