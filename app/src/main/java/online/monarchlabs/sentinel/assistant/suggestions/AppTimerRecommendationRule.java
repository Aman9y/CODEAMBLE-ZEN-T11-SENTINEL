package online.monarchlabs.sentinel.assistant.suggestions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import online.monarchlabs.sentinel.assistant.state.AssistantLiveStateRepository.LiveStateSnapshot;

public final class AppTimerRecommendationRule implements SuggestionRule {

    @Override
    public List<AssistantSuggestion> evaluate(SuggestionContext context) {
        List<AssistantSuggestion> suggestions = new ArrayList<>();
        if (context == null || context.snapshot == null || context.snapshot.appUsageMillis == null) {
            return suggestions;
        }

        LiveStateSnapshot snapshot = context.snapshot;
        SuggestionConfig config = context.config != null ? context.config : SuggestionConfig.defaultConfig();

        for (Map.Entry<String, Long> entry : snapshot.appUsageMillis.entrySet()) {
            String packageName = entry.getKey();
            long usage = entry.getValue();

            // Exclude background services, system launchers, and Sentinel from timer suggestions
            if (isIgnoredPackage(packageName)) {
                continue;
            }

            // Existing Policy Awareness:
            // 1. Exceeds usageThresholdMillis
            // 2. App is not already blocked
            // 3. No active timer policy covers it
            if (usage >= config.usageThresholdMillis
                    && !snapshot.blockedPackages.contains(packageName)
                    && !snapshot.activeTimerPackages.contains(packageName)) {

                String appName = snapshot.packageToAppName.get(packageName);
                if (appName == null || appName.isEmpty()) {
                    appName = snapshot.packageToAppName.get(packageName.replace('_', '.'));
                }
                if (appName == null || appName.isEmpty()) {
                    appName = snapshot.packageToAppName.get(packageName.replace('.', '_'));
                }
                if (appName == null || appName.isEmpty()) {
                    appName = formatPackageNameAsAppName(packageName);
                }

                // Compute dynamic limit: round to nearest 30 mins
                long recommendedLimit = Math.max(TimeUnit.MINUTES.toMillis(30),
                        Math.round((usage * config.recommendationRatio) / TimeUnit.MINUTES.toMillis(30)) * TimeUnit.MINUTES.toMillis(30));

                // Deterministic Suggestion ID: APP_TIMER:{packageName}
                String id = "APP_TIMER:" + packageName;
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("packageName", packageName);
                metadata.put("appName", appName);
                metadata.put("limitMillis", recommendedLimit);

                // Compute priority and score:
                // Usage > 3 hours -> HIGH priority, else MEDIUM priority
                AssistantSuggestion.Priority priority = usage >= TimeUnit.HOURS.toMillis(3)
                        ? AssistantSuggestion.Priority.HIGH
                        : AssistantSuggestion.Priority.MEDIUM;

                // Score is a function of total usage vs threshold (capped at 100)
                int score = (int) Math.min(100, (usage * 100) / config.usageThresholdMillis);

                long hours = TimeUnit.MILLISECONDS.toHours(usage);
                long minutes = TimeUnit.MILLISECONDS.toMinutes(usage) % 60;
                String usageText = hours > 0 ? (hours + "h " + minutes + "m") : (minutes + "m");

                long limitMinutes = TimeUnit.MILLISECONDS.toMinutes(recommendedLimit);
                String limitText = limitMinutes >= 60 ? ((limitMinutes / 60) + " hour" + (limitMinutes / 60 > 1 ? "s" : "")) : (limitMinutes + " minutes");

                suggestions.add(new AssistantSuggestion(
                        id,
                        AssistantSuggestion.Type.APP_TIMER_RECOMMENDATION,
                        priority,
                        score,
                        "Set limit on " + appName,
                        appName + " usage is high today (" + usageText + "). Would you like to set a " + limitText + " limit?",
                        AssistantSuggestion.ActionType.SET_TIMER,
                        "Set Limit",
                        "Dismiss",
                        metadata,
                        true,
                        context.currentTimeMillis + config.defaultCooldownMillis
                ));
            }
        }
        return suggestions;
    }

    private boolean isIgnoredPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return true;
        }
        String pkg = packageName.toLowerCase(java.util.Locale.US);
        return pkg.startsWith("online.monarchlabs.sentinel")
                || pkg.contains("launcher")
                || pkg.equals("com.android.systemui")
                || pkg.equals("com.android.settings")
                || pkg.equals("com.google.android.gms")
                || pkg.equals("com.google.android.googlequicksearchbox");
    }

    private String formatPackageNameAsAppName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "";
        }
        String cleaned = packageName;
        if (cleaned.startsWith("com.")) cleaned = cleaned.substring(4);
        if (cleaned.startsWith("com_")) cleaned = cleaned.substring(4);
        if (cleaned.startsWith("org.")) cleaned = cleaned.substring(4);
        if (cleaned.startsWith("org_")) cleaned = cleaned.substring(4);
        if (cleaned.startsWith("net.")) cleaned = cleaned.substring(4);
        if (cleaned.startsWith("net_")) cleaned = cleaned.substring(4);

        // Replace dots/underscores with space and capitalize
        String[] parts = cleaned.split("[._]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1))
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }
}
