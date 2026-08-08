package online.monarchlabs.sentinel.assistant.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.planner.ActionPlan;
import online.monarchlabs.sentinel.assistant.planner.AssistantPlan;

public class IdempotencyKeyGenerator {
    public String generate(AssistantPlan plan) {
        if (plan == null) {
            return hash("null-plan");
        }
        List<String> parts = new ArrayList<>();
        parts.add(nullSafe(plan.getParentId()));
        parts.add(nullSafe(plan.getChildId()));
        parts.add(plan.getIntent().name());
        for (ActionPlan action : plan.getActions()) {
            parts.add(action.getActionType().name());
            List<String> targets = new ArrayList<>(action.getTargets());
            Collections.sort(targets);
            parts.add(targets.toString());
        }
        if (plan.getSlots() != null) {
            parts.add(nullSafe(plan.getSlots().getCategoryName()));
            parts.add(nullSafe(plan.getSlots().getAppName()));
            parts.add(String.valueOf(plan.getSlots().getDurationMillis()));
            parts.add(plan.getSlots().getTimeRange() == null ? "" : plan.getSlots().getTimeRange().getDisplayText());
            parts.add(nullSafe(plan.getSlots().getRepeatRule()));
            parts.add(plan.getSlots().getExceptions().toString());
        }
        return hash(String.join("|", parts));
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
