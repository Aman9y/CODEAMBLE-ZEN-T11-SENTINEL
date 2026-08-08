package online.monarchlabs.sentinel.assistant.execution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import online.monarchlabs.sentinel.assistant.core.CommandSource;
import online.monarchlabs.sentinel.assistant.core.CommandType;
import online.monarchlabs.sentinel.assistant.parser.ExtractedSlots;
import online.monarchlabs.sentinel.assistant.planner.ActionPlan;
import online.monarchlabs.sentinel.assistant.planner.AssistantPlan;

public class AssistantCommandFactory {
    private static final long DEFAULT_EXPIRY_MILLIS = 24L * 60L * 60L * 1000L;

    private final IdempotencyKeyGenerator idempotencyKeyGenerator;

    public AssistantCommandFactory() {
        this(new IdempotencyKeyGenerator());
    }

    public AssistantCommandFactory(IdempotencyKeyGenerator idempotencyKeyGenerator) {
        this.idempotencyKeyGenerator = idempotencyKeyGenerator;
    }

    public SentinelCommand create(AssistantPlan plan, CommandSource source) {
        return create(plan, source, null);
    }

    public SentinelCommand create(AssistantPlan plan, CommandSource source, Map<String, String> appNameToPackage) {
        String assistantActionId = UUID.randomUUID().toString();
        String idempotencyKey = idempotencyKeyGenerator.generate(plan);
        List<String> packages = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<String> appTargets = plan.getSlots() != null ? plan.getSlots().getAppTargets() : new ArrayList<>();
        for (ActionPlan action : plan.getActions()) {
            for (String target : action.getTargets()) {
                if (target == null) continue;
                String resolvedTarget = target;
                if (appNameToPackage != null) {
                    String pkg = appNameToPackage.get(target.toLowerCase(Locale.US));
                    if (pkg != null) {
                        resolvedTarget = pkg;
                    }
                }
                if (resolvedTarget.contains(".") || appTargets.contains(target)) {
                    packages.add(resolvedTarget);
                } else {
                    categories.add(resolvedTarget);
                }
            }
        }
        Map<String, Object> payload = buildPayload(plan);
        return new SentinelCommand(assistantActionId, idempotencyKey, plan.getParentId(), plan.getChildId(),
                commandTypeFor(plan), source, packages, categories, payload,
                System.currentTimeMillis() + DEFAULT_EXPIRY_MILLIS);
    }

    private Map<String, Object> buildPayload(AssistantPlan plan) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("planId", plan.getPlanId());
        payload.put("intent", plan.getIntent().name());
        payload.put("summary", plan.getSummary());
        payload.put("riskLevel", plan.getRiskLevel().name());
        ExtractedSlots slots = plan.getSlots();
        if (slots != null) {
            payload.put("appName", slots.getAppName());
            payload.put("categoryName", slots.getCategoryName());
            payload.put("durationMillis", slots.getDurationMillis());
            payload.put("repeatRule", slots.getRepeatRule());
            payload.put("exceptions", slots.getExceptions());
            payload.put("timeRange", slots.getTimeRange() == null ? null : slots.getTimeRange().getDisplayText());
        }
        return payload;
    }

    private CommandType commandTypeFor(AssistantPlan plan) {
        switch (plan.getIntent()) {
            case BLOCK_APP_NOW:
            case BLOCK_APP_TEMPORARY:
            case SCHEDULE_BLOCK_APP:
                return CommandType.ASSISTANT_BLOCK_APP;
            case UNBLOCK_APP:
                return CommandType.ASSISTANT_UNBLOCK_APP;
            case UNBLOCK_ALL_APPS:
                return CommandType.ASSISTANT_UNBLOCK_ALL_APPS;
            case BLOCK_CATEGORY_NOW:
            case SCHEDULE_BLOCK_CATEGORY:
                return CommandType.ASSISTANT_BLOCK_CATEGORY;
            case SET_APP_TIMER:
                return CommandType.ASSISTANT_SET_APP_TIMER;
            case REMOVE_APP_TIMER:
                return CommandType.ASSISTANT_REMOVE_APP_TIMER;
            case PAUSE_RESTRICTIONS:
                return CommandType.ASSISTANT_PAUSE_RESTRICTIONS;
            case RESUME_RESTRICTIONS:
                return CommandType.ASSISTANT_RESUME_RESTRICTIONS;
            case APPLY_MODE:
            case APPLY_ROUTINE:
                return CommandType.ASSISTANT_APPLY_TEMPLATE;
            case UNDO_LAST_ACTION:
            default:
                return CommandType.ASSISTANT_ROLLBACK;
        }
    }
}
