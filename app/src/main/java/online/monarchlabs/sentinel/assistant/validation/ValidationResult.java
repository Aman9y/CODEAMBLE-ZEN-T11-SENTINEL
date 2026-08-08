package online.monarchlabs.sentinel.assistant.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;

public class ValidationResult {
    public enum Status {
        VALID,
        RECOVERABLE_ERROR,
        UNRECOVERABLE_ERROR,
        CLARIFICATION_REQUIRED,
        WARNING
    }

    private final Status status;
    private final AssistantErrorCode errorCode;
    private final String message;
    private final List<String> warnings;

    private ValidationResult(Status status, AssistantErrorCode errorCode, String message, List<String> warnings) {
        this.status = status;
        this.errorCode = errorCode == null ? AssistantErrorCode.NONE : errorCode;
        this.message = message;
        this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
    }

    public static ValidationResult valid() {
        return new ValidationResult(Status.VALID, AssistantErrorCode.NONE, "Plan is valid.", null);
    }

    public static ValidationResult clarification(AssistantErrorCode errorCode, String message) {
        return new ValidationResult(Status.CLARIFICATION_REQUIRED, errorCode, message, null);
    }

    public static ValidationResult recoverable(AssistantErrorCode errorCode, String message) {
        return new ValidationResult(Status.RECOVERABLE_ERROR, errorCode, message, null);
    }

    public static ValidationResult warning(String message, List<String> warnings) {
        return new ValidationResult(Status.WARNING, AssistantErrorCode.NONE, message, warnings);
    }

    public Status getStatus() {
        return status;
    }

    public AssistantErrorCode getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public boolean canContinue() {
        return status == Status.VALID || status == Status.WARNING;
    }
}
