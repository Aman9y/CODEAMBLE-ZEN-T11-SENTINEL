package online.monarchlabs.sentinel.assistant.parser;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts slots from the normalized sentence. Phase 3 changes the app-name
 * strategy: instead of grabbing "whatever word follows the verb", we scan the
 * whole sentence against an {@link AppCatalog} (exact then fuzzy) so the target
 * is found regardless of phrasing or word order ("keep my kid off youtube
 * tonight" -&gt; youtube).
 */
public class SlotExtractor {
    private static final List<String> CATEGORIES = Arrays.asList(
            "social media", "study apps", "video apps", "music apps", "shopping apps",
            "all apps", "games", "entertainment", "messaging", "browsers"
    );
    /** Words that delimit a target phrase when no catalog app matched. */
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(Arrays.asList(
            "for", "from", "every", "limit", "to", "tonight", "morning", "evening",
            "night", "afternoon", "today", "daily", "now", "and", "except",
            "until", "during", "weekdays", "weekends", "per",
            "the", "a", "an", "this", "that", "these", "those",
            "block", "unblock", "set", "pause", "resume", "query", "apply", "explain", "why", "is", "are"));

    private final TimeParser timeParser;
    private final AppCatalog appCatalog;

    public SlotExtractor(TimeParser timeParser) {
        this(timeParser, new SeedAppCatalog());
    }

    public SlotExtractor(TimeParser timeParser, AppCatalog appCatalog) {
        this.timeParser = timeParser;
        this.appCatalog = appCatalog == null ? new SeedAppCatalog() : appCatalog;
    }

    public ExtractedSlots extract(String normalizedInput) {
        String input = normalizedInput == null ? "" : normalizedInput;
        ExtractedSlots slots = new ExtractedSlots();
        slots.setDurationMillis(timeParser.parseDurationMillis(input));
        slots.setTimeRange(timeParser.parseTimeRange(input));
        slots.setRepeatRule(timeParser.parseRepeatRule(input));
        slots.setAmbiguousTime(timeParser.hasAmbiguousRange(input));
        slots.setCategoryName(extractCategory(input));

        // Apps: catalog scan (exact + fuzzy)
        List<String> matched = scanForApps(input, slots.getCategoryName());
        for (String app : matched) {
            slots.addAppTarget(app);
        }

        // Also extract fallback apps (e.g. apps not in seed catalog)
        String fallbackSeq = extractAppNameFallback(input, slots.getCategoryName());
        if (fallbackSeq != null) {
            String[] words = fallbackSeq.split("\\s+");
            for (String word : words) {
                if (word.length() < 3 || STOP_WORDS.contains(word)) {
                    continue;
                }
                boolean alreadyMatched = false;
                for (String app : slots.getAppTargets()) {
                    if (app.equalsIgnoreCase(word) || FuzzyMatcher.matches(word, app)) {
                        alreadyMatched = true;
                        break;
                    }
                }
                if (!alreadyMatched) {
                    slots.addAppTarget(word);
                }
            }
        }

        extractException(input, slots);
        extractModeOrRoutine(input, slots);
        return slots;
    }

    private String extractCategory(String input) {
        for (String category : CATEGORIES) {
            if (input.contains(category)) {
                return category;
            }
        }
        return null;
    }

    /**
     * Walk the catalog and collect every app that appears in the sentence
     * (exact match first, then fuzzy). When a category is named we skip app
     * scanning so "block games" doesn't also grab a stray app token.
     */
    private List<String> scanForApps(String input, String categoryName) {
        Set<String> found = new LinkedHashSet<>();
        if (categoryName != null) {
            return Arrays.asList();
        }
        // Exact match first (preserves priority and is cheapest).
        for (String app : appCatalog.knownApps()) {
            if (containsWord(input, app)) {
                found.add(app);
            }
        }
        // Fuzzy fallback for each token individually, regardless of whether exact matches were found.
        String[] tokens = input.split("\\s+");
        for (String token : tokens) {
            if (token.length() < 3 || STOP_WORDS.contains(token)) {
                continue;
            }
            // Check if this token is already matched by an exact app match.
            boolean alreadyMatched = false;
            for (String matchedApp : found) {
                if (containsWord(matchedApp, token) || containsWord(token, matchedApp)) {
                    alreadyMatched = true;
                    break;
                }
            }
            if (alreadyMatched) {
                continue;
            }
            for (String app : appCatalog.knownApps()) {
                if (app.contains(" ") || app.length() <= 3) {
                    continue; // multi-word / short apps only matched exactly above
                }
                if (FuzzyMatcher.matches(token, app)) {
                    found.add(app);
                    break;
                }
            }
        }
        return Arrays.asList(found.toArray(new String[0]));
    }

    private boolean containsWord(String input, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(input).find();
    }

    /**
     * Last-resort app extraction: capture the token sequence after the action
     * verb until a stop word. Handles apps not in the seed catalog (e.g. a
     * locally installed game the parent names).
     */
    private String extractAppNameFallback(String input, String categoryName) {
        if (categoryName != null) {
            return null;
        }
        Matcher m = Pattern.compile(
                "\\b(?:block|unblock|ban|stop|lock|restrict|why is|set)\\s+([a-z0-9 ]+?)(?:\\s+(?:for|from|every|limit|blocked|to|tonight|morning|evening|night|today|daily|now|except)\\b|$)")
                .matcher(input);
        if (m.find()) {
            return cleanTarget(m.group(1));
        }
        Matcher suffix = Pattern.compile("\\b([a-z0-9 ]+?)\\s+(?:block|unblock)\\b").matcher(input);
        if (suffix.find()) {
            return cleanTarget(suffix.group(1));
        }
        Matcher limit = Pattern.compile("\\b([a-z0-9 ]+?)\\s+limit\\b").matcher(input);
        if (limit.find()) {
            return cleanTarget(limit.group(1));
        }
        return null;
    }

    private String cleanTarget(String target) {
        if (target == null) {
            return null;
        }
        String cleaned = target.replaceAll("\\bapp\\b", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private void extractException(String input, ExtractedSlots slots) {
        Matcher matcher = Pattern.compile("\\bexcept\\s+([a-z0-9 ]+?)(?:\\s+(?:from|for|every|until)\\b|$)")
                .matcher(input);
        if (matcher.find()) {
            String[] names = matcher.group(1).split("\\s+and\\s+|\\s*,\\s*");
            for (String name : names) {
                slots.addException(cleanTarget(name));
            }
        }
    }

    private void extractModeOrRoutine(String input, ExtractedSlots slots) {
        Matcher matcher = Pattern.compile("\\bapply\\s+([a-z0-9 ]+?)(?:\\s+(?:until|for|from)\\b|$)").matcher(input);
        if (!matcher.find()) {
            return;
        }
        String name = matcher.group(1).trim();
        if (name.endsWith("mode")) {
            slots.setModeName(name);
        } else {
            slots.setRoutineName(name);
        }
    }
}
