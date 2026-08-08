package online.monarchlabs.sentinel.assistant.validation;

import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;
import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.parser.ExtractedSlots;
import online.monarchlabs.sentinel.assistant.planner.AssistantPlan;

public class AssistantPlanValidator {
    public ValidationResult validate(AssistantPlan plan) {
        if (plan == null) {
            return ValidationResult.recoverable(AssistantErrorCode.VALIDATION_FAILED, "No assistant plan was created.");
        }
        if (isBlank(plan.getParentId())) {
            return ValidationResult.recoverable(AssistantErrorCode.VALIDATION_FAILED, "Parent sign-in is required.");
        }
        if (requiresChild(plan.getIntent()) && isBlank(plan.getChildId())) {
            return ValidationResult.clarification(AssistantErrorCode.MISSING_CHILD,
                    "Choose a child before confirming this command.");
        }
        ExtractedSlots slots = plan.getSlots();
        if (slots == null) {
            return ValidationResult.recoverable(AssistantErrorCode.VALIDATION_FAILED, "Command details are missing.");
        }
        if (requiresApp(plan.getIntent()) && isBlank(slots.getAppName())) {
            return ValidationResult.clarification(AssistantErrorCode.MISSING_APP,
                    "Choose the app this command should affect.");
        }
        if (requiresCategory(plan.getIntent()) && isBlank(slots.getCategoryName())) {
            return ValidationResult.clarification(AssistantErrorCode.MISSING_CATEGORY,
                    "Choose the category this command should affect.");
        }
        if (requiresDuration(plan.getIntent()) && (slots.getDurationMillis() == null || slots.getDurationMillis() <= 0)) {
            return ValidationResult.clarification(AssistantErrorCode.MISSING_DURATION,
                    "Choose how long this command should last.");
        }
        if (requiresTimeRange(plan.getIntent()) && slots.getTimeRange() == null) {
            return ValidationResult.clarification(AssistantErrorCode.MISSING_TIME_RANGE,
                    "Choose a start and end time for this schedule.");
        }
        return ValidationResult.valid();
    }

    private boolean requiresChild(AssistantIntent intent) {
        return intent != AssistantIntent.UNKNOWN;
    }

    private boolean requiresApp(AssistantIntent intent) {
        return intent == AssistantIntent.BLOCK_APP_NOW
                || intent == AssistantIntent.BLOCK_APP_TEMPORARY
                || intent == AssistantIntent.SCHEDULE_BLOCK_APP
                || intent == AssistantIntent.UNBLOCK_APP
                || intent == AssistantIntent.REMOVE_APP_TIMER
                || intent == AssistantIntent.SET_APP_TIMER
                || intent == AssistantIntent.EXPLAIN_APP_BLOCK;
    }

    private boolean requiresCategory(AssistantIntent intent) {
        return intent == AssistantIntent.BLOCK_CATEGORY_NOW
                || intent == AssistantIntent.SCHEDULE_BLOCK_CATEGORY;
    }

    private boolean requiresDuration(AssistantIntent intent) {
        return intent == AssistantIntent.BLOCK_APP_TEMPORARY
                || intent == AssistantIntent.PAUSE_RESTRICTIONS
                || intent == AssistantIntent.SET_APP_TIMER;
    }

    private boolean requiresTimeRange(AssistantIntent intent) {
        return intent == AssistantIntent.SCHEDULE_BLOCK_APP
                || intent == AssistantIntent.SCHEDULE_BLOCK_CATEGORY;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
