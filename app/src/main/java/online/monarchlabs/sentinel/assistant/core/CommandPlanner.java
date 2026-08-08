package online.monarchlabs.sentinel.assistant.core;

public interface CommandPlanner {
    AssistantPlanningResult plan(AssistantPlanningRequest request);
}
