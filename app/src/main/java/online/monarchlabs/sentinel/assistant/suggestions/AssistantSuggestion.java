package online.monarchlabs.sentinel.assistant.suggestions;

import java.io.Serializable;
import java.util.Map;

public final class AssistantSuggestion implements Serializable {
    public enum Priority { HIGH, MEDIUM, LOW }

    public enum Type {
        APP_TIMER_RECOMMENDATION,
        EXCESSIVE_SCREEN_TIME,
        BEDTIME_RECOMMENDATION,
        DOWNTIME_REMINDER,
        NEW_APP_INSTALLED,
        SUSPICIOUS_USAGE_PATTERN,
        HOMEWORK_MODE_REMINDER
    }

    public enum ActionType {
        SET_TIMER,
        BLOCK_APP,
        ENABLE_DOWNTIME,
        OPEN_SETTINGS,
        VIEW_REPORT
    }

    public final String id;
    public final Type type;
    public final Priority priority;
    public final int score;
    public final String title;
    public final String description;
    public final ActionType actionType;
    public final String actionLabel;
    public final String dismissLabel;
    public final Map<String, Object> metadata;
    public final boolean dismissible;
    public final long expiresAt;

    public AssistantSuggestion(String id, Type type, Priority priority, int score, String title, String description,
                               ActionType actionType, String actionLabel, String dismissLabel,
                               Map<String, Object> metadata, boolean dismissible, long expiresAt) {
        this.id = id;
        this.type = type;
        this.priority = priority;
        this.score = score;
        this.title = title;
        this.description = description;
        this.actionType = actionType;
        this.actionLabel = actionLabel;
        this.dismissLabel = dismissLabel;
        this.metadata = metadata;
        this.dismissible = dismissible;
        this.expiresAt = expiresAt;
    }
}
