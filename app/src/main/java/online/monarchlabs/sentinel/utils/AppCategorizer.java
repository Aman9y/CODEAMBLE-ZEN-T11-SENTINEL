package online.monarchlabs.sentinel.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart app categorization - analyzes both package name AND app name
 * for maximum accuracy
 */
public class AppCategorizer {
    private static final String TAG = "AppCategorizer";
    private static final ConcurrentHashMap<String, AppCategory> CATEGORY_CACHE =
            new ConcurrentHashMap<>();

    public enum AppCategory {
        ALL("All"),
        SOCIAL("Social"),
        ENTERTAINMENT("Entertainment"),
        GAMES("Games"),
        PRODUCTIVITY("Productivity"),
        COMMUNICATION("Communication"),
        TOOLS("Tools"),
        EDUCATION("Education"),
        SHOPPING("Shopping"),
        MEDIA("Media & Video"),
        OTHER("Other");

        private final String displayName;

        AppCategory(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Get category for an app - analyzes package name AND app name
     */
    public static AppCategory getCategory(Context context, String packageName) {
        if (context == null || packageName == null) {
            return AppCategory.OTHER;
        }

        AppCategory cached = CATEGORY_CACHE.get(packageName);
        if (cached != null) {
            return cached;
        }

        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);

            // Get actual app name that user sees
            String appName = appInfo.loadLabel(pm).toString();

            // Step 1: Try Android's native category (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int nativeCategory = appInfo.category;
                if (nativeCategory != ApplicationInfo.CATEGORY_UNDEFINED) {
                    AppCategory mapped = mapNativeCategory(nativeCategory);
                    if (mapped != AppCategory.OTHER) {
                        CATEGORY_CACHE.put(packageName, mapped);
                        return mapped;
                    }
                }
            }

            // Step 2: Analyze BOTH package name AND app name
            AppCategory category = categorizeByPatterns(packageName, appName);
            CATEGORY_CACHE.put(packageName, category);
            return category;

        } catch (PackageManager.NameNotFoundException e) {
            CATEGORY_CACHE.put(packageName, AppCategory.OTHER);
            return AppCategory.OTHER;
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
            return AppCategory.OTHER;
        }
    }

    private static AppCategory mapNativeCategory(int nativeCategory) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            switch (nativeCategory) {
                case ApplicationInfo.CATEGORY_GAME:
                    return AppCategory.GAMES;
                case ApplicationInfo.CATEGORY_AUDIO:
                case ApplicationInfo.CATEGORY_VIDEO:
                    return AppCategory.ENTERTAINMENT;
                case ApplicationInfo.CATEGORY_IMAGE:
                    return AppCategory.MEDIA;
                case ApplicationInfo.CATEGORY_SOCIAL:
                    return AppCategory.SOCIAL;
                case ApplicationInfo.CATEGORY_MAPS:
                    return AppCategory.TOOLS;
                case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                    return AppCategory.PRODUCTIVITY;
                default:
                    return AppCategory.OTHER;
            }
        }
        return AppCategory.OTHER;
    }

    /**
     * Categorize by analyzing both package name and app name
     */
    private static AppCategory categorizeByPatterns(String packageName, String appName) {
        String pkg = packageName.toLowerCase();
        String name = appName.toLowerCase();

        // === GAMES ===
        if (containsAny(pkg, "game", "games", "gaming", "puzzle", "arcade", "racing",
                "shooter", "adventure", "casino", "slot", "poker", "chess",
                "sudoku", "solitaire", "bubble", "candy", "craft", "clash",
                "battle", "war", "fight", "hero", "quest", "legend", "saga",
                "rpg", "mmo", "pubg", "fortnite", "minecraft", "roblox") ||
                containsAny(name, "game", "games", "gaming", "puzzle", "arcade", "racing",
                        "shooter", "adventure", "casino", "slot", "poker", "chess",
                        "sudoku", "solitaire", "bubble", "candy", "craft", "clash",
                        "battle", "war", "fight", "hero", "quest", "legend", "saga",
                        "free fire", "bgmi", "pubg", "fortnite", "minecraft", "roblox",
                        "temple run", "subway surf", "angry bird", "fruit ninja")) {
            return AppCategory.GAMES;
        }

        // === SOCIAL ===
        if (containsAny(pkg, "social", "facebook", "instagram", "twitter", "tiktok",
                "snapchat", "pinterest", "reddit", "tumblr", "linkedin",
                "musically", "threads", "mastodon", "truth") ||
                containsAny(name, "social", "facebook", "instagram", "twitter", "tiktok",
                        "snapchat", "pinterest", "reddit", "tumblr", "linkedin",
                        "threads", "mastodon", "truth social", "x (formerly")) {
            return AppCategory.SOCIAL;
        }

        // === COMMUNICATION ===
        if (containsAny(pkg, "whatsapp", "telegram", "messenger", "chat", "sms", "mms",
                "mail", "email", "gmail", "outlook", "yahoo", "discord",
                "skype", "zoom", "meet", "teams", "slack", "signal", "viber",
                "line", "wechat", "kakaotalk", "duo", "call", "dialer", "phone",
                "contacts", "hangouts") ||
                containsAny(name, "whatsapp", "telegram", "messenger", "chat", "sms",
                        "mail", "email", "gmail", "outlook", "yahoo", "discord",
                        "skype", "zoom", "meet", "teams", "slack", "signal", "viber",
                        "line", "wechat", "kakaotalk", "duo", "call", "dialer", "phone",
                        "contacts", "messages", "hangouts")) {
            return AppCategory.COMMUNICATION;
        }

        // === ENTERTAINMENT ===
        if (containsAny(pkg, "youtube", "netflix", "spotify", "music", "video", "player",
                "stream", "movie", "tv", "hulu", "disney", "prime", "hbo",
                "twitch", "podcast", "radio", "fm", "audio", "mp3", "media",
                "voot", "hotstar", "jio", "zee", "sony", "mx", "vlc",
                "deezer", "soundcloud", "pandora", "shazam", "tidal", "gaana",
                "wynk", "saavn", "hungama") ||
                containsAny(name, "youtube", "netflix", "spotify", "music", "video", "player",
                        "streaming", "movie", "tv", "hulu", "disney", "prime video", "hbo",
                        "twitch", "podcast", "radio", "audio", "media player",
                        "voot", "hotstar", "jio", "zee5", "sony liv", "mx player",
                        "deezer", "soundcloud", "pandora", "shazam", "gaana", "wynk",
                        "saavn", "hungama", "tune", "listen")) {
            return AppCategory.ENTERTAINMENT;
        }

        // === SHOPPING ===
        if (containsAny(pkg, "shop", "store", "amazon", "flipkart", "ebay", "walmart",
                "alibaba", "aliexpress", "wish", "shein", "myntra", "ajio",
                "meesho", "snapdeal", "paytm", "mall", "buy", "cart", "deal",
                "offer", "coupon", "shopping", "ecommerce", "olx", "quikr",
                "zomato", "swiggy", "uber", "ola", "lyft", "food", "delivery",
                "dunzo", "blinkit", "instamart", "grofers") ||
                containsAny(name, "shop", "store", "amazon", "flipkart", "ebay", "walmart",
                        "alibaba", "aliexpress", "wish", "shein", "myntra", "ajio",
                        "meesho", "snapdeal", "paytm mall", "deal", "offer", "coupon",
                        "shopping", "olx", "quikr", "zomato", "swiggy", "uber eats",
                        "food delivery", "dunzo", "blinkit", "instamart", "grocery")) {
            return AppCategory.SHOPPING;
        }

        // === EDUCATION ===
        if (containsAny(pkg, "learn", "edu", "education", "school", "college", "study",
                "tutor", "course", "class", "academy", "student", "exam",
                "quiz", "test", "dictionary", "translate", "language",
                "duolingo", "khan", "byju", "vedantu", "unacademy", "coursera",
                "udemy", "skillshare", "udacity", "brilliant", "toppr") ||
                containsAny(name, "learn", "education", "school", "college", "study",
                        "tutor", "course", "class", "academy", "student", "exam",
                        "quiz", "test", "dictionary", "translate", "language",
                        "duolingo", "khan academy", "byju", "vedantu", "unacademy",
                        "coursera", "udemy", "skillshare", "udacity", "brilliant",
                        "math", "science", "english", "hindi", "grammar")) {
            return AppCategory.EDUCATION;
        }

        // === PRODUCTIVITY ===
        if (containsAny(pkg, "office", "docs", "document", "word", "excel", "sheet",
                "powerpoint", "slide", "note", "todo", "task", "calendar",
                "schedule", "remind", "planner", "organize", "drive", "cloud",
                "dropbox", "onedrive", "notion", "evernote", "trello", "asana",
                "monday", "todoist", "tick", "any.do", "wunderlist", "keep",
                "pdf", "scan", "sign", "fax") ||
                containsAny(name, "office", "docs", "document", "word", "excel", "sheet",
                        "powerpoint", "slide", "note", "todo", "task", "calendar",
                        "schedule", "reminder", "planner", "organizer", "drive", "cloud",
                        "dropbox", "onedrive", "notion", "evernote", "trello", "asana",
                        "todoist", "keep", "pdf", "scan", "sign", "productivity")) {
            return AppCategory.PRODUCTIVITY;
        }

        // === MEDIA & PHOTO ===
        if (containsAny(pkg, "photo", "camera", "gallery", "image", "pic", "snap",
                "edit", "filter", "retouch", "collage", "frame", "album",
                "lightroom", "photoshop", "canva", "picsart", "vsco", "snapseed",
                "remini", "facetune", "beautycam", "b612", "retrica", "prisma") ||
                containsAny(name, "photo", "camera", "gallery", "image", "picture", "snap",
                        "editor", "filter", "retouch", "collage", "frame", "album",
                        "lightroom", "photoshop", "canva", "picsart", "vsco", "snapseed",
                        "remini", "facetune", "beauty", "selfie")) {
            return AppCategory.MEDIA;
        }

        // === TOOLS ===
        if (containsAny(pkg, "browser", "chrome", "firefox", "opera", "edge", "brave",
                "safari", "uc", "calc", "calculator", "clock", "alarm", "timer",
                "compass", "flash", "torch", "light", "qr", "scan", "barcode",
                "weather", "file", "manager", "explorer", "cleaner", "boost",
                "battery", "vpn", "proxy", "wifi", "speed", "test", "tool",
                "utility", "system", "settings", "security", "antivirus",
                "keyboard", "launcher", "theme", "wallpaper", "lock", "applock",
                "backup", "transfer", "share", "bluetooth", "nfc", "maps",
                "gps", "navigation", "compass", "measure", "ruler", "level") ||
                containsAny(name, "browser", "chrome", "firefox", "opera", "edge", "brave",
                        "calculator", "clock", "alarm", "timer", "compass", "flashlight",
                        "torch", "qr", "scanner", "weather", "file manager", "cleaner",
                        "booster", "battery", "vpn", "wifi", "speed test", "tool",
                        "utility", "security", "antivirus", "keyboard", "launcher",
                        "theme", "wallpaper", "lock", "backup", "transfer", "share",
                        "maps", "gps", "navigation", "compass", "measure")) {
            return AppCategory.TOOLS;
        }

        return AppCategory.OTHER;
    }

    /**
     * Check if text contains any of the keywords
     */
    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Legacy method for backward compatibility
     */
    @Deprecated
    public static AppCategory getCategory(String packageName) {
        if (packageName == null)
            return AppCategory.OTHER;
        return categorizeByPatterns(packageName, "");
    }

    public static AppCategory getCategory(String packageName, String appName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return AppCategory.OTHER;
        }
        return categorizeByPatterns(packageName, appName != null ? appName : "");
    }
    public static AppCategory[] getAllCategories() {
        return AppCategory.values();
    }
}
