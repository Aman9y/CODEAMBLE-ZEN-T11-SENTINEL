package online.monarchlabs.sentinel.assistant.execution;

import org.junit.Test;

import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.core.CommandSource;
import online.monarchlabs.sentinel.assistant.planner.LocalRuleBasedCommandPlanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class IdempotencyKeyGeneratorTest {
    private final LocalRuleBasedCommandPlanner planner = new LocalRuleBasedCommandPlanner();
    private final IdempotencyKeyGenerator generator = new IdempotencyKeyGenerator();

    @Test
    public void samePlanContentGetsSameKey() {
        AssistantPlanningResult first = plan("Block YouTube for 2 hours");
        AssistantPlanningResult second = plan("Block yt for 2 hours");

        assertEquals(generator.generate(first.getPlan()), generator.generate(second.getPlan()));
    }

    @Test
    public void changedDurationGetsDifferentKey() {
        AssistantPlanningResult first = plan("Block YouTube for 2 hours");
        AssistantPlanningResult second = plan("Block YouTube for 30 minutes");

        assertNotEquals(generator.generate(first.getPlan()), generator.generate(second.getPlan()));
    }

    private AssistantPlanningResult plan(String input) {
        return planner.plan(new AssistantPlanningRequest("parent1", "child1", "Rahul", input, CommandSource.PARENT_TEXT));
    }
}
