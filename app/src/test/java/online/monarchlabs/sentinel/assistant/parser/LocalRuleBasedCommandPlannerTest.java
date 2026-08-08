package online.monarchlabs.sentinel.assistant.parser;

import java.util.List;
import org.junit.Test;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.core.CommandSource;
import online.monarchlabs.sentinel.assistant.planner.LocalRuleBasedCommandPlanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LocalRuleBasedCommandPlannerTest {
    private final LocalRuleBasedCommandPlanner planner = new LocalRuleBasedCommandPlanner();

    @Test
    public void normalizesHinglishAndAliases() {
        AssistantPlanningResult result = plan("YouTube band karo");

        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, result.getType());
        assertEquals(AssistantIntent.BLOCK_APP_NOW, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
        assertFalse(result.getDebugInfo().getMatchedAliases().isEmpty());
    }

    @Test
    public void detectsTemporaryAppBlock() {
        AssistantPlanningResult result = plan("Block yt for 2 hours");

        assertEquals(AssistantIntent.BLOCK_APP_TEMPORARY, result.getDebugInfo().getDetectedIntent());
        assertEquals(Long.valueOf(2L * 60L * 60L * 1000L), result.getPlan().getSlots().getDurationMillis());
    }

    @Test
    public void extractsScheduledBlockTimeRange() {
        AssistantPlanningResult result = plan("Block Instagram every weekday from 6 PM to 9 PM");

        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, result.getType());
        assertEquals(AssistantIntent.SCHEDULE_BLOCK_APP, result.getDebugInfo().getDetectedIntent());
        assertEquals("weekdays", result.getPlan().getSlots().getRepeatRule());
        assertNotNull(result.getPlan().getSlots().getTimeRange());
        assertEquals(18 * 60, result.getPlan().getSlots().getTimeRange().getStartMinutes());
        assertEquals(21 * 60, result.getPlan().getSlots().getTimeRange().getEndMinutes());
    }

    @Test
    public void extractsTimerDurationWhenLimitUsesTo() {
        AssistantPlanningResult result = plan("Set YouTube limit to 30 minutes");

        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, result.getType());
        assertEquals(AssistantIntent.SET_APP_TIMER, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
        assertEquals(Long.valueOf(30L * 60L * 1000L), result.getPlan().getSlots().getDurationMillis());
    }

    @Test
    public void extractsDailyScheduleWhenRangeUsesFor() {
        AssistantPlanningResult result = plan("Block YouTube for 6 PM to 10 PM daily");

        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, result.getType());
        assertEquals(AssistantIntent.SCHEDULE_BLOCK_APP, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
        assertEquals("daily", result.getPlan().getSlots().getRepeatRule());
        assertNotNull(result.getPlan().getSlots().getTimeRange());
        assertEquals(18 * 60, result.getPlan().getSlots().getTimeRange().getStartMinutes());
        assertEquals(22 * 60, result.getPlan().getSlots().getTimeRange().getEndMinutes());
    }

    @Test
    public void extractsCategoryException() {
        AssistantPlanningResult result = plan("Block games except WhatsApp");

        assertEquals(AssistantIntent.BLOCK_CATEGORY_NOW, result.getDebugInfo().getDetectedIntent());
        assertEquals("games", result.getPlan().getSlots().getCategoryName());
        assertEquals("whatsapp", result.getPlan().getSlots().getExceptions().get(0));
    }

    @Test
    public void ambiguousTimeNeedsClarification() {
        AssistantPlanningResult result = plan("Block YouTube from 6 to 9");

        assertEquals(AssistantPlanningResult.ResultType.NEEDS_CLARIFICATION, result.getType());
        assertEquals(AssistantIntent.SCHEDULE_BLOCK_APP, result.getDebugInfo().getDetectedIntent());
        assertFalse(result.getDebugInfo().getAmbiguities().isEmpty());
    }

    @Test
    public void queryAndUndoDoNotRequireControlSlots() {
        assertEquals(AssistantIntent.QUERY_USAGE, plan("Show today usage").getDebugInfo().getDetectedIntent());
        assertEquals(AssistantIntent.UNDO_LAST_ACTION, plan("Undo last change").getDebugInfo().getDetectedIntent());
    }

    // ---- Phase 1: synonym vocabulary (ban/stop/lock/rok do -> block) ----

    @Test
    public void synonymBanMapsToBlock() {
        AssistantPlanningResult result = plan("Ban Instagram");

        assertEquals(AssistantIntent.BLOCK_APP_NOW, result.getDebugInfo().getDetectedIntent());
        assertEquals("instagram", result.getPlan().getSlots().getAppName());
    }

    @Test
    public void synonymLockWithHinglishAlias() {
        AssistantPlanningResult result = plan("Rok do YouTube");

        assertEquals(AssistantIntent.BLOCK_APP_NOW, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
    }

    // ---- Phase 2: scoring + relative time + negation ----

    @Test
    public void relativeTimeTonightIsScheduleBlock() {
        AssistantPlanningResult result = plan("Block YouTube tonight");

        assertEquals(AssistantIntent.SCHEDULE_BLOCK_APP, result.getDebugInfo().getDetectedIntent());
        assertNotNull(result.getPlan().getSlots().getTimeRange());
        assertEquals(18 * 60, result.getPlan().getSlots().getTimeRange().getStartMinutes());
    }

    @Test
    public void negationFlipsBlockToUnblock() {
        AssistantPlanningResult result = plan("Don't block WhatsApp");

        assertEquals(AssistantIntent.UNBLOCK_APP, result.getDebugInfo().getDetectedIntent());
    }

    @Test
    public void freeWordOrderStillDetected() {
        // "keep my kid off youtube tonight" - off/keep normalized, time word present
        AssistantPlanningResult result = plan("Restrict youtube tonight");

        assertEquals(AssistantIntent.SCHEDULE_BLOCK_APP, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
    }

    @Test
    public void hinglishMatChalaoMapsToBlock() {
        AssistantPlanningResult result = plan("YouTube mat chalao");

        assertEquals(AssistantIntent.BLOCK_APP_NOW, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
    }

    @Test
    public void hinglishKholoMapsToUnblock() {
        AssistantPlanningResult result = plan("YouTube ko khol do");

        assertEquals(AssistantIntent.UNBLOCK_APP, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
    }

    @Test
    public void hinglishSeTakRangeBecomesSchedule() {
        AssistantPlanningResult result = plan("YouTube 6 PM se 9 PM tak band rakho");

        assertEquals(AssistantIntent.SCHEDULE_BLOCK_APP, result.getDebugInfo().getDetectedIntent());
        assertNotNull(result.getPlan().getSlots().getTimeRange());
        assertEquals(18 * 60, result.getPlan().getSlots().getTimeRange().getStartMinutes());
        assertEquals(21 * 60, result.getPlan().getSlots().getTimeRange().getEndMinutes());
    }

    @Test
    public void hinglishDurationGhanteParsesAsTemporaryBlock() {
        AssistantPlanningResult result = plan("YouTube ko 2 ghante ke liye block karo");

        assertEquals(AssistantIntent.BLOCK_APP_TEMPORARY, result.getDebugInfo().getDetectedIntent());
        assertEquals(Long.valueOf(2L * 60L * 60L * 1000L), result.getPlan().getSlots().getDurationMillis());
    }

    // ---- Phase 3: catalog + fuzzy matching ----

    @Test
    public void fuzzyTypoStillResolvesApp() {
        AssistantPlanningResult result = plan("Block yutube");

        assertEquals(AssistantIntent.BLOCK_APP_NOW, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
    }

    @Test
    public void catalogAppExtractedRegardlessOfWordOrder() {
        AssistantPlanningResult result = plan("Lock youtube for 2 hours");

        assertEquals(AssistantIntent.BLOCK_APP_TEMPORARY, result.getDebugInfo().getDetectedIntent());
        assertEquals("youtube", result.getPlan().getSlots().getAppName());
        assertEquals(Long.valueOf(2L * 60L * 60L * 1000L), result.getPlan().getSlots().getDurationMillis());
    }

    // ---- Phase 4a: multi-target ----

    @Test
    public void multiTargetBlockCreatesMultipleActions() {
        AssistantPlanningResult result = plan("Block youtube and instagram");

        assertEquals(AssistantIntent.BLOCK_APP_NOW, result.getDebugInfo().getDetectedIntent());
        assertEquals(2, result.getPlan().getSlots().getAppTargets().size());
        assertEquals("youtube", result.getPlan().getSlots().getAppTargets().get(0));
        assertEquals("instagram", result.getPlan().getSlots().getAppTargets().get(1));
        assertEquals(2, result.getPlan().getActions().size());
    }

    @Test
    public void multiTargetSummaryJoinsWithAnd() {
        AssistantPlanningResult result = plan("Block youtube, instagram and tiktok");

        assertEquals("Block youtube, instagram and tiktok", result.getPlan().getSummary());
    }

    @Test
    public void multiTargetWithFuzzyAndFallback() {
        AssistantPlanningResult result = plan("Block insta, wahtsapp, chess, drive");

        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, result.getType());
        assertEquals(AssistantIntent.BLOCK_APP_NOW, result.getDebugInfo().getDetectedIntent());

        List<String> targets = result.getPlan().getSlots().getAppTargets();
        assertEquals(4, targets.size());
        assertEquals("instagram", targets.get(0));
        assertEquals("drive", targets.get(1));
        assertEquals("whatsapp", targets.get(2));
        assertEquals("chess", targets.get(3));

        assertEquals("Block instagram, drive, whatsapp and chess", result.getPlan().getSummary());
    }

    // ---- Phase 4b: conversational intents ----

    @Test
    public void greetingReturnsInfoReply() {
        AssistantPlanningResult result = plan("Hi");

        assertEquals(AssistantPlanningResult.ResultType.INFO, result.getType());
        assertEquals(AssistantIntent.GREETING, result.getDebugInfo().getDetectedIntent());
    }

    @Test
    public void helpReturnsInfoReply() {
        AssistantPlanningResult result = plan("What can you do?");

        assertEquals(AssistantPlanningResult.ResultType.INFO, result.getType());
        assertEquals(AssistantIntent.HELP, result.getDebugInfo().getDetectedIntent());
    }

    @Test
    public void unsupportedCommandGivesContextualMessage() {
        AssistantPlanningResult result = plan("xyzzy florp");

        assertEquals(AssistantPlanningResult.ResultType.UNSUPPORTED, result.getType());
    }

    @Test
    public void extractsDurationWithMinsAndStandaloneFormats() {
        AssistantPlanningResult res1 = plan("NOW add 30 minutes timer to YouTube");
        assertEquals(AssistantIntent.SET_APP_TIMER, res1.getDebugInfo().getDetectedIntent());
        assertEquals(Long.valueOf(30L * 60L * 1000L), res1.getPlan().getSlots().getDurationMillis());

        AssistantPlanningResult res2 = plan("Block YouTube for 30m");
        assertEquals(AssistantIntent.BLOCK_APP_TEMPORARY, res2.getDebugInfo().getDetectedIntent());
        assertEquals(Long.valueOf(30L * 60L * 1000L), res2.getPlan().getSlots().getDurationMillis());

        AssistantPlanningResult res3 = plan("Block YouTube for 30 min");
        assertEquals(AssistantIntent.BLOCK_APP_TEMPORARY, res3.getDebugInfo().getDetectedIntent());
        assertEquals(Long.valueOf(30L * 60L * 1000L), res3.getPlan().getSlots().getDurationMillis());

        AssistantPlanningResult res4 = plan("Block YouTube for 30 mins");
        assertEquals(AssistantIntent.BLOCK_APP_TEMPORARY, res4.getDebugInfo().getDetectedIntent());
        assertEquals(Long.valueOf(30L * 60L * 1000L), res4.getPlan().getSlots().getDurationMillis());

        AssistantPlanningResult res5 = plan("Block YouTube 30 mins");
        assertEquals(AssistantIntent.BLOCK_APP_TEMPORARY, res5.getDebugInfo().getDetectedIntent());
        assertEquals(Long.valueOf(30L * 60L * 1000L), res5.getPlan().getSlots().getDurationMillis());
    }

    @Test
    public void testDynamicCatalogInjection() {
        // Setup planner with a dynamic installed app list
        planner.setInstalledApps(java.util.Arrays.asList("fortnite", "genshin impact"));

        // This should now resolve to the custom app because it's injected
        AssistantPlanningResult res = plan("Block fortnite");
        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, res.getType());
        assertEquals(AssistantIntent.BLOCK_APP_NOW, res.getPlan().getIntent());
        assertTrue(res.getPlan().getSlots().getAppTargets().contains("fortnite"));
    }

    @Test
    public void testClauseSplitting() {
        // Compound command with different verbs
        AssistantPlanningResult res = plan("unblock youtube but block instagram");
        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, res.getType());
        // The merged plan takes the intent of the first clause
        assertEquals(AssistantIntent.UNBLOCK_APP, res.getPlan().getIntent());

        // Check that both apps were captured in the merged slots
        java.util.List<String> targets = res.getPlan().getSlots().getAppTargets();
        assertTrue(targets.contains("youtube"));
        assertTrue(targets.contains("instagram"));

        // Verify multiple actions were created
        java.util.List<online.monarchlabs.sentinel.assistant.planner.ActionPlan> actions = res.getPlan().getActions();
        assertEquals(2, actions.size());
        assertEquals(online.monarchlabs.sentinel.assistant.core.AssistantActionType.UNBLOCK_APP, actions.get(0).getActionType());
        assertTrue(actions.get(0).getTargets().contains("youtube"));
        assertEquals(online.monarchlabs.sentinel.assistant.core.AssistantActionType.BLOCK_APP, actions.get(1).getActionType());
        assertTrue(actions.get(1).getTargets().contains("instagram"));
    }

    @Test
    public void testClauseSplitting_SameVerb_NoSplit() {
        // "and" connects apps under the same verb, shouldn't split
        AssistantPlanningResult res = plan("block youtube and instagram");
        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, res.getType());
        assertEquals(AssistantIntent.BLOCK_APP_NOW, res.getPlan().getIntent());

        java.util.List<online.monarchlabs.sentinel.assistant.planner.ActionPlan> actions = res.getPlan().getActions();
        assertEquals(2, actions.size());
        assertEquals("Block youtube and instagram", res.getPlan().getSummary());
    }

    @Test
    public void testFallbackExtractionAcrossAnd() {
        // "someunknownapp" and "anotherunknown" are not in catalog, so they fall back to regex
        AssistantPlanningResult res = plan("block someunknownapp and anotherunknown");
        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, res.getType());
        assertEquals(AssistantIntent.BLOCK_APP_NOW, res.getPlan().getIntent());

        // Both should be extracted since "and" is no longer a regex stop-word
        java.util.List<String> targets = res.getPlan().getSlots().getAppTargets();
        assertTrue(targets.contains("someunknownapp"));
        assertTrue(targets.contains("anotherunknown"));
    }

    @Test
    public void testExtendedTypoCoverage() {
        AssistantPlanningResult res1 = plan("blck youtube");
        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, res1.getType());
        assertEquals(AssistantIntent.BLOCK_APP_NOW, res1.getPlan().getIntent());

        AssistantPlanningResult res2 = plan("unbloack youtube");
        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, res2.getType());
        assertEquals(AssistantIntent.UNBLOCK_APP, res2.getPlan().getIntent());

        AssistantPlanningResult res3 = plan("block playstore");
        assertEquals(AssistantPlanningResult.ResultType.SUCCESS, res3.getType());
        assertTrue(res3.getPlan().getSlots().getAppTargets().contains("play store"));
    }


    private AssistantPlanningResult plan(String input) {
        return planner.plan(new AssistantPlanningRequest("parent1", "child1", "Rahul", input, CommandSource.PARENT_TEXT));
    }
}
