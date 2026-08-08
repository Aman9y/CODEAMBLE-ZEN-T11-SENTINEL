package online.monarchlabs.sentinel;

/**
 * Central policy for apps that must always remain reachable on the child device.
 */
public final class AppBlockingPolicy {
    public static final String ANDROID_SETTINGS_PACKAGE = "com.android.settings";

    private AppBlockingPolicy() {
    }

    public static boolean isUnblockable(String packageName) {
        return ANDROID_SETTINGS_PACKAGE.equals(packageName);
    }
}
