package online.monarchlabs.sentinel.assistant.providers;

import java.util.List;

public class UsageCard extends AssistantCard {
    public final String title;
    public final String totalScreenTime;
    public final List<String> topApps;
    public final String currentApp;
    public final int blockedAppsCount;

    public UsageCard(String title, String totalScreenTime, List<String> topApps,
                     String currentApp, int blockedAppsCount) {
        super("USAGE_CARD");
        this.title = title;
        this.totalScreenTime = totalScreenTime;
        this.topApps = topApps;
        this.currentApp = currentApp;
        this.blockedAppsCount = blockedAppsCount;
    }
}
