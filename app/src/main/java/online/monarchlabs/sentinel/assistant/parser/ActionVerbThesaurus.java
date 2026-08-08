package online.monarchlabs.sentinel.assistant.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the many ways a parent can phrase a control action onto a small set of
 * canonical verbs the rest of the parser understands: "block", "unblock",
 * "limit", "query", "pause", "resume", "apply".
 *
 * <p>Centralising this vocabulary is what lets the assistant feel forgiving
 * ("ban", "lock", "rok do" all behave like "block") instead of keyword-strict.
 * Everything here is offline, deterministic, and dependency-free.</p>
 */
public class ActionVerbThesaurus {

    /** A single (alias -> canonicalVerb) entry consumed by the normalizer. */
    public static final class VerbAlias {
        public final String alias;
        public final String canonical;

        public VerbAlias(String alias, String canonical) {
            this.alias = alias;
            this.canonical = canonical;
        }
    }

    private final List<VerbAlias> aliases;

    public ActionVerbThesaurus() {
        Map<String, String> map = new LinkedHashMap<>();
        addAll(map, "block",
                "block", "blok", "blck", "ban", "bar", "stop", "prevent", "lock", "restrict",
                "disable", "freeze", "hide", "turn off", "keep off", "no more",
                // Hinglish
            "band karo", "band kar", "band rakho", "mat chalao",
            "rok do", "roko", "rok lo", "mana karo", "band karo na");
        addAll(map, "unblock",
                "unblock", "unbock", "unbloack", "unblok", "allow", "permit", "enable", "release", "turn on",
                // Hinglish
            "kholo", "khol do", "khol do na", "chalu karo", "chalao", "anumat do");
        addAll(map, "limit",
            "limit", "cap", "max", "maximum", "timer", "timmer", "laga do", "set karo", "time limit", "time");
        addAll(map, "query",
                "show", "whats", "what is", "what was", "how much", "how long",
                "tell me", "check", "kitna", "kitni", "kaisa", "kaisi");
        addAll(map, "pause", "pause");
        addAll(map, "resume", "resume", "continue");
        addAll(map, "apply", "apply", "activate", "switch to");

        // Longest alias first so multi-word phrases (e.g. "turn off") are matched
        // before their single-word substrings (e.g. "off").
        List<VerbAlias> sorted = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            sorted.add(new VerbAlias(entry.getKey(), entry.getValue()));
        }
        sorted.sort(Comparator.comparingInt((VerbAlias a) -> a.alias.length()).reversed());
        this.aliases = Collections.unmodifiableList(sorted);
    }

    private static void addAll(Map<String, String> map, String canonical, String... aliases) {
        for (String alias : aliases) {
            // Never overwrite a canonical token with itself, and never map a
            // token that is already canonical under another verb.
            if (!alias.equals(canonical) && !map.containsKey(alias)) {
                map.put(alias, canonical);
            }
        }
    }

    /** Alias entries ordered longest-first, safe to apply in sequence. */
    public List<VerbAlias> aliases() {
        return aliases;
    }

    /** True if the given token is one of the canonical verbs. */
    public boolean isCanonical(String token) {
        if (token == null) {
            return false;
        }
        for (VerbAlias alias : aliases) {
            if (alias.canonical.equals(token)) {
                return true;
            }
        }
        return false;
    }
}
