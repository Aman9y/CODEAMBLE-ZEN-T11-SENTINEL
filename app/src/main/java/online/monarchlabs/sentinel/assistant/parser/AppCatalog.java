package online.monarchlabs.sentinel.assistant.parser;

import java.util.List;

/**
 * Source of app names the assistant can resolve free text against. The catalog
 * is intentionally an interface so the activity can later supply the child's
 * <em>actually installed</em> apps, while a seed list is used out of the box.
 */
public interface AppCatalog {

    /**
     * @return the lowercased, de-duplicated app names this catalog knows about.
     */
    List<String> knownApps();

    /**
     * @return aliases that map onto a known app (e.g. "yt" -&gt; "youtube").
     *         May be empty; alias expansion may already be handled elsewhere.
     */
    List<Alias> aliases();

    /** A (shorthand -&gt; canonical app name) pair. */
    final class Alias {
        public final String shorthand;
        public final String canonical;

        public Alias(String shorthand, String canonical) {
            this.shorthand = shorthand;
            this.canonical = canonical;
        }
    }
}
