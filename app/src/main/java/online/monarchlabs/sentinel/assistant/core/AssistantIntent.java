package online.monarchlabs.sentinel.assistant.core;

public enum AssistantIntent {
    BLOCK_APP_NOW,
    BLOCK_APP_TEMPORARY,
    SCHEDULE_BLOCK_APP,
    SCHEDULE_BLOCK_CATEGORY,
    BLOCK_CATEGORY_NOW,
    UNBLOCK_APP,
    UNBLOCK_ALL_APPS,
    SET_APP_TIMER,
    REMOVE_APP_TIMER,
    PAUSE_RESTRICTIONS,
    RESUME_RESTRICTIONS,
    APPLY_ROUTINE,
    APPLY_MODE,
    QUERY_USAGE,
    QUERY_ACTIVE_RULES,
    EXPLAIN_APP_BLOCK,
    UNDO_LAST_ACTION,
    GREETING,
    HELP,
    AFFIRM,
    DENY,
    THANKS,
    UNKNOWN;

    public enum Category {
        ACTION,
        QUERY,
        SYSTEM
    }

    public Category getCategory() {
        switch (this) {
            case BLOCK_APP_NOW:
            case BLOCK_APP_TEMPORARY:
            case SCHEDULE_BLOCK_APP:
            case SCHEDULE_BLOCK_CATEGORY:
            case BLOCK_CATEGORY_NOW:
            case UNBLOCK_APP:
            case UNBLOCK_ALL_APPS:
            case SET_APP_TIMER:
            case REMOVE_APP_TIMER:
            case PAUSE_RESTRICTIONS:
            case RESUME_RESTRICTIONS:
            case APPLY_ROUTINE:
            case APPLY_MODE:
                return Category.ACTION;

            case QUERY_USAGE:
            case QUERY_ACTIVE_RULES:
                return Category.QUERY;

            case EXPLAIN_APP_BLOCK:
            case UNDO_LAST_ACTION:
            case GREETING:
            case HELP:
            case AFFIRM:
            case DENY:
            case THANKS:
            case UNKNOWN:
            default:
                return Category.SYSTEM;
        }
    }
}
