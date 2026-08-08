package online.monarchlabs.sentinel.assistant.audit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InMemoryAssistantAuditLoggerTest {
    @Test
    public void storesAuditEventsInOrder() {
        InMemoryAssistantAuditLogger logger = new InMemoryAssistantAuditLogger();

        logger.log("parent1", "child1", "action1", AuditStage.INPUT_RECEIVED, "Input received", null);
        logger.log("parent1", "child1", "action1", AuditStage.PLAN_CREATED, "Plan created", null);

        assertEquals(2, logger.getEvents().size());
        assertEquals(AuditStage.INPUT_RECEIVED, logger.getEvents().get(0).getStage());
        assertEquals(AuditStage.PLAN_CREATED, logger.getEvents().get(1).getStage());
    }
}
