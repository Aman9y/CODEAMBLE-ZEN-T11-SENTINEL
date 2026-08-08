package online.monarchlabs.sentinel.assistant.planner;

import java.util.ArrayList;
import java.util.List;

import online.monarchlabs.sentinel.assistant.core.AssistantActionType;
import online.monarchlabs.sentinel.assistant.core.AssistantErrorCode;
import online.monarchlabs.sentinel.assistant.core.AssistantIntent;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningRequest;
import online.monarchlabs.sentinel.assistant.core.AssistantPlanningResult;
import online.monarchlabs.sentinel.assistant.core.CommandPlanner;
import online.monarchlabs.sentinel.assistant.core.RiskLevel;
import online.monarchlabs.sentinel.assistant.parser.ClauseSplitter;
import online.monarchlabs.sentinel.assistant.parser.DynamicAppCatalog;
import online.monarchlabs.sentinel.assistant.parser.ExtractedSlots;
import online.monarchlabs.sentinel.assistant.parser.IntentDetector;
import online.monarchlabs.sentinel.assistant.parser.IntentResult;
import online.monarchlabs.sentinel.assistant.parser.ParserDebugInfo;
import online.monarchlabs.sentinel.assistant.parser.SlotExtractor;
import online.monarchlabs.sentinel.assistant.parser.TextNormalizer;
import online.monarchlabs.sentinel.assistant.parser.TimeParser;

public class LocalRuleBasedCommandPlanner implements CommandPlanner {
    private final TextNormalizer textNormalizer;
    private final IntentDetector intentDetector;
    private List<String> installedApps;

    public LocalRuleBasedCommandPlanner() {
        this.textNormalizer = new TextNormalizer();
        this.intentDetector = new IntentDetector();
    }

    public void setInstalledApps(List<String> installedApps) {
        this.installedApps = installedApps;
    }

    private SlotExtractor createSlotExtractor() {
        TimeParser timeParser = new TimeParser();
        if (installedApps != null && !installedApps.isEmpty()) {
            return new SlotExtractor(timeParser, new DynamicAppCatalog(installedApps));
        }
        return new SlotExtractor(timeParser);
    }

    @Override
    public AssistantPlanningResult plan(AssistantPlanningRequest request) {
        String rawInput = request == null ? "" : request.getInputText();
        ParserDebugInfo debugInfo = new ParserDebugInfo(rawInput);
        String normalized = textNormalizer.normalize(rawInput, debugInfo);
        debugInfo.setNormalizedInput(normalized);

        List<String> clauses = ClauseSplitter.split(normalized);
        if (clauses.size() > 1) {
            return planMultiClause(request, clauses, debugInfo);
        }
        return planSingleClause(request, normalized, debugInfo);
    }

    private AssistantPlanningResult planMultiClause(AssistantPlanningRequest request,
                                                    List<String> clauses,
                                                    ParserDebugInfo debugInfo) {
        List<ActionPlan> mergedActions = new ArrayList<>();
        ExtractedSlots mergedSlots = new ExtractedSlots();
        List<String> summaryParts = new ArrayList<>();
        RiskLevel highestRisk = RiskLevel.LOW;
        AssistantIntent firstIntent = null;

        for (String clause : clauses) {
            AssistantPlanningResult subResult = planSingleClause(request, clause, debugInfo);
            if (subResult.getType() != AssistantPlanningResult.ResultType.SUCCESS) {
                return subResult;
            }
            AssistantPlan subPlan = subResult.getPlan();
            mergedActions.addAll(subPlan.getActions());
            for (String app : subPlan.getSlots().getAppTargets()) {
                mergedSlots.addAppTarget(app);
            }
            if (subPlan.getSlots().getDurationMillis() != null && mergedSlots.getDurationMillis() == null) {
                mergedSlots.setDurationMillis(subPlan.getSlots().getDurationMillis());
            }
            if (subPlan.getSlots().getTimeRange() != null && mergedSlots.getTimeRange() == null) {
                mergedSlots.setTimeRange(subPlan.getSlots().getTimeRange());
            }
            if (subPlan.getSlots().getRepeatRule() != null && mergedSlots.getRepeatRule() == null) {
                mergedSlots.setRepeatRule(subPlan.getSlots().getRepeatRule());
            }
            if (subPlan.getSlots().getCategoryName() != null && mergedSlots.getCategoryName() == null) {
                mergedSlots.setCategoryName(subPlan.getSlots().getCategoryName());
            }
            for (String exc : subPlan.getSlots().getExceptions()) {
                mergedSlots.addException(exc);
            }
            summaryParts.add(subPlan.getSummary());
            if (subPlan.getRiskLevel().ordinal() > highestRisk.ordinal()) {
                highestRisk = subPlan.getRiskLevel();
            }
            if (firstIntent == null) {
                firstIntent = subPlan.getIntent();
            }
        }

        String mergedSummary = String.join(" + ", summaryParts);
        AssistantPlan mergedPlan = new AssistantPlan(
                firstIntent, request.getParentId(), request.getSelectedChildId(),
                request.getSelectedChildName(), mergedActions, mergedSlots,
                new ArrayList<>(), highestRisk, mergedSummary, true);
        return AssistantPlanningResult.success(mergedPlan, debugInfo);
    }

    private AssistantPlanningResult planSingleClause(AssistantPlanningRequest request,
                                                     String normalized,
                                                     ParserDebugInfo debugInfo) {
        SlotExtractor slotExtractor = createSlotExtractor();

        IntentResult intentResult = intentDetector.detect(normalized);
        debugInfo.setDetectedIntent(intentResult.getIntent());
        debugInfo.setIntentConfidence(intentResult.getConfidence());

        ExtractedSlots slots = slotExtractor.extract(normalized);
        if (request != null) {
            if (request.getDurationOverride() != null) {
                slots.setDurationMillis(request.getDurationOverride());
            }
            if (request.getAppTargetOverride() != null) {
                slots.addAppTarget(request.getAppTargetOverride());
            }
        }
        debugInfo.setExtractedSlots(slots);

        AssistantIntent intent = intentResult.getIntent();
        if (intent == AssistantIntent.UNKNOWN) {
            String rawInput = request == null ? "" : request.getInputText();
            return AssistantPlanningResult.unsupported(
                    "I'm not sure what you mean by \"" + rawInput.trim() + "\". "
                            + "Try things like \"block YouTube\", \"set a 30-minute timer for Instagram\", "
                            + "\"block games tonight\", or \"show today's usage\".",
                    debugInfo);
        }
        AssistantPlanningResult conversational = handleConversational(intent, debugInfo);
        if (conversational != null) {
            return conversational;
        }
        if (slots.isAmbiguousTime()) {
            debugInfo.addAmbiguity("time_range");
            return AssistantPlanningResult.needsClarification(AssistantErrorCode.AMBIGUOUS_TIME,
                    "Do you mean AM or PM for that time range?", debugInfo);
        }
        String conflictMessage = detectConflictingActions(normalized, slots);
        if (conflictMessage != null) {
            return AssistantPlanningResult.needsClarification(online.monarchlabs.sentinel.assistant.core.AssistantErrorCode.CONFLICT_FOUND,
                    conflictMessage, debugInfo);
        }

        AssistantPlanningResult clarification = missingSlotResult(intent, slots, debugInfo);
        if (clarification != null) {
            return clarification;
        }

        AssistantPlan plan = buildPlan(request, intent, slots);
        return AssistantPlanningResult.success(plan, debugInfo);
    }

    private AssistantPlanningResult missingSlotResult(AssistantIntent intent, ExtractedSlots slots,
                                                     ParserDebugInfo debugInfo) {
        if (requiresApp(intent) && slots.getAppName() == null) {
            debugInfo.addMissingSlot("app");
            return AssistantPlanningResult.needsClarification(AssistantErrorCode.MISSING_APP,
                    "I understood you want to " + verbPhraseFor(intent)
                            + ", but which app? For example: YouTube, Instagram, WhatsApp.",
                    debugInfo);
        }
        if (requiresCategory(intent) && slots.getCategoryName() == null) {
            debugInfo.addMissingSlot("category");
            return AssistantPlanningResult.needsClarification(AssistantErrorCode.MISSING_CATEGORY,
                    "Which category? For example: games, social media, entertainment, or all apps.",
                    debugInfo);
        }
        if ((intent == AssistantIntent.BLOCK_APP_TEMPORARY || intent == AssistantIntent.PAUSE_RESTRICTIONS)
                && slots.getDurationMillis() == null) {
            debugInfo.addMissingSlot("duration");
            return AssistantPlanningResult.needsClarification(AssistantErrorCode.MISSING_DURATION,
                    "For how long? For example: \"for 2 hours\" or \"for 30 minutes\".",
                    debugInfo);
        }
        if ((intent == AssistantIntent.SCHEDULE_BLOCK_APP || intent == AssistantIntent.SCHEDULE_BLOCK_CATEGORY)
                && slots.getTimeRange() == null) {
            debugInfo.addMissingSlot("time_range");
            return AssistantPlanningResult.needsClarification(AssistantErrorCode.MISSING_TIME_RANGE,
                    "What time window? For example: \"from 6 PM to 9 PM\", or \"tonight\".",
                    debugInfo);
        }
        return null;
    }

    private String verbPhraseFor(AssistantIntent intent) {
        switch (intent) {
            case BLOCK_APP_NOW:
            case BLOCK_APP_TEMPORARY:
            case SCHEDULE_BLOCK_APP:
                return "block an app";
            case UNBLOCK_APP:
                return "unblock an app";
            case UNBLOCK_ALL_APPS:
                return "unblock all apps";
            case REMOVE_APP_TIMER:
                return "remove an app timer";
            case SET_APP_TIMER:
                return "set a timer on an app";
            case EXPLAIN_APP_BLOCK:
                return "explain a block";
            default:
                return "do that";
        }
    }

    private boolean requiresApp(AssistantIntent intent) {
        return intent == AssistantIntent.BLOCK_APP_NOW
                || intent == AssistantIntent.BLOCK_APP_TEMPORARY
                || intent == AssistantIntent.SCHEDULE_BLOCK_APP
                || intent == AssistantIntent.UNBLOCK_APP
                || intent == AssistantIntent.REMOVE_APP_TIMER
                || intent == AssistantIntent.SET_APP_TIMER
                || intent == AssistantIntent.EXPLAIN_APP_BLOCK;
    }

    private boolean requiresCategory(AssistantIntent intent) {
        return intent == AssistantIntent.BLOCK_CATEGORY_NOW
                || intent == AssistantIntent.SCHEDULE_BLOCK_CATEGORY;
    }

    private AssistantPlan buildPlan(AssistantPlanningRequest request, AssistantIntent intent, ExtractedSlots slots) {
        List<ActionPlan> actions = new ArrayList<>();
        AssistantActionType actionType = actionTypeFor(intent);

        // Multi-target: emit one action per resolved app so "block youtube and
        // instagram" produces two targets. Non-app intents collapse to a single
        // action carrying the category / mode / routine slot.
        List<String> targets = slots.getAppTargets();
        boolean addedAny = false;
        for (String app : targets) {
            ActionPlan action = new ActionPlan(actionType);
            action.addTarget(app);
            actions.add(action);
            addedAny = true;
        }
        if (!addedAny) {
            ActionPlan action = new ActionPlan(actionType);
            action.addTarget(slots.getCategoryName());
            action.addTarget(slots.getModeName());
            action.addTarget(slots.getRoutineName());
            actions.add(action);
        }

        RiskLevel riskLevel = riskFor(intent, slots);
        boolean requiresConfirmation = intent != AssistantIntent.QUERY_USAGE
                && intent != AssistantIntent.QUERY_ACTIVE_RULES
                && intent != AssistantIntent.EXPLAIN_APP_BLOCK;

        return new AssistantPlan(intent, request.getParentId(), request.getSelectedChildId(),
                request.getSelectedChildName(), actions, slots, new ArrayList<>(), riskLevel,
                summaryFor(intent, slots), requiresConfirmation);
    }

    /**
     * Conversational intents produce a friendly info reply instead of a control
     * plan. Returns null for non-conversational intents so normal planning runs.
     */
    private AssistantPlanningResult handleConversational(AssistantIntent intent, ParserDebugInfo debugInfo) {
        switch (intent) {
            case GREETING:
                return AssistantPlanningResult.info(
                        "Hi! I'm your Sentinel assistant. I can block apps, set timers, "
                                + "schedule blocks, or show usage. What would you like to do?",
                        debugInfo);
            case HELP:
                return AssistantPlanningResult.info(
                        "Here's what I can do:\n"
                                + "• Block an app: \"block YouTube\"\n"
                                + "• Temporary block: \"block Instagram for 2 hours\"\n"
                                + "• Schedule: \"block games from 6 PM to 9 PM\"\n"
                                + "• Timer: \"set a 30-minute limit on TikTok\"\n"
                                + "• Usage: \"show today's usage\"\n"
                                + "• Undo: \"undo last change\"",
                        debugInfo);
            case THANKS:
                return AssistantPlanningResult.info("You're welcome! Anything else?", debugInfo);
            case AFFIRM:
                return AssistantPlanningResult.info(
                        "Tap the Confirm button on the card above to apply it.", debugInfo);
            case DENY:
                return AssistantPlanningResult.info("Okay, cancelled. Let me know if you need anything else.",
                        debugInfo);
            default:
                return null;
        }
    }

    private AssistantActionType actionTypeFor(AssistantIntent intent) {
        switch (intent) {
            case BLOCK_APP_NOW:
            case BLOCK_APP_TEMPORARY:
            case SCHEDULE_BLOCK_APP:
                return AssistantActionType.BLOCK_APP;
            case UNBLOCK_APP:
                return AssistantActionType.UNBLOCK_APP;
            case UNBLOCK_ALL_APPS:
                return AssistantActionType.UNBLOCK_ALL_APPS;
            case BLOCK_CATEGORY_NOW:
            case SCHEDULE_BLOCK_CATEGORY:
                return AssistantActionType.BLOCK_CATEGORY;
            case SET_APP_TIMER:
                return AssistantActionType.SET_APP_TIMER;
            case REMOVE_APP_TIMER:
                return AssistantActionType.REMOVE_APP_TIMER;
            case PAUSE_RESTRICTIONS:
                return AssistantActionType.PAUSE_RESTRICTIONS;
            case RESUME_RESTRICTIONS:
                return AssistantActionType.RESUME_RESTRICTIONS;
            case APPLY_MODE:
                return AssistantActionType.APPLY_MODE;
            case APPLY_ROUTINE:
                return AssistantActionType.APPLY_ROUTINE;
            case UNDO_LAST_ACTION:
                return AssistantActionType.UNDO;
            default:
                return AssistantActionType.QUERY;
        }
    }

    private RiskLevel riskFor(AssistantIntent intent, ExtractedSlots slots) {
        if ("all apps".equals(slots.getCategoryName())) {
            return RiskLevel.HIGH;
        }
        if (intent == AssistantIntent.QUERY_USAGE || intent == AssistantIntent.QUERY_ACTIVE_RULES
                || intent == AssistantIntent.EXPLAIN_APP_BLOCK) {
            return RiskLevel.LOW;
        }
        return RiskLevel.MEDIUM;
    }

    private String summaryFor(AssistantIntent intent, ExtractedSlots slots) {
        String apps = joinTargets(slots.getAppTargets());
        switch (intent) {
            case BLOCK_APP_NOW:
                return "Block " + apps;
            case BLOCK_APP_TEMPORARY:
                return "Block " + apps + " temporarily";
            case SCHEDULE_BLOCK_APP:
                return "Schedule a block for " + apps;
            case UNBLOCK_ALL_APPS:
                return "Unblock all blocked apps";
            case BLOCK_CATEGORY_NOW:
                return "Block " + slots.getCategoryName();
            case SCHEDULE_BLOCK_CATEGORY:
                return "Schedule a block for " + slots.getCategoryName();
            case SET_APP_TIMER:
                return "Set an app timer for " + apps;
            case REMOVE_APP_TIMER:
                return "Remove the app timer for " + apps;
            case PAUSE_RESTRICTIONS:
                return "Pause restrictions";
            case RESUME_RESTRICTIONS:
                return "Resume restrictions";
            case EXPLAIN_APP_BLOCK:
                return "Explain why " + apps + " is blocked";
            case QUERY_USAGE:
                return "Show usage";
            case UNDO_LAST_ACTION:
                return "Undo the last assistant action";
            default:
                return intent.name();
        }
    }

    /** "youtube and instagram" for multi-target, falling back to single name. */
    private String joinTargets(List<String> targets) {
        if (targets.isEmpty()) {
            return "the selected app";
        }
        if (targets.size() == 1) {
            return targets.get(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) {
                sb.append(i == targets.size() - 1 ? " and " : ", ");
            }
            sb.append(targets.get(i));
        }
        return sb.toString();
    }

    private int lastIndexOfWord(String text, String word, int beforeIndex) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        int lastIndex = -1;
        while (matcher.find()) {
            if (matcher.start() < beforeIndex) {
                lastIndex = matcher.start();
            } else {
                break;
            }
        }
        return lastIndex;
    }

    private String detectConflictingActions(String normalized, ExtractedSlots slots) {
        if (slots == null || slots.getAppTargets().isEmpty()) {
            return null;
        }

        for (String app : slots.getAppTargets()) {
            String lowerApp = app.toLowerCase(java.util.Locale.US);
            int index = 0;
            boolean targetedByBlock = false;
            boolean targetedByUnblock = false;

            while ((index = normalized.indexOf(lowerApp, index)) != -1) {
                int lastBlock = lastIndexOfWord(normalized, "block", index);
                int lastUnblock = lastIndexOfWord(normalized, "unblock", index);

                if (lastBlock != -1) {
                    boolean otherAppBetween = false;
                    for (String otherApp : slots.getAppTargets()) {
                        String lowerOther = otherApp.toLowerCase(java.util.Locale.US);
                        if (!lowerOther.equals(lowerApp)) {
                            int otherIdx = normalized.indexOf(lowerOther, lastBlock);
                            if (otherIdx != -1 && otherIdx < index) {
                                otherAppBetween = true;
                                break;
                            }
                        }
                    }
                    if (!otherAppBetween) {
                        targetedByBlock = true;
                    }
                }

                if (lastUnblock != -1) {
                    boolean otherAppBetween = false;
                    for (String otherApp : slots.getAppTargets()) {
                        String lowerOther = otherApp.toLowerCase(java.util.Locale.US);
                        if (!lowerOther.equals(lowerApp)) {
                            int otherIdx = normalized.indexOf(lowerOther, lastUnblock);
                            if (otherIdx != -1 && otherIdx < index) {
                                otherAppBetween = true;
                                break;
                            }
                        }
                    }
                    if (!otherAppBetween) {
                        targetedByUnblock = true;
                    }
                }

                index += lowerApp.length();
            }

            if (targetedByBlock && targetedByUnblock) {
                String displayName = app.substring(0, 1).toUpperCase(java.util.Locale.US) + app.substring(1);
                return "I found conflicting actions for " + displayName + ": block and unblock. Which would you like to do?";
            }
        }

        return null;
    }
}
