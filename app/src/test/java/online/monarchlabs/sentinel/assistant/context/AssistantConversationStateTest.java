package online.monarchlabs.sentinel.assistant.context;

import org.junit.Test;

import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class AssistantConversationStateTest {
    @Test
    public void durationAnswerCompletesPreviousCommand() {
        AssistantConversationState state = new AssistantConversationState();
        state.setPendingClarification(AssistantErrorCode.MISSING_DURATION,
                "Set YouTube limit", 1000L);

        assertEquals("Set YouTube limit for 2 days",
                state.completePendingClarification("2 days", 2000L));
        assertFalse(state.hasActivePendingClarification(2000L));
    }

    @Test
    public void durationAnswerRespectsTrailingConnector() {
        AssistantConversationState state = new AssistantConversationState();
        state.setPendingClarification(AssistantErrorCode.MISSING_DURATION,
                "Set YouTube limit to", 1000L);

        assertEquals("Set YouTube limit to 30 minutes",
                state.completePendingClarification("30 minutes", 2000L));
    }

    @Test
    public void timeRangeAnswerCompletesPreviousCommand() {
        AssistantConversationState state = new AssistantConversationState();
        state.setPendingClarification(AssistantErrorCode.MISSING_TIME_RANGE,
                "Block YouTube daily", 1000L);

        assertEquals("Block YouTube daily from 6 PM to 10 PM",
                state.completePendingClarification("6 PM to 10 PM", 2000L));
    }

    @Test
    public void timeRangeAnswerRespectsTrailingConnector() {
        AssistantConversationState state = new AssistantConversationState();
        state.setPendingClarification(AssistantErrorCode.MISSING_TIME_RANGE,
                "Block YouTube from", 1000L);

        assertEquals("Block YouTube from 6 PM to 10 PM",
                state.completePendingClarification("6 PM to 10 PM", 2000L));
    }

    @Test
    public void expiredClarificationIsIgnored() {
        AssistantConversationState state = new AssistantConversationState();
        state.setPendingClarification(AssistantErrorCode.MISSING_DURATION,
                "Pause restrictions", 1000L);

        assertNull(state.completePendingClarification("30 minutes", 10L * 60L * 1000L));
    }
}
