package online.monarchlabs.sentinel.assistant.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantActionType;

public class ActionPlan {
    private final AssistantActionType actionType;
    private final List<String> targets = new ArrayList<>();

    public ActionPlan(AssistantActionType actionType) {
        this.actionType = actionType;
    }

    public AssistantActionType getActionType() {
        return actionType;
    }

    public void addTarget(String target) {
        if (target != null && !target.trim().isEmpty()) {
            targets.add(target.trim());
        }
    }

    public List<String> getTargets() {
        return Collections.unmodifiableList(targets);
    }
}
