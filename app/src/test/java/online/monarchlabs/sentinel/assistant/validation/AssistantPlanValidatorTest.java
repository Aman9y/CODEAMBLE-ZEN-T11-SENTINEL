package online.monarchlabs.sentinel.assistant.validation;

import org.junit.Test;

import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.core.CommandSource;
import online.monarchlabs.sentinel.assistant.planner.LocalRuleBasedCommandPlanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AssistantPlanValidatorTest {
    private final LocalRuleBasedCommandPlanner planner = new LocalRuleBasedCommandPlanner();
    private final AssistantPlanValidator validator = new AssistantPlanValidator();

    @Test
    public void validControlPlanCanContinue() {
        AssistantPlanningResult result = planner.plan(
                new AssistantPlanningRequest("parent1", "child1", "Rahul", "Block YouTube", CommandSource.PARENT_TEXT));

        ValidationResult validation = validator.validate(result.getPlan());

        assertEquals(ValidationResult.Status.VALID, validation.getStatus());
        assertTrue(validation.canContinue());
    }

    @Test
    public void missingChildRequiresClarification() {
        AssistantPlanningResult result = planner.plan(
                new AssistantPlanningRequest("parent1", "", "", "Block YouTube", CommandSource.PARENT_TEXT));

        ValidationResult validation = validator.validate(result.getPlan());

        assertEquals(ValidationResult.Status.CLARIFICATION_REQUIRED, validation.getStatus());
    }
}
