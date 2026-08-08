package online.monarchlabs.sentinel.assistant.reliability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import online.monarchlabs.sentinel.assistant.context.AssistantConversationState;
import online.monarchlabs.sentinel.assistant.context.AssistantConversationStore;
import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;
import org.junit.Before;
import org.junit.Test;

public final class ReliabilityFrameworkTest {

    private AssistantConversationState conversationState;
    private AssistantConversationStore conversationStore;
    private AssistantClarificationManager clarificationManager;

    @Before
    public void setUp() {
        conversationState = new AssistantConversationState();
        conversationStore = new AssistantConversationStore(null, "dummy_child");
        clarificationManager = new AssistantClarificationManager();
    }

    @Test
    public void testFollowUpParser_Cancel() {
        FollowUpParser.FollowUpResult res = FollowUpParser.parse("cancel");
        assertEquals(FollowUpParser.Type.CANCEL, res.type);

        res = FollowUpParser.parse("never mind");
        assertEquals(FollowUpParser.Type.CANCEL, res.type);
    }

    @Test
    public void testFollowUpParser_Confirm() {
        FollowUpParser.FollowUpResult res = FollowUpParser.parse("yes");
        assertEquals(FollowUpParser.Type.YES, res.type);
        assertEquals(true, res.value);

        res = FollowUpParser.parse("ok");
        assertEquals(FollowUpParser.Type.YES, res.type);
        assertEquals(true, res.value);
    }

    @Test
    public void testFollowUpParser_Duration() {
        FollowUpParser.FollowUpResult res = FollowUpParser.parse("30");
        assertEquals(FollowUpParser.Type.DURATION, res.type);
        assertEquals(1800000L, res.value); // 30 * 60 * 1000

        res = FollowUpParser.parse("1 hour");
        assertEquals(FollowUpParser.Type.DURATION, res.type);
        assertEquals(3600000L, res.value); // 1 * 60 * 60 * 1000
    }

    @Test
    public void testFollowUpParser_Index() {
        FollowUpParser.FollowUpResult res = FollowUpParser.parse("first");
        assertEquals(FollowUpParser.Type.FIRST, res.type);
        assertEquals(0, res.value);

        res = FollowUpParser.parse("second");
        assertEquals(FollowUpParser.Type.SECOND, res.type);
        assertEquals(1, res.value);
    }

    @Test
    public void testTraceRingBuffer() {
        // Generate more than 100 traces
        for (int i = 0; i < 105; i++) {
            AssistantExecutionTrace.start("command " + i);
        }

        List<AssistantExecutionTrace> traces = AssistantExecutionTrace.getRecentTraces();
        assertTrue(traces.size() <= 100);
        assertEquals("command 104", traces.get(traces.size() - 1).originalInput);
    }

    @Test
    public void testClarificationLoopDetection() {
        // Prepare clarification state
        conversationState.setPendingClarification(AssistantErrorCode.MISSING_DURATION, "block youtube", System.currentTimeMillis());

        // Send a garbage/invalid response
        FollowUpParser.FollowUpResult garbage = FollowUpParser.parse("nonsense");
        AssistantClarificationManager.MergedSlotsResult res1 = clarificationManager.resolveAndMerge(garbage, conversationState, conversationStore);
        assertFalse(res1.success);
        assertNotNull(res1.error);
        assertEquals(AssistantErrorCode.PLAN_MISSING_PARAMETER, res1.error.code);

        // Send another invalid response (loop limit exceeded on second failure)
        AssistantClarificationManager.MergedSlotsResult res2 = clarificationManager.resolveAndMerge(garbage, conversationState, conversationStore);
        assertFalse(res2.success);
        assertNotNull(res2.error);
        assertEquals(AssistantErrorCode.CLARIFICATION_LOOP_DETECTED, res2.error.code);
    }
}
