package online.monarchlabs.sentinel.assistant.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.core.RiskLevel;
import online.monarchlabs.sentinel.assistant.parser.ExtractedSlots;

public class AssistantPlan {
    private final String planId;
    private final AssistantIntent intent;
    private final String parentId;
    private final String childId;
    private final String childName;
    private final List<ActionPlan> actions;
    private final ExtractedSlots slots;
    private final List<String> warnings;
    private final RiskLevel riskLevel;
    private final String summary;
    private final boolean requiresConfirmation;

    public AssistantPlan(AssistantIntent intent, String parentId, String childId, String childName,
                         List<ActionPlan> actions, ExtractedSlots slots, List<String> warnings,
                         RiskLevel riskLevel, String summary, boolean requiresConfirmation) {
        this.planId = UUID.randomUUID().toString();
        this.intent = intent;
        this.parentId = parentId;
        this.childId = childId;
        this.childName = childName;
        this.actions = actions == null ? new ArrayList<>() : new ArrayList<>(actions);
        this.slots = slots;
        this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
        this.riskLevel = riskLevel;
        this.summary = summary;
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getPlanId() {
        return planId;
    }

    public AssistantIntent getIntent() {
        return intent;
    }

    public String getParentId() {
        return parentId;
    }

    public String getChildId() {
        return childId;
    }

    public String getChildName() {
        return childName;
    }

    public List<ActionPlan> getActions() {
        return Collections.unmodifiableList(actions);
    }

    public ExtractedSlots getSlots() {
        return slots;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }
}
