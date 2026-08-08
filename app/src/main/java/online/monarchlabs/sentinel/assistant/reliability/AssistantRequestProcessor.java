package online.monarchlabs.sentinel.assistant.reliability;


import java.util.ArrayList;
import java.util.List;
import online.monarchlabs.sentinel.assistant.context.AssistantConversationState;
import online.monarchlabs.sentinel.assistant.context.AssistantConversationStore;
import online.monarchlabs.sentinel.assistant.planner.LocalRuleBasedCommandPlanner;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.planner.AssistantPlan;
import online.monarchlabs.sentinel.assistant.validation.AssistantPlanValidator;
import online.monarchlabs.sentinel.assistant.validation.ValidationResult;
import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;
import online.monarchlabs.sentinel.assistant.core.CommandSource;

public final class AssistantRequestProcessor {
    private final LocalRuleBasedCommandPlanner planner;
    private final AssistantPlanValidator validator;
    private final AssistantClarificationManager clarificationManager;
    private final online.monarchlabs.sentinel.assistant.context.ConversationContextEngine contextEngine;
    private final online.monarchlabs.sentinel.assistant.handlers.AssistantHandlerRegistry handlerRegistry;

    public interface RequestCallback {
        void onResult(AssistantResult<?> result);
    }

    public AssistantRequestProcessor(
            LocalRuleBasedCommandPlanner planner,
            AssistantPlanValidator validator,
            AssistantClarificationManager clarificationManager,
            online.monarchlabs.sentinel.assistant.context.ConversationContextEngine contextEngine,
            online.monarchlabs.sentinel.assistant.handlers.AssistantHandlerRegistry handlerRegistry) {
        this.planner = planner;
        this.validator = validator;
        this.clarificationManager = clarificationManager;
        this.contextEngine = contextEngine;
        this.handlerRegistry = handlerRegistry;
    }

    public void processRequest(
            String text,
            String parentId,
            String childId,
            String childName,
            List<String> installedApps,
            AssistantConversationState conversationState,
            AssistantConversationStore conversationStore,
            RequestCallback callback) {

        long startTime = System.currentTimeMillis();
        AssistantExecutionTrace trace = AssistantExecutionTrace.start(text);
        trace.markStage("INPUT_RECEIVED");

        // 1. Clarification & Follow-Up Resolution
        trace.markStage("FOLLOW_UP_RESOLUTION");
        long now = System.currentTimeMillis();
        Long durationOverride = null;
        Boolean confirmationOverride = null;
        String appTargetOverride = null;

        if (conversationState.hasActivePendingClarification(now)) {
            FollowUpParser.FollowUpResult followUp = FollowUpParser.parse(text);
            if (followUp.type == FollowUpParser.Type.CANCEL) {
                conversationState.clearPendingClarification();
                conversationState.clearClarificationRepeatCount();
                conversationStore.save(conversationState);

                AssistantError error = new AssistantError(
                        AssistantErrorCategory.CONFIRMATION,
                        AssistantErrorCode.CONFIRMATION_CANCELLED,
                        "Clarification cancelled by user",
                        "The parent cancelled the pending clarification request.",
                        true,
                        false,
                        new ArrayList<>(),
                        null
                );
                trace.finish(trace.parsedIntent, null, "CANCELLED", null, System.currentTimeMillis() - startTime);
                callback.onResult(AssistantResult.failure(error, trace));
                return;
            }

            // Stateful clarification merging (without rewriting user text)
            AssistantClarificationManager.MergedSlotsResult mergedResult = clarificationManager.resolveAndMerge(
                    followUp, conversationState, conversationStore);
            if (!mergedResult.success) {
                trace.finish(trace.parsedIntent, null, "CLARIFICATION_FAILED", mergedResult.error.message, System.currentTimeMillis() - startTime);
                callback.onResult(AssistantResult.failure(mergedResult.error, trace));
                return;
            }
            // Use the original text and apply overrides
            text = mergedResult.originalInput;
            durationOverride = mergedResult.durationMillis;
            confirmationOverride = mergedResult.confirmation;
            appTargetOverride = mergedResult.appTarget;
        }

        // 2. Planning
        trace.markStage("PARSING");
        if (installedApps != null && !installedApps.isEmpty()) {
            planner.setInstalledApps(installedApps);
        }

        AssistantPlanningRequest request = new AssistantPlanningRequest(
                parentId,
                childId,
                childName,
                text,
                CommandSource.PARENT_TEXT);

        if (durationOverride != null) {
            request.setDurationOverride(durationOverride);
        }
        if (appTargetOverride != null) {
            request.setAppTargetOverride(appTargetOverride);
        }
        if (confirmationOverride != null) {
            request.setConfirmationOverride(confirmationOverride);
        }

        trace.markStage("PLANNING");
        AssistantPlanningResult planResult = planner.plan(request);
        trace.parsedIntent = planResult.getPlan() != null ? planResult.getPlan().getIntent().name() : "UNKNOWN";

        if (planResult.getType() == AssistantPlanningResult.ResultType.UNSUPPORTED) {
            AssistantError error = new AssistantError(
                    AssistantErrorCategory.PARSING,
                    AssistantErrorCode.PARSE_UNKNOWN_COMMAND,
                    planResult.getMessage(),
                    "The assistant parser could not recognize the command structure.",
                    true,
                    false,
                    new ArrayList<>(),
                    null
            );
            trace.finish(trace.parsedIntent, null, "UNSUPPORTED", planResult.getMessage(), System.currentTimeMillis() - startTime);
            callback.onResult(AssistantResult.failure(error, trace));
            return;
        }

        if (planResult.getType() == AssistantPlanningResult.ResultType.INFO) {
            trace.finish(trace.parsedIntent, "INFO", "SUCCESS", null, System.currentTimeMillis() - startTime);
            callback.onResult(AssistantResult.success(planResult, trace));
            return;
        }

        if (planResult.getType() == AssistantPlanningResult.ResultType.NEEDS_CLARIFICATION) {
            AssistantError error = handleNeedsClarification(planResult.getErrorCode(), text, planResult.getMessage(), conversationState, conversationStore);
            trace.finish(trace.parsedIntent, null, "NEEDS_CLARIFICATION", planResult.getMessage(), System.currentTimeMillis() - startTime);
            callback.onResult(AssistantResult.failure(error, trace));
            return;
        }

        // 3. Context Processing
        contextEngine.processContext(request, conversationState);

        // 4. Validation (only for ACTION intents)
        AssistantPlan plan = planResult.getPlan();
        if (plan != null && plan.getIntent().getCategory() == online.monarchlabs.sentinel.assistant.core.AssistantIntent.Category.ACTION) {
            trace.markStage("VALIDATION");
            ValidationResult validation = validator.validate(plan);
            if (!validation.canContinue()) {
                AssistantError error = handleNeedsClarification(validation.getErrorCode(), text, validation.getMessage(), conversationState, conversationStore);
                trace.finish(trace.parsedIntent, null, "NEEDS_CLARIFICATION", validation.getMessage(), System.currentTimeMillis() - startTime);
                callback.onResult(AssistantResult.failure(error, trace));
                return;
            }
        }

        conversationState.clearClarificationRepeatCount();
        conversationStore.save(conversationState);

        // 5. Dispatch via HandlerRegistry
        AssistantResult<?> dispatchResult = handlerRegistry.dispatch(request, planResult, conversationState);

        trace.finish(trace.parsedIntent, plan != null ? plan.getSummary() : null, "SUCCESS", null, System.currentTimeMillis() - startTime);
        callback.onResult(dispatchResult.withTrace(trace));
    }

    private AssistantError handleNeedsClarification(
            AssistantErrorCode errorCode,
            String text,
            String message,
            AssistantConversationState conversationState,
            AssistantConversationStore conversationStore) {

        conversationState.setPendingClarification(errorCode, text, System.currentTimeMillis());
        conversationStore.save(conversationState);

        // Check loop limits (max 2 attempts)
        if (conversationState.getLastClarificationErrorCode() == errorCode && conversationState.getClarificationRepeatCount() > 2) {
            conversationState.clearPendingClarification();
            conversationState.clearClarificationRepeatCount();
            conversationStore.save(conversationState);

            return new AssistantError(
                    AssistantErrorCategory.CONFIRMATION,
                    AssistantErrorCode.CLARIFICATION_LOOP_DETECTED,
                    "Clarification loop detected",
                    "Sorry, I couldn't understand that. Please try a different command.",
                    false,
                    false,
                    new ArrayList<>(),
                    null
            );
        }

        return new AssistantError(
                AssistantErrorCategory.ENTITY_RESOLUTION,
                errorCode,
                message,
                "The planner requires additional slots to complete the request.",
                true,
                false,
                new ArrayList<>(),
                null
        );
    }
}
