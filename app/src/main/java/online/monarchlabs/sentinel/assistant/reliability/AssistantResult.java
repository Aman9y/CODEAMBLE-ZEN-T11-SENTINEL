package online.monarchlabs.sentinel.assistant.reliability;

import java.io.Serializable;

public final class AssistantResult<T> implements Serializable {
    public final boolean success;
    public final T data;
    public final AssistantError error;
    public final AssistantExecutionTrace trace;

    private AssistantResult(boolean success, T data, AssistantError error, AssistantExecutionTrace trace) {
        this.success = success;
        this.data = data;
        this.error = error;
        this.trace = trace;
    }

    public static <T> AssistantResult<T> success(T data) {
        return new AssistantResult<>(true, data, null, null);
    }

    public AssistantResult<T> withTrace(AssistantExecutionTrace newTrace) {
        return new AssistantResult<>(this.success, this.data, this.error, newTrace);
    }

    public static <T> AssistantResult<T> success(T data, AssistantExecutionTrace trace) {
        return new AssistantResult<>(true, data, null, trace);
    }

    public static <T> AssistantResult<T> failure(AssistantError error, AssistantExecutionTrace trace) {
        return new AssistantResult<>(false, null, error, trace);
    }
}
