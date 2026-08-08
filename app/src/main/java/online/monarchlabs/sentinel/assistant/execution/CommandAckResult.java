package online.monarchlabs.sentinel.assistant.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.ChildAckStatus;

public class CommandAckResult {
    private final String commandId;
    private final ChildAckStatus status;
    private final String reason;
    private final String message;
    private final long timestampMillis;
    private final String permissionState;
    private final List<String> appliedRuleIds;

    public CommandAckResult(String commandId, ChildAckStatus status, String reason, String message,
                            long timestampMillis, String permissionState, List<String> appliedRuleIds) {
        this.commandId = commandId;
        this.status = status;
        this.reason = reason;
        this.message = message;
        this.timestampMillis = timestampMillis;
        this.permissionState = permissionState;
        this.appliedRuleIds = appliedRuleIds == null ? new ArrayList<>() : new ArrayList<>(appliedRuleIds);
    }

    public String getCommandId() {
        return commandId;
    }

    public ChildAckStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public String getPermissionState() {
        return permissionState;
    }

    public List<String> getAppliedRuleIds() {
        return Collections.unmodifiableList(appliedRuleIds);
    }
}
