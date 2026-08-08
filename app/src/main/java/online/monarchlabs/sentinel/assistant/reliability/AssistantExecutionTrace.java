package online.monarchlabs.sentinel.assistant.reliability;

import android.util.Log;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import online.monarchlabs.sentinel.BuildConfig;

public final class AssistantExecutionTrace implements Serializable {
    private static final List<AssistantExecutionTrace> BUFFER = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_BUFFER_SIZE = 100;

    public final String traceId;
    public final long timestamp;
    public final String originalInput;
    public final List<String> stages = new ArrayList<>();
    public String parsedIntent = "UNKNOWN";

    public String planningSummary;
    public String validationResult;
    public String executionResult;
    public long durationMillis;

    private AssistantExecutionTrace(String originalInput) {
        this.traceId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
        this.originalInput = originalInput;
    }

    public static AssistantExecutionTrace start(String originalInput) {
        AssistantExecutionTrace trace = new AssistantExecutionTrace(originalInput);
        trace.markStage("INPUT_RECEIVED");
        addToBuffer(trace);
        return trace;
    }

    public void markStage(String stage) {
        stages.add(stage);
        if (BuildConfig.DEBUG) {
            try {
                Log.d("SentinelTrace", "[" + traceId + "] Stage: " + stage);
            } catch (Exception ignored) {
                // Ignore mock log warnings in unit tests
            }
        }
    }

    public void finish(String parsedIntent, String planningSummary, String validationResult, String executionResult, long durationMillis) {
        this.parsedIntent = parsedIntent;
        this.planningSummary = planningSummary;
        this.validationResult = validationResult;
        this.executionResult = executionResult;
        this.durationMillis = durationMillis;
        markStage("RESULT");
        if (BuildConfig.DEBUG) {
            try {
                Log.d("SentinelTrace", "[" + traceId + "] Finished. Duration: " + durationMillis + "ms");
            } catch (Exception ignored) {
                // Ignore mock log warnings in unit tests
            }
        }
    }

    private static void addToBuffer(AssistantExecutionTrace trace) {
        synchronized (BUFFER) {
            BUFFER.add(trace);
            while (BUFFER.size() > MAX_BUFFER_SIZE) {
                BUFFER.remove(0);
            }
        }
    }

    public static List<AssistantExecutionTrace> getRecentTraces() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }
}
