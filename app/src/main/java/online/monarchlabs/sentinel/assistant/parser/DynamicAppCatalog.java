package online.monarchlabs.sentinel.assistant.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * App catalog that merges the built-in {@link SeedAppCatalog} with a runtime
 * list of apps actually installed on the child's device. Installed names are
 * lowercased and de-duplicated; seed apps always appear first.
 */
public class DynamicAppCatalog implements AppCatalog {

    private final List<String> apps;
    private final SeedAppCatalog seed;

    /**
     * @param installedAppNames nullable list of app names reported by the
     *                          device at runtime. {@code null} or empty is
     *                          perfectly fine — the seed catalog is always
     *                          included.
     */
    public DynamicAppCatalog(List<String> installedAppNames) {
        this.seed = new SeedAppCatalog();
        LinkedHashSet<String> set = new LinkedHashSet<>(seed.knownApps());
        if (installedAppNames != null) {
            for (String name : installedAppNames) {
                if (name != null && !name.trim().isEmpty()) {
                    set.add(name.toLowerCase(Locale.US).trim());
                }
            }
        }
        this.apps = Collections.unmodifiableList(new ArrayList<>(set));
    }

    @Override
    public List<String> knownApps() {
        return apps;
    }

    @Override
    public List<Alias> aliases() {
        return seed.aliases();
    }
}
