package online.monarchlabs.sentinel.assistant.reliability;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;

public final class AssistantError implements Serializable {
    public final AssistantErrorCategory category;
    public final AssistantErrorCode code;
    public final String message;
    public final String technicalReason;
    public final boolean recoverable;
    public final boolean retryable;
    public final List<String> suggestedActions;
    public final Map<String, Object> metadata;

    public AssistantError(AssistantErrorCategory category, AssistantErrorCode code, String message,
                          String technicalReason, boolean recoverable, boolean retryable,
                          List<String> suggestedActions, Map<String, Object> metadata) {
        this.category = category;
        this.code = code;
        this.message = message;
        this.technicalReason = technicalReason;
        this.recoverable = recoverable;
        this.retryable = retryable;
        this.suggestedActions = suggestedActions;
        this.metadata = metadata;
    }
}
