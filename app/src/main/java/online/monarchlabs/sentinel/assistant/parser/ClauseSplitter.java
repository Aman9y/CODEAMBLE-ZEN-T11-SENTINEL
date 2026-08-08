package online.monarchlabs.sentinel.assistant.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a compound instruction into independent sub-clauses so each clause
 * can be parsed as a separate command. A split happens only when a coordinating
 * conjunction ({@code but}, {@code and then}, {@code also}, {@code then})
 * appears <em>between two different</em> canonical action verbs.
 *
 * <p>Example: {@code "unblock youtube but block instagram"} produces two
 * clauses, while {@code "block youtube and instagram"} stays as one because
 * the verb context does not change.</p>
 */
public class ClauseSplitter {

    private static final List<String> ACTION_VERBS = Arrays.asList(
            "block", "unblock", "limit", "query", "pause", "resume", "apply");

    /**
     * Conjunctions that may signal a clause boundary. Ordered longest-first so
     * {@code "and then"} is tried before a bare {@code "and"} would be (though
     * bare {@code "and"} is intentionally absent — it usually connects app
     * names, not clauses).
     */
    private static final List<String> CONJUNCTIONS = Arrays.asList(
            "and then", "but", "also", "then");

    /**
     * Splits {@code normalizedInput} into one or more independent clauses.
     *
     * @param normalizedInput a sentence already passed through
     *                        {@link TextNormalizer}.
     * @return an unmodifiable list of 1+ clauses.
     */
    public static List<String> split(String normalizedInput) {
        if (normalizedInput == null || normalizedInput.trim().isEmpty()) {
            return Collections.singletonList("");
        }
        String input = normalizedInput.trim();

        List<String> clauses = new ArrayList<>();
        clauses.add(input);

        for (String conj : CONJUNCTIONS) {
            List<String> next = new ArrayList<>();
            for (String clause : clauses) {
                next.addAll(trySplit(clause, conj));
            }
            clauses = next;
        }

        // Trim and drop empties.
        List<String> result = new ArrayList<>();
        for (String c : clauses) {
            String trimmed = c.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableList(result.isEmpty()
                ? Collections.singletonList(input) : result);
    }

    /**
     * Attempt to split a single clause on the given conjunction. A split only
     * happens when the leading verb of the text before the conjunction differs
     * from the leading verb of the text after it.
     */
    private static List<String> trySplit(String clause, String conjunction) {
        // Build a pattern that finds the conjunction as a whole word.
        Pattern pattern = Pattern.compile(
                "\\b" + Pattern.quote(conjunction) + "\\b");
        Matcher matcher = pattern.matcher(clause);

        int searchFrom = 0;
        List<String> parts = new ArrayList<>();
        String remaining = clause;

        while (matcher.find(searchFrom)) {
            String before = clause.substring(0, matcher.start()).trim();
            String after = clause.substring(matcher.end()).trim();

            String verbBefore = findLastVerb(before);
            String verbAfter = findFirstVerb(after);

            if (verbBefore != null && verbAfter != null
                    && !verbBefore.equals(verbAfter)) {
                parts.add(before);
                remaining = after;
                // Reset matcher on the remainder to find further splits.
                matcher = pattern.matcher(remaining);
                searchFrom = 0;
                // Adjust clause reference for subsequent iterations.
                clause = remaining;
            } else {
                searchFrom = matcher.end();
            }
        }
        parts.add(remaining);
        return parts;
    }

    /** Finds the first action verb that appears in the text. */
    private static String findFirstVerb(String text) {
        int earliest = Integer.MAX_VALUE;
        String found = null;
        for (String verb : ACTION_VERBS) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(verb) + "\\b")
                    .matcher(text);
            if (m.find() && m.start() < earliest) {
                earliest = m.start();
                found = verb;
            }
        }
        return found;
    }

    /** Finds the last action verb that appears in the text. */
    private static String findLastVerb(String text) {
        int latest = -1;
        String found = null;
        for (String verb : ACTION_VERBS) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(verb) + "\\b")
                    .matcher(text);
            while (m.find()) {
                if (m.start() > latest) {
                    latest = m.start();
                    found = verb;
                }
            }
        }
        return found;
    }
}
