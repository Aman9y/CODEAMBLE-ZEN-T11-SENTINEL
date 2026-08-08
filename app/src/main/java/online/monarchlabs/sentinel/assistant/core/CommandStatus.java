package online.monarchlabs.sentinel.assistant.core;

public enum CommandStatus {
    DRAFT,
    NEEDS_CLARIFICATION,
    WAITING_CONFIRMATION,
    PENDING_SYNC,
    SYNCED,
    RECEIVED,
    APPLIED,
    PENDING_CHILD_ONLINE,
    PENDING_PERMISSION,
    PARTIALLY_APPLIED,
    FAILED,
    CANCELLED,
    ROLLED_BACK
}
