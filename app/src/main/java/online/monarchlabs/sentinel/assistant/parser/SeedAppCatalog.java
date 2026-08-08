package online.monarchlabs.sentinel.assistant.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Built-in app catalog used until the activity feeds in the child's installed
 * apps. Covers the apps parents most commonly restrict in this region. Fully
 * static, offline, no network.
 */
public class SeedAppCatalog implements AppCatalog {

    private final List<String> apps;
    private final List<Alias> aliases;

    public SeedAppCatalog() {
        LinkedHashSet<String> set = new LinkedHashSet<>(Arrays.asList(
                "youtube", "instagram", "whatsapp", "facebook", "tiktok",
                "snapchat", "telegram", "twitter", "x", "reddit",
                "chrome", "firefox", "opera", "edge",
                "netflix", "prime video", "hotstar", "disney hotstar",
                "spotify", "youtube music", "gaana", "jiosaavn",
                "pubg", "free fire", "bgmi", "candy crush", "clash of clans",
                "amazon", "flipkart", "meesho", "myntra",
                "gmail", "outlook", "maps", "google", "chrome",
                "roblox", "drive", "photos", "play store", "app store",
                "settings", "safari", "signal", "pinterest", "threads", "discord"));
        this.apps = Collections.unmodifiableList(new ArrayList<>(set));

        List<Alias> a = new ArrayList<>();
        a.add(new Alias("yt", "youtube"));
        a.add(new Alias("you tube", "youtube"));
        a.add(new Alias("insta", "instagram"));
        a.add(new Alias("ig", "instagram"));
        a.add(new Alias("wa", "whatsapp"));
        a.add(new Alias("fb", "facebook"));
        a.add(new Alias("disney hotstar", "hotstar"));
        a.add(new Alias("playstore", "play store"));
        a.add(new Alias("appstore", "app store"));
        this.aliases = Collections.unmodifiableList(a);
    }

    @Override
    public List<String> knownApps() {
        return apps;
    }

    @Override
    public List<Alias> aliases() {
        return aliases;
    }
}
