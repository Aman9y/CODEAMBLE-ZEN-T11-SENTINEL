package online.monarchlabs.sentinel.assistant.audit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AuditEvent {
    private final String eventId;
    private final String parentId;
    private final String childId;
    private final String assistantActionId;
    private final AuditStage stage;
    private final String message;
    private final Map<String, Object> details;
    private final long timestampMillis;

    public AuditEvent(String parentId, String childId, String assistantActionId, AuditStage stage,
                      String message, Map<String, Object> details) {
        this.eventId = UUID.randomUUID().toString();
        this.parentId = parentId;
        this.childId = childId;
        this.assistantActionId = assistantActionId;
        this.stage = stage;
        this.message = message;
        this.details = details == null ? new HashMap<>() : new HashMap<>(details);
        this.timestampMillis = System.currentTimeMillis();
    }

    public String getEventId() {
        return eventId;
    }

    public String getParentId() {
        return parentId;
    }

    public String getChildId() {
        return childId;
    }

    public String getAssistantActionId() {
        return assistantActionId;
    }

    public AuditStage getStage() {
        return stage;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getDetails() {
        return Collections.unmodifiableMap(details);
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public Map<String, Object> toFirebaseMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("eventId", eventId);
        map.put("parentId", parentId);
        map.put("childId", childId);
        map.put("assistantActionId", assistantActionId);
        map.put("stage", stage.name());
        map.put("message", message);
        map.put("details", details);
        map.put("timestampMillis", timestampMillis);
        return map;
    }
}
