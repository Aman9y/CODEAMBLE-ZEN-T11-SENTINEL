package online.monarchlabs.sentinel.utils;

/** User-facing help, privacy, and terms text kept in one factual source. */
public final class InfoContentRepository {
    public static final String KEY_HELP = "help";
    public static final String KEY_PRIVACY = "privacy";
    public static final String KEY_TERMS = "terms";

    private InfoContentRepository() {}

    public static String getTitle(String key) {
        switch (key) {
            case KEY_HELP: return "Help & Support";
            case KEY_PRIVACY: return "Privacy Policy";
            case KEY_TERMS: return "Terms of Service";
            default: return "Information";
        }
    }

    public static String getContent(String key) {
        switch (key) {
            case KEY_HELP: return getHelpContent();
            case KEY_PRIVACY: return getPrivacyContent();
            case KEY_TERMS: return getTermsContent();
            default: return "Content not found.";
        }
    }

    private static String getHelpContent() {
        return "Last updated: June 16, 2026\n\n" +
                "CHILD-DEVICE PERMISSIONS\n\n" +
                "Sentinel needs certain Android permissions only on a child device where its parental-control features are enabled. Android settings control whether each permission remains active.\n\n" +
                "1. Accessibility Service\n" +
                "Sentinel observes page views and taps in app and other actions on this child device to detect when a restricted app is opened and enforce parent-selected blocks, screen-time limits, and focus modes. App package names and enforcement events may be synchronized with the linked parent account. Sentinel does not collect passwords, payment details, private messages, or unrelated screen content through Accessibility.\n\n" +
                "2. Usage Access\n" +
                "Used to calculate per-app usage, screen-time totals, timer status, and usage reports. Package names, app names, usage duration, and timestamps may be uploaded to Sentinel services and shown to the linked parent account.\n\n" +
                "3. Notifications\n" +
                "Used for ongoing monitoring notices, timer status, limit alerts, focus-mode updates, and permission/service status alerts. Notification permission does not give Sentinel access to other apps' notification contents.\n\n" +
                "4. Location\n" +
                "When location access is enabled, the child device can send its current location, accuracy, provider/status, and timestamp so the linked parent can view or refresh the family map. Background location is used only if the parent-authorized location feature should continue while Sentinel is closed or not in use.\n\n" +
                "5. Installed Apps\n" +
                "The child device sends its launchable-app list, package names, app names, icons, install/remove status, and related timestamps so the linked parent can choose apps for blocking, timers, and focus modes.\n\n" +
                "6. Device Admin / Uninstall Protection\n" +
                "When Uninstall Protection is active, Sentinel uses Android Device Admin only to add Android's verification step before the app can be uninstalled. Sentinel does not use Device Admin to wipe data, reset passwords, lock the device, disable the camera, or manage other device policies. The linked parent can view the current protection state and send a setup request from the parent dashboard; Android confirms the change on the child device.\n\n" +
                "7. Battery Optimization Exemption\n" +
                "Used to improve reliability of timer resets, app-limit enforcement, connection status, and optional location updates while the child device is idle. Android settings control whether the exemption remains active.\n\n" +
                "TROUBLESHOOTING\n\n" +
                "If a child device appears offline, check its internet connection, battery restrictions, and required Android permissions. If necessary, reconnect it using a new QR code from the parent's device.\n\n" +
                "Support: monarch.official2005@gmail.com";
    }

    private static String getPrivacyContent() {
        return "Last updated: June 16, 2026\n\n" +
                "This policy describes the data processed by Sentinel, a parental-control application. The parent account holder must be a parent or legal guardian, or otherwise have lawful authority to manage the linked child device.\n\n" +
                "1. INFORMATION PROCESSED\n\n" +
                "Depending on the features and permissions used, Sentinel may process:\n" +
                "- Parent account details, including name, email address, phone number, account identifiers, and account timestamps.\n" +
                "- Child and parent device identifiers, device name/model, connection records, service status, heartbeat information, and permission status.\n" +
                "- Installed-app details such as app name, package name, icon, and install or removal events.\n" +
                "- App usage and screen-time records, foreground-app events, active timers, usage limits, blocked-app settings, focus-mode settings, and parent commands.\n" +
                "- Child-device location, accuracy, provider/status, and timestamp when location permission and location features are enabled, including background location when that permission is granted.\n" +
                "- Notification permission and local notification status for ongoing monitoring, timer, focus-mode, and service alerts. Sentinel does not read other apps' notification contents.\n" +
                "- Accessibility-derived page views and taps in app and other actions used for app blocking and screen-time enforcement. Sentinel does not collect passwords, payment details, private messages, typed text, or unrelated screen content through Accessibility.\n" +
                "- Device Admin and Uninstall Protection status, including whether parent-controlled uninstall protection is enabled or disabled.\n" +
                "- QR pairing sessions, guardian-consent records, OTP records, rate-limit records, and login/session information.\n" +
                "- App reliability information processed through Firebase Analytics and Firebase Crashlytics, such as app events, device/app metadata, diagnostics, and crash reports.\n\n" +
                "Sentinel does not request SMS, call-log, contacts, microphone, or VPN access. Camera access is used only to scan a parent QR pairing code and camera frames are not stored or uploaded by Sentinel.\n\n" +
                "2. HOW INFORMATION IS USED\n\n" +
                "Information is used to authenticate parent accounts, pair devices, provide app blocking and timers, display usage and location information to the linked parent, synchronize parent commands, detect connection or permission problems, maintain parent-controlled Uninstall Protection, prevent abuse, and diagnose app failures. Child-device data is not used for behavioral advertising and is not sold.\n\n" +
                "3. WHERE INFORMATION GOES\n\n" +
                "Sentinel stores and processes remote data using Firebase services and Appwrite functions. Child-device information is made available to the parent account or parent device linked through the pairing process. Service providers may process data on our behalf to provide authentication, database hosting, function execution, email delivery, analytics, and crash reporting.\n\n" +
                "Sentinel does not provide end-to-end encryption between parent and child devices. Network transport is handled by Android, Firebase, Appwrite, and related service connections. No claim is made that every locally stored value is encrypted.\n\n" +
                "4. GUARDIAN CONSENT AND NOTICE\n\n" +
                "Before generating a pairing code, the parent must confirm that they are the child's parent or legal guardian, are authorized to manage the device, and consent to the described processing. The parent is responsible for giving the child age-appropriate notice that the device is managed. Sentinel must not be used for hidden monitoring of an adult or any person without lawful authority and notice.\n\n" +
                "5. RETENTION\n\n" +
                "Account, device, control, and connection data is retained while needed to provide the service. Short-lived pairing and OTP records expire sooner. Historical usage, location, event, and diagnostic records may be removed by automated retention processing. Some provider logs, backups, or records required for security, legal obligations, or dispute resolution may remain for a limited period after deletion.\n\n" +
                "6. ACCOUNT AND DATA DELETION\n\n" +
                "A signed-in parent can select Settings > Delete Account and Data. The deletion process removes the Firebase Authentication account and associated parent, consent, pairing, and linked child-device records covered by the deletion service, including usage, location, installed-app, timer, control, and permission-status records identified by the linked account and child devices. Local app data is cleared on the parent's device after the server confirms deletion. Data already present on another device, in provider backups, or in provider-controlled logs may not disappear immediately.\n\n" +
                "7. CHOICES AND CONTROLS\n\n" +
                "Android permissions can be reviewed or disabled in system settings. Disabling a permission may stop the related feature. Parents can disconnect child devices, stop using features, sign out, or delete their account.\n\n" +
                "8. CONTACT\n\n" +
                "Questions or privacy requests: monarch.official2005@gmail.com";
    }

    private static String getTermsContent() {
        return "Effective date: June 16, 2026\n\n" +
                "1. ACCEPTANCE\n\n" +
                "By creating an account, pairing a device, or using Sentinel, you agree to these Terms of Service and the Privacy Policy. Do not use the service if you do not agree.\n\n" +
                "2. ELIGIBILITY AND AUTHORITY\n\n" +
                "The parent account is intended for an adult parent, legal guardian, or person with lawful authority to manage the linked child device. You must provide accurate account information and keep your credentials secure.\n\n" +
                "3. PERMITTED USE\n\n" +
                "Sentinel may be used for lawful and transparent parental-control purposes. You may pair only devices you own, are authorized to manage, or are legally permitted to supervise. You are responsible for providing any notice and obtaining any consent required by applicable law.\n\n" +
                "4. PROHIBITED USE\n\n" +
                "You must not use Sentinel to secretly monitor an adult, partner, employee, or any person without lawful authority and appropriate notice. You must not use the service to stalk, harass, threaten, exploit, unlawfully track, gain unauthorized access, evade security controls, or violate another person's rights.\n\n" +
                "5. DEVICE PERMISSIONS AND LIMITATIONS\n\n" +
                "Parental-control features depend on Android permissions and special access, including Accessibility, Usage Access, app visibility, notifications, location, background services, battery settings, and parent-controlled Device Admin where enabled. Android or device manufacturers may interrupt background operation, allow permissions to be disabled, or allow Device Admin to be deactivated before uninstall. Sentinel does not guarantee uninterrupted monitoring, blocking, location availability, command delivery, uninstall protection, or data recovery.\n\n" +
                "6. ACCOUNT RESPONSIBILITY\n\n" +
                "You are responsible for activity under your account and for the parental-control settings you choose. Notify us if you believe your account or a paired device is being used without authorization.\n\n" +
                "7. SERVICE CHANGES\n\n" +
                "Features may be changed, limited, suspended, or discontinued. Updates may be required for continued operation. We may restrict access where reasonably necessary to address misuse, security risks, legal requirements, or service availability.\n\n" +
                "8. THIRD-PARTY SERVICES\n\n" +
                "Sentinel relies on services including Firebase, Appwrite, Google Play services, Google Maps, Android system APIs, and email delivery providers. Their availability and data handling are also governed by their own terms and policies.\n\n" +
                "9. NO WARRANTY\n\n" +
                "Sentinel is provided on an 'as available' basis. To the extent permitted by law, no warranty is made that every feature will be uninterrupted, error-free, or suitable for every device or purpose. The app is not an emergency service and must not be relied on as the sole means of protecting a child or locating a device.\n\n" +
                "10. LIABILITY\n\n" +
                "To the extent permitted by applicable law, the developer is not responsible for indirect, incidental, special, or consequential loss resulting from use of or inability to use the service. Nothing in these terms excludes rights or liabilities that cannot legally be excluded.\n\n" +
                "11. TERMINATION AND DELETION\n\n" +
                "You may stop using Sentinel, sign out, disconnect devices, turn off parent-controlled Uninstall Protection, or use Settings > Delete Account and Data. Access may be suspended or terminated for unlawful use, material violations of these terms, or conduct that creates risk for users or the service.\n\n" +
                "12. CONTACT\n\n" +
                "Questions about these terms: monarch.official2005@gmail.com";
    }
}
