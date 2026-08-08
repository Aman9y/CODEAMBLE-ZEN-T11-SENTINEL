package online.monarchlabs.sentinel.assistant.core;

import online.monarchlabs.sentinel.assistant.parser.ParserDebugInfo;
import online.monarchlabs.sentinel.assistant.planner.AssistantPlan;

public class AssistantPlanningResult {
    public enum ResultType {
        SUCCESS,
        NEEDS_CLARIFICATION,
        UNSUPPORTED,
        VALIDATION_FAILED,
        CONFLICT_FOUND,
        EXECUTION_FAILED,
        /** A conversational reply (greeting/help/yes/no/thanks) with no plan. */
        INFO
    }

    private final ResultType type;
    private final AssistantPlan plan;
    private final AssistantErrorCode errorCode;
    private final String message;
    private final ParserDebugInfo debugInfo;

    private AssistantPlanningResult(ResultType type, AssistantPlan plan, AssistantErrorCode errorCode,
                                    String message, ParserDebugInfo debugInfo) {
        this.type = type;
        this.plan = plan;
        this.errorCode = errorCode == null ? AssistantErrorCode.NONE : errorCode;
        this.message = message;
        this.debugInfo = debugInfo;
    }

    public static AssistantPlanningResult success(AssistantPlan plan, ParserDebugInfo debugInfo) {
        return new AssistantPlanningResult(ResultType.SUCCESS, plan, AssistantErrorCode.NONE,
                "Plan ready for confirmation.", debugInfo);
    }

    public static AssistantPlanningResult needsClarification(AssistantErrorCode errorCode, String message,
                                                            ParserDebugInfo debugInfo) {
        return new AssistantPlanningResult(ResultType.NEEDS_CLARIFICATION, null, errorCode, message, debugInfo);
    }

    public static AssistantPlanningResult unsupported(String message, ParserDebugInfo debugInfo) {
        return new AssistantPlanningResult(ResultType.UNSUPPORTED, null, AssistantErrorCode.UNSUPPORTED_INTENT,
                message, debugInfo);
    }

    public static AssistantPlanningResult info(String message, ParserDebugInfo debugInfo) {
        return new AssistantPlanningResult(ResultType.INFO, null, AssistantErrorCode.NONE, message, debugInfo);
    }

    public ResultType getType() {
        return type;
    }

    public AssistantPlan getPlan() {
        return plan;
    }

    public AssistantErrorCode getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public ParserDebugInfo getDebugInfo() {
        return debugInfo;
    }
}
