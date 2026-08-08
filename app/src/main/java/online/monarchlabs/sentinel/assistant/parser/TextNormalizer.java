package online.monarchlabs.sentinel.assistant.parser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class TextNormalizer {
    private static final String STOP_CHARS_REGEX = "[\\p{Punct}&&[^:]]+";

    /**
     * Substitution table applied AFTER the verb thesaurus. These are not action
     * verbs (so they don't belong in ActionVerbThesaurus) — they normalise apps,
     * times, and Hinglish shorthand so downstream regex parsing stays simple.
     */
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final ActionVerbThesaurus verbThesaurus;

    public TextNormalizer() {
        this(new ActionVerbThesaurus());
    }

    public TextNormalizer(ActionVerbThesaurus verbThesaurus) {
        this.verbThesaurus = verbThesaurus == null ? new ActionVerbThesaurus() : verbThesaurus;

        // App shorthand (longest first inside normalize to avoid partial clashes)
        aliases.put("youtube", "youtube");
        aliases.put("you tube", "youtube");
        aliases.put("yt", "youtube");
        aliases.put("insta", "instagram");
        aliases.put("ig", "instagram");
        aliases.put("wa", "whatsapp");

        // Hinglish duration / time shorthand
        aliases.put("aadha ghanta", "30 minutes");
        aliases.put("adhaa ghanta", "30 minutes");
        aliases.put("ghanta", "hour");
        aliases.put("ghante", "hours");
        aliases.put("min", "minutes");
        aliases.put("minuets", "minutes");
        aliases.put("minuet", "minute");
        aliases.put("roz", "every day");
        aliases.put("har roj", "every day");
        aliases.put("rozana", "every day");
        aliases.put("har din", "every day");
        aliases.put("weekdays", "every weekday");
        aliases.put("working days", "every weekday");
        aliases.put("shaam", "evening");
        aliases.put("subah", "morning");
        aliases.put("dopahar", "afternoon");
        aliases.put("raat", "night");
        aliases.put("ke liye", "for");
        aliases.put("k liye", "for");
        aliases.put("playstore", "play store");
        aliases.put("appstore", "app store");
    }

    public String normalize(String rawInput, ParserDebugInfo debugInfo) {
        if (rawInput == null) {
            return "";
        }
        // Collapse common contractions BEFORE punctuation stripping turns
        // "don't" into "don t". Lowercased first so the map is case-insensitive.
        String normalized = rawInput.toLowerCase(Locale.US);
        normalized = normalized.replaceAll("\\bdon\\s*'?t\\b", "dont")
                .replaceAll("\\bcan\\s*'?t\\b", "cant")
                .replaceAll("\\bwon\\s*'?t\\b", "wont")
                .replaceAll("\\bisn\\s*'?t\\b", "isnt")
                .replaceAll("\\baren\\s*'?t\\b", "arent")
                .replaceAll("\\bdo\\s*not\\b", "dont");
        normalized = normalized.replaceAll(STOP_CHARS_REGEX, " ")
                .replaceAll("\\s+", " ")
                .trim();

        // Verb synonyms first (multi-word, longest-first), then app/time aliases.
        for (ActionVerbThesaurus.VerbAlias alias : verbThesaurus.aliases()) {
            normalized = applyAlias(normalized, alias.alias, alias.canonical, debugInfo);
        }
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            normalized = applyAlias(normalized, entry.getKey(), entry.getValue(), debugInfo);
        }
        return normalized;
    }

    private String applyAlias(String input, String alias, String replacement, ParserDebugInfo debugInfo) {
        String replaced = input.replaceAll("\\b" + Pattern.quote(alias) + "\\b", replacement);
        if (!replaced.equals(input)) {
            if (debugInfo != null && !alias.equals(replacement)) {
                debugInfo.addMatchedAlias(alias + " -> " + replacement);
            }
            return replaced.replaceAll("\\s+", " ").trim();
        }
        return input;
    }

    /** Exposed so intent detection can consult the canonical-verb set. */
    public ActionVerbThesaurus verbThesaurus() {
        return verbThesaurus;
    }
}
