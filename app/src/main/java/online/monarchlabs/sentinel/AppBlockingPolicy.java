package online.monarchlabs.sentinel;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Central policy for apps that must always remain reachable on the child device.
 */
public final class AppBlockingPolicy {
    public static final String ANDROID_SETTINGS_PACKAGE = "com.android.settings";
    private static final String SENTINEL_PACKAGE = "online.monarchlabs.sentinel";

    private static final Set<String> EXACT_UNBLOCKABLE_PACKAGES = new HashSet<>(Arrays.asList(
            ANDROID_SETTINGS_PACKAGE,
            SENTINEL_PACKAGE,
            "com.android.phone",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.dialer",
            "com.android.contacts",
            "com.google.android.contacts",
            "com.samsung.android.contacts",
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.emergency",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.webview",
            "com.android.webview",
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.providers.settings",
            "com.android.providers.downloads",
            "com.android.providers.media",
            "com.android.providers.telephony",
            "com.android.keychain",
            "com.google.android.apps.maps",
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.android.calendar",
            "com.google.android.calendar",
            "com.android.calculator2",
            "com.google.android.calculator"
    ));

    private AppBlockingPolicy() {
    }

    public static boolean isUnblockable(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        String normalized = packageName.trim().toLowerCase(Locale.US);
        return EXACT_UNBLOCKABLE_PACKAGES.contains(normalized)
                || normalized.startsWith("com.android.phone")
                || normalized.startsWith("com.android.launcher")
                || normalized.startsWith("com.android.inputmethod")
                || normalized.startsWith("com.google.android.inputmethod")
                || normalized.startsWith("com.samsung.android.dialer")
                || normalized.startsWith("com.samsung.android.messaging")
                || normalized.startsWith("com.samsung.android.honeyboard")
                || normalized.equals(SENTINEL_PACKAGE);
    }

    public static String getBlockedSelectionMessage(String packageName, String appName) {
        String label = appName == null || appName.trim().isEmpty() ? "This app" : appName.trim();
        if (SENTINEL_PACKAGE.equals(packageName)) {
            return "Sentinel is required for child safety and cannot be blocked.";
        }
        if (ANDROID_SETTINGS_PACKAGE.equals(packageName)) {
            return "Android Settings must remain available. Sentinel protects sensitive settings separately.";
        }
        if (packageName != null) {
            String normalized = packageName.toLowerCase(Locale.US);
            if (normalized.contains("dialer") || normalized.contains("phone")
                    || normalized.contains("contacts") || normalized.contains("messaging")
                    || normalized.contains("mms") || normalized.contains("emergency")) {
                return label + " must stay available for family and emergency communication.";
            }
            if (normalized.contains("launcher") || normalized.contains("systemui")
                    || normalized.contains("inputmethod") || normalized.contains("permissioncontroller")
                    || normalized.contains("packageinstaller") || normalized.contains("gms")
                    || normalized.contains("gsf") || normalized.contains("webview")) {
                return label + " is required by Android and cannot be blocked safely.";
            }
            if (normalized.contains("maps")) {
                return label + " is kept available for location and safety workflows.";
            }
            if (normalized.contains("clock") || normalized.contains("calendar")
                    || normalized.contains("calculator")) {
                return label + " is a basic utility and should remain available.";
            }
        }
        return label + " is protected and cannot be blocked.";
    }
}
