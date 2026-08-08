package online.monarchlabs.sentinel.assistant.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import online.monarchlabs.sentinel.assistant.core.CommandSource;
import online.monarchlabs.sentinel.assistant.core.CommandType;

public class SentinelCommand {
    private final String commandId;
    private final String assistantActionId;
    private final String idempotencyKey;
    private final String parentId;
    private final String childId;
    private final CommandType commandType;
    private final CommandSource source;
    private final List<String> targetPackages;
    private final List<String> targetCategories;
    private final Map<String, Object> payload;
    private final long createdAtMillis;
    private final long expiresAtMillis;

    public SentinelCommand(String assistantActionId, String idempotencyKey, String parentId, String childId,
                           CommandType commandType, CommandSource source, List<String> targetPackages,
                           List<String> targetCategories, Map<String, Object> payload, long expiresAtMillis) {
        this.commandId = UUID.randomUUID().toString();
        this.assistantActionId = assistantActionId;
        this.idempotencyKey = idempotencyKey;
        this.parentId = parentId;
        this.childId = childId;
        this.commandType = commandType;
        this.source = source;
        this.targetPackages = targetPackages == null ? new ArrayList<>() : new ArrayList<>(targetPackages);
        this.targetCategories = targetCategories == null ? new ArrayList<>() : new ArrayList<>(targetCategories);
        this.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
        this.createdAtMillis = System.currentTimeMillis();
        this.expiresAtMillis = expiresAtMillis;
    }

    public String getCommandId() {
        return commandId;
    }

    public String getAssistantActionId() {
        return assistantActionId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getParentId() {
        return parentId;
    }

    public String getChildId() {
        return childId;
    }

    public CommandType getCommandType() {
        return commandType;
    }

    public CommandSource getSource() {
        return source;
    }

    public List<String> getTargetPackages() {
        return Collections.unmodifiableList(targetPackages);
    }

    public List<String> getTargetCategories() {
        return Collections.unmodifiableList(targetCategories);
    }

    public Map<String, Object> getPayload() {
        return Collections.unmodifiableMap(payload);
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public Map<String, Object> toFirebaseMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("commandId", commandId);
        map.put("assistantActionId", assistantActionId);
        map.put("idempotencyKey", idempotencyKey);
        map.put("parentId", parentId);
        map.put("childId", childId);
        map.put("commandType", commandType.name());
        map.put("source", source.name());
        map.put("targetPackages", targetPackages);
        map.put("targetCategories", targetCategories);
        map.put("payload", payload);
        map.put("createdAtMillis", createdAtMillis);
        map.put("expiresAtMillis", expiresAtMillis);
        return map;
    }
}
