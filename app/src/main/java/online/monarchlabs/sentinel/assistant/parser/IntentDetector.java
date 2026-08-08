package online.monarchlabs.sentinel.assistant.parser;

import java.util.LinkedHashMap;
import java.util.Map;

import online.monarchlabs.sentinel.assistant.core.AssistantIntent;

/**
 * Scores the normalized input against each known intent using the canonical
 * verbs from {@link ActionVerbThesaurus} plus slot signals (time range,
 * duration, category). The highest-scoring intent wins instead of the first
 * matching keyword, which makes the detector forgiving of word order and
 * phrasing ("ban youtube tonight" scores BLOCK+SCHEDULE even though it never
 * contains the literal word "block").
 */
public class IntentDetector {
    private static final float WIN_THRESHOLD = 0.35f;

    private static final java.util.Set<String> CATEGORIES = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
            "social media", "study apps", "video apps", "music apps", "shopping apps",
            "all apps", "games", "entertainment", "messaging", "browsers"));

    private final ActionVerbThesaurus thesaurus;

    public IntentDetector() {
        this(new ActionVerbThesaurus());
    }

    public IntentDetector(ActionVerbThesaurus thesaurus) {
        this.thesaurus = thesaurus == null ? new ActionVerbThesaurus() : thesaurus;
    }

    public IntentResult detect(String normalizedInput) {
        String input = normalizedInput == null ? "" : normalizedInput;
        boolean isNegated = isNegated(input);

        // Conversational intents are checked first: a bare greeting or "help"
        // must never be misread as a control command.
        IntentResult conversational = detectConversational(input);
        if (conversational != null) {
            return conversational;
        }

        // Fixed-signal intents: these match phrases that aren't pure verbs.
        if (containsWord(input, "undo") || input.contains("last change wapas")) {
            return result(AssistantIntent.UNDO_LAST_ACTION, 0.95f);
        }
        if (containsWord(input, "why") && containsWord(input, "blocked")) {
            return result(AssistantIntent.EXPLAIN_APP_BLOCK, 0.9f);
        }
        if (containsWord(input, "unblock")
                && (containsWord(input, "all apps")
                || containsWord(input, "all the apps")
                || containsWord(input, "everything")
                || containsWord(input, "all"))) {
            return result(AssistantIntent.UNBLOCK_ALL_APPS, 0.94f);
        }
        if (anyWord(input,
            "remove timer", "delete timer", "clear timer", "cancel timer",
            "remove the timer", "delete the timer", "clear the timer",
            "remove limit", "delete limit", "clear limit", "cancel limit",
            "remove the limit", "delete the limit", "clear the limit", "cancel the limit")) {
            return result(AssistantIntent.REMOVE_APP_TIMER, 0.93f);
        }
        if (anyWord(input, "show usage", "today usage", "used most", "screen time", "kitni der")) {
            return result(AssistantIntent.QUERY_USAGE, 0.9f);
        }
        if (anyWord(input, "active rules", "rules active", "current rules", "what is active")) {
            return result(AssistantIntent.QUERY_ACTIVE_RULES, 0.9f);
        }
        if (containsWord(input, "resume") || containsWord(input, "continue")) {
            return result(AssistantIntent.RESUME_RESTRICTIONS, 0.92f);
        }
        if (containsWord(input, "pause")) {
            return result(AssistantIntent.PAUSE_RESTRICTIONS, 0.92f);
        }

        // Mode/routine application: "apply exam mode", "activate bedtime mode", ...
        String modeName = extractModeName(input);
        if (modeName != null) {
            AssistantIntent intent = modeName.endsWith("mode")
                    ? AssistantIntent.APPLY_MODE
                    : AssistantIntent.APPLY_ROUTINE;
            return result(intent, 0.88f);
        }

        // Verbal intents: score BLOCK vs UNBLOCK vs TIMER vs QUERY.
        Map<AssistantIntent, Float> scores = new LinkedHashMap<>();
        addScore(scores, AssistantIntent.UNBLOCK_APP, verbScore(input, "unblock", 0.9f));
        addScore(scores, AssistantIntent.SET_APP_TIMER, verbScore(input, "limit", 0.7f));
        addScore(scores, AssistantIntent.QUERY_USAGE, queryScore(input));

        // Negation flips a detected block into an unblock intent ("don't block X").
        float blockScore = verbScore(input, "block", 0.85f);
        if (isNegated && blockScore > 0) {
            addScore(scores, AssistantIntent.UNBLOCK_APP, blockScore);
        } else {
            addScore(scores, AssistantIntent.BLOCK_APP_NOW, blockScore);
        }

        AssistantIntent best = null;
        float bestScore = WIN_THRESHOLD;
        for (Map.Entry<AssistantIntent, Float> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }

        if (best == null) {
            return result(AssistantIntent.UNKNOWN, 0f);
        }
        return result(refineBlockIntent(best, input), bestScore);
    }

    /**
     * A raw "block" winner is specialised into temporary/scheduled/category
     * variants based on whether duration, time range, or a category is present.
     */
    private AssistantIntent refineBlockIntent(AssistantIntent intent, String input) {
        if (intent != AssistantIntent.BLOCK_APP_NOW) {
            return intent;
        }
        boolean hasCategory = containsCategory(input);
        boolean hasRange = TimeParser.hasRelativeTime(input) || hasMeridiemRange(input) || hasNumericRange(input);
        boolean hasDuration = TimeParser.hasDuration(input);

        if (hasRange && hasCategory) {
            return AssistantIntent.SCHEDULE_BLOCK_CATEGORY;
        }
        if (hasRange) {
            return AssistantIntent.SCHEDULE_BLOCK_APP;
        }
        if (hasDuration) {
            return AssistantIntent.BLOCK_APP_TEMPORARY;
        }
        if (hasCategory) {
            return AssistantIntent.BLOCK_CATEGORY_NOW;
        }
        return AssistantIntent.BLOCK_APP_NOW;
    }

    private float verbScore(String input, String canonicalVerb, float base) {
        // Count occurrences so "block" earns a little more than a single match.
        int hits = countWord(input, canonicalVerb);
        if (hits == 0) {
            return 0f;
        }
        return base + Math.min(hits - 1, 2) * 0.05f;
    }

    private float queryScore(String input) {
        if (hasCanonicalVerb(input, "query")) {
            return 0.8f;
        }
        return 0f;
    }

    private boolean hasCanonicalVerb(String input, String verb) {
        return containsWord(input, verb);
    }

    private String extractModeName(String input) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(?:apply|activate|switch to)\\s+([a-z0-9 ]+?)(?:\\s+(?:until|for|from)\\b|$)")
                .matcher(input);
        if (!m.find()) {
            return null;
        }
        String name = m.group(1).trim();
        return name.isEmpty() ? null : name;
    }

    private boolean containsCategory(String input) {
        for (String category : CATEGORIES) {
            if (containsWord(input, category)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMeridiemRange(String input) {
        return input.matches(".*\\b(?:from|for|between)\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)\\s+(?:to|and)\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)\\b.*")
                || input.matches(".*\\b\\d{1,2}(:\\d{2})?\\s*(am|pm)\\s+se\\s+\\d{1,2}(:\\d{2})?\\s*(am|pm)\\s*(?:tak)?\\b.*");
    }

    /** Numeric range without am/pm, e.g. "from 6 to 9" (ambiguous -> clarification). */
    private boolean hasNumericRange(String input) {
        return input.matches(".*\\b(?:from|for|between)\\s+\\d{1,2}\\s+(?:to|and)\\s+\\d{1,2}\\b.*")
                || input.matches(".*\\b\\d{1,2}\\s+se\\s+\\d{1,2}\\s*(?:tak)?\\b.*");
    }

    /** Detect negation that flips the polarity of the action verb. */
    private boolean isNegated(String input) {
        return anyWord(input, "dont block", "do not block", "never block",
                "keep unblocked", "dont restrict", "dont ban");
    }

    /**
     * Lightweight conversational intent detection (greeting / help / yes / no /
     * thanks). These never build a control plan — the planner turns them into a
     * friendly reply. Null means "not conversational, continue scoring".
     */
    private IntentResult detectConversational(String input) {
        if (anyWord(input, "help", "what can you do", "kya kar sakte ho",
                "kaise use kare", "options", "commands")) {
            return result(AssistantIntent.HELP, 0.9f);
        }
        if (anyWord(input, "thanks", "thank you", "thx", "shukriya", "dhanyawad")) {
            return result(AssistantIntent.THANKS, 0.9f);
        }
        if (anyWord(input, "yes", "yeah", "yep", "sure", "ok", "okay", "haan", "confirm", "do it")) {
            return result(AssistantIntent.AFFIRM, 0.7f);
        }
        if (anyWord(input, "no", "nope", "nahi", "cancel")) {
            return result(AssistantIntent.DENY, 0.7f);
        }
        // Greeting: short phrases like "hi", "hello", "namaste". Guarded by
        // length so "hi" doesn't fire inside a longer command.
        if (input.length() <= 12 && anyWord(input, "hi", "hello", "hey", "namaste",
                "namaskar", "good morning", "good evening")) {
            return result(AssistantIntent.GREETING, 0.85f);
        }
        return null;
    }

    private static boolean containsWord(String input, String word) {
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b")
                .matcher(input).find();
    }

    private static int countWord(String input, String word) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b").matcher(input);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    private static boolean anyWord(String input, String... words) {
        for (String word : words) {
            if (containsWord(input, word)) {
                return true;
            }
        }
        return false;
    }

    private static IntentResult result(AssistantIntent intent, float confidence) {
        return new IntentResult(intent, confidence);
    }

    private static void addScore(Map<AssistantIntent, Float> scores, AssistantIntent intent, float score) {
        if (score > 0) {
            scores.put(intent, score);
        }
    }
}
