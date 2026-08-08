package online.monarchlabs.sentinel.assistant.core;

public enum ChildAckStatus {
    RECEIVED,
    APPLIED,
    PARTIALLY_APPLIED,
    FAILED,
    PENDING_PERMISSION,
    DUPLICATE_IGNORED,
    EXPIRED
}
