package online.monarchlabs.sentinel.assistant.parser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FuzzyMatcherTest {

    @Test
    public void exactMatchSucceeds() {
        assertTrue(FuzzyMatcher.matches("youtube", "youtube"));
    }

    @Test
    public void caseInsensitiveMatch() {
        assertTrue(FuzzyMatcher.matches("YouTube", "youtube"));
    }

    @Test
    public void oneTypoOnMediumWordMatches() {
        // "yutube" vs "youtube" -> 1 edit, length 7 -> budget 2 -> match
        assertTrue(FuzzyMatcher.matches("yutube", "youtube"));
    }

    @Test
    public void twoTyposOnLongWordMatches() {
        // "instgrm" vs "instagram" -> several edits, length 9 -> budget 2
        assertTrue(FuzzyMatcher.matches("instgrm", "instagram"));
    }

    @Test
    public void shortTokensRequireExactMatch() {
        // length 1 -> budget 0 -> "x" must equal "x", never matches "twitter"
        assertFalse(FuzzyMatcher.matches("x", "twitter"));
        assertTrue(FuzzyMatcher.matches("x", "x"));
    }

    @Test
    public void tooManyEditsRejected() {
        // completely different strings should not match
        assertFalse(FuzzyMatcher.matches("youtube", "facebook"));
    }

    @Test
    public void allowedEditsScalesWithLength() {
        assertEquals(0, FuzzyMatcher.allowedEdits(2));
        assertEquals(1, FuzzyMatcher.allowedEdits(4));
        assertEquals(2, FuzzyMatcher.allowedEdits(8));
    }

    @Test
    public void levenshteinDistanceCorrectness() {
        assertEquals(0, FuzzyMatcher.levenshtein("same", "same"));
        assertEquals(1, FuzzyMatcher.levenshtein("kitten", "sitten"));
        assertEquals(3, FuzzyMatcher.levenshtein("kitten", "sitting"));
        assertEquals(3, FuzzyMatcher.levenshtein("", "abc"));
    }
}
