package online.monarchlabs.sentinel.assistant.audit;

import java.util.Map;

public interface AssistantAuditLogger {
    void log(AuditEvent event);

    default void log(String parentId, String childId, String assistantActionId, AuditStage stage,
                     String message, Map<String, Object> details) {
        log(new AuditEvent(parentId, childId, assistantActionId, stage, message, details));
    }
}
