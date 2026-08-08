package online.monarchlabs.sentinel.assistant.reliability;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import online.monarchlabs.sentinel.assistant.context.AssistantConversationState;
import online.monarchlabs.sentinel.assistant.context.AssistantConversationStore;
import online.monarchlabs.sentinel.assistant.context.PendingClarification;
import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;

public final class AssistantClarificationManager {

    public static final class MergedSlotsResult {
        public final boolean success;
        public final String originalInput;
        public final Long durationMillis;
        public final Boolean confirmation;
        public final String appTarget;
        public final AssistantError error;

        public MergedSlotsResult(boolean success, String originalInput, Long durationMillis, Boolean confirmation, String appTarget, AssistantError error) {
            this.success = success;
            this.originalInput = originalInput;
            this.durationMillis = durationMillis;
            this.confirmation = confirmation;
            this.appTarget = appTarget;
            this.error = error;
        }

        public static MergedSlotsResult success(String originalInput, Long durationMillis, Boolean confirmation, String appTarget) {
            return new MergedSlotsResult(true, originalInput, durationMillis, confirmation, appTarget, null);
        }

        public static MergedSlotsResult failure(AssistantError error) {
            return new MergedSlotsResult(false, null, null, null, null, error);
        }
    }

    public MergedSlotsResult resolveAndMerge(
            FollowUpParser.FollowUpResult followUp,
            AssistantConversationState conversationState,
            AssistantConversationStore conversationStore) {

        long now = System.currentTimeMillis();
        if (!conversationState.hasActivePendingClarification(now)) {
            return MergedSlotsResult.failure(new AssistantError(
                    AssistantErrorCategory.CONFIRMATION,
                    AssistantErrorCode.CONFIRMATION_EXPIRED,
                    "Clarification session expired",
                    "The pending clarification session has expired.",
                    true,
                    false,
                    new ArrayList<>(),
                    null
            ));
        }

        PendingClarification pending = conversationState.getPendingClarification();
        AssistantErrorCode errorCode = pending.getErrorCode();

        // Increment repeat/loop count
        conversationState.setPendingClarification(errorCode, pending.getOriginalInput(), now);
        conversationStore.save(conversationState);

        // Loop detection check (max 2 attempts)
        if (conversationState.getClarificationRepeatCount() > 2) {
            conversationState.clearPendingClarification();
            conversationState.clearClarificationRepeatCount();
            conversationStore.save(conversationState);

            return MergedSlotsResult.failure(new AssistantError(
                    AssistantErrorCategory.CONFIRMATION,
                    AssistantErrorCode.CLARIFICATION_LOOP_DETECTED,
                    "Clarification loop detected",
                    "Sorry, I couldn't understand that. Please try a different command.",
                    false,
                    false,
                    new ArrayList<>(),
                    null
            ));
        }

        // Parse slot updates
        Long duration = null;
        Boolean confirmation = null;
        String appTarget = null;

        if (errorCode == AssistantErrorCode.MISSING_DURATION && followUp.type == FollowUpParser.Type.DURATION) {
            duration = (Long) followUp.value;
        } else if (errorCode == AssistantErrorCode.MISSING_APP && (followUp.type == FollowUpParser.Type.UNKNOWN || followUp.type == FollowUpParser.Type.FIRST)) {
            appTarget = followUp.raw;
        } else if (errorCode == AssistantErrorCode.CONFLICT_FOUND && (followUp.type == FollowUpParser.Type.YES || followUp.type == FollowUpParser.Type.NO)) {
            confirmation = (Boolean) followUp.value;
        } else if (followUp.type == FollowUpParser.Type.YES || followUp.type == FollowUpParser.Type.NO) {
            confirmation = (Boolean) followUp.value;
        } else {
            // Unrecognized follow-up for this clarification type
            return MergedSlotsResult.failure(new AssistantError(
                    AssistantErrorCategory.ENTITY_RESOLUTION,
                    AssistantErrorCode.PLAN_MISSING_PARAMETER,
                    "Could not resolve clarification reply. Please provide a valid reply.",
                    "Shorthand reply could not be matched to the pending slot type.",
                    true,
                    false,
                    new ArrayList<>(),
                    null
            ));
        }

        // Success: clear clarification state
        conversationState.clearPendingClarification();
        conversationState.clearClarificationRepeatCount();
        conversationStore.save(conversationState);

        return MergedSlotsResult.success(pending.getOriginalInput(), duration, confirmation, appTarget);
    }
}
