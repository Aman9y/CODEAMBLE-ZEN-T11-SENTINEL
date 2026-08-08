package online.monarchlabs.sentinel.assistant.providers;

import online.monarchlabs.sentinel.assistant.models.UsageSummary;

public class UsageCardBuilder {
    public static UsageCard buildOverview(UsageSummary summary) {
        String screenTimeStr = formatDuration(summary.totalScreenTime);
        return new UsageCard("Today's Overview", screenTimeStr, summary.topApps,
                             summary.currentForegroundApp, summary.blockedApps.size());
    }

    public static UsageCard buildDetailed(UsageSummary summary) {
        String screenTimeStr = formatDuration(summary.totalScreenTime);
        return new UsageCard("Today's Usage", screenTimeStr, summary.topApps,
                             summary.currentForegroundApp, summary.blockedApps.size());
    }

    private static String formatDuration(long millis) {
        if (millis <= 0) return "0m";
        long minutes = (millis / (1000 * 60)) % 60;
        long hours = (millis / (1000 * 60 * 60));
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
