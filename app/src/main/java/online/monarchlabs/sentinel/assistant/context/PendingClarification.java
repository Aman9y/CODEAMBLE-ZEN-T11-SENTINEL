package online.monarchlabs.sentinel.assistant.context;

import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;

public class PendingClarification {
    private final AssistantErrorCode errorCode;
    private final String originalInput;
    private final long expiresAtMillis;

    public PendingClarification(AssistantErrorCode errorCode, String originalInput, long expiresAtMillis) {
        this.errorCode = errorCode;
        this.originalInput = originalInput;
        this.expiresAtMillis = expiresAtMillis;
    }

    public AssistantErrorCode getErrorCode() {
        return errorCode;
    }

    public String getOriginalInput() {
        return originalInput;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis > expiresAtMillis;
    }
}
