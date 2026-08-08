package online.monarchlabs.sentinel.assistant.parser;

import java.util.Locale;

/**
 * Tiny offline fuzzy matcher based on Levenshtein edit distance, with an
 * adaptive threshold that forgives a couple of typos on long app names while
 * staying strict on short ones (so "x" never matches "twitter").
 */
public final class FuzzyMatcher {

    /**
     * @return true if candidate matches target within the allowed edit budget.
     */
    public static boolean matches(String candidate, String target) {
        if (candidate == null || target == null) {
            return false;
        }
        String a = candidate.toLowerCase(Locale.US);
        String b = target.toLowerCase(Locale.US);
        if (a.equals(b)) {
            return true;
        }
        int budget = allowedEdits(Math.max(a.length(), b.length()));
        if (budget <= 0) {
            return false;
        }
        return levenshtein(a, b) <= budget;
    }

    /** Short tokens allow 0 edits (must be exact), longer ones allow up to 2. */
    static int allowedEdits(int length) {
        if (length <= 2) {
            return 0;
        }
        if (length <= 4) {
            return 1;
        }
        return 2;
    }

    /**
     * Classic iterative Levenshtein distance with O(min(m,n)) rolling array.
     */
    static int levenshtein(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        // Ensure b is the shorter string for a smaller row.
        if (m > n) {
            String tmp = a;
            a = b;
            b = tmp;
            int t = n;
            n = m;
            m = t;
        }
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[m];
    }
}
