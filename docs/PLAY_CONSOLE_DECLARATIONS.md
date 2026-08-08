# Sentinel Play Console Declaration Copy

Last updated: 2026-06-16

Use this as paste-ready drafting copy for Google Play Console. Replace support/legal placeholders with your final public website, privacy URL, support email, and legal publisher details before submission.

Important release note: this copy assumes Sentinel is submitted as a transparent parental-control app for parents/legal guardians managing a linked child device. It also assumes the app does not use Accessibility to block access to Android's own permission, Device Admin deactivation, App Info, force-stop, or uninstall controls. If that behavior remains in the release build, review risk remains high.

## Accessibility Service Declaration

Sentinel is a parental-control app and is not an accessibility tool. On a linked child device that a parent or legal guardian is authorized to manage, Sentinel uses Android Accessibility Service to detect the foreground app and enforce parent-selected app restrictions, focus modes, and screen-time limits.

The service lets Sentinel compare the current foreground app against the linked parent's blocked apps, timers, and daily limits. When a restricted app is opened or a parent-set allowance has expired, Sentinel returns the child device to the Home screen so the restricted app is no longer foregrounded. This is core functionality because parental app blocking and screen-time enforcement must work while the child app is not visible.

Accessibility-derived app identity and enforcement events may be synchronized with Sentinel services and shown to the linked parent account for parental-control functionality. Sentinel does not use Accessibility to collect passwords, payment details, private messages, typed text, or unrelated screen content. Sentinel does not use Accessibility for advertising, marketing, or behavioral profiling.

Before opening Android Accessibility settings, Sentinel displays a prominent disclosure explaining the data accessed, the purpose, whether app identity/enforcement data is shared with the linked parent, and how the user can decline by not enabling the service.

## Usage Access Declaration

Sentinel uses Android Usage Access on a linked child device to read app usage duration, foreground-app events, package names, app names, timestamps, and screen-time totals. This information is used to provide the linked parent with screen-time reports, timer status, app-limit enforcement, and daily usage summaries.

Usage Access is core to Sentinel's parental-control purpose. Without Usage Access, Sentinel cannot accurately calculate app usage, apply per-app timers, or show parents which apps are being used and for how long. Usage information may be uploaded to Sentinel services and displayed only to the linked parent account. It is not sold and is not used for advertising.

Before opening Android Usage Access settings, Sentinel explains that usage data is collected from the child device, used for screen-time reports and limits, and synchronized with the linked parent account.

## QUERY_ALL_PACKAGES Declaration

Sentinel requires broad app visibility on the linked child device so the parent can view the complete list of launchable installed apps and choose any app for blocking, timers, focus modes, and screen-time limits.

The set of apps installed on a child's device is not known in advance and changes over time. Targeted package queries would omit newly installed, regional, sideloaded, uncommon, or later-installed apps, making the parent unable to manage those apps and leaving the core parental-control feature incomplete.

Sentinel collects and uploads installed-app information such as app name, package name, icon, system-app status, install/remove status, and timestamps to Sentinel services so it can be shown to the linked parent account. This data is used only for parental-control functionality. It is not sold and is not used for advertising.

## Background Location Declaration

Sentinel offers a family-location feature for a linked child device. When the parent-authorized location feature is enabled and Android permissions are granted, Sentinel collects the child device's location, accuracy, provider/status, and timestamp and uploads it to Sentinel services so the linked parent can view the device on the family map or request a location refresh.

Background location is used only when the location feature should continue while Sentinel is closed or not in use. This allows the linked parent to see family-location updates and refresh the child device's location from the parent dashboard. Location data is made available only to the linked parent account and service providers used to operate Sentinel. It is not sold and is not used for advertising.

Before requesting background location, Sentinel displays a prominent disclosure that location may be collected and uploaded even when the app is closed or not in use, explains the parent-facing purpose, and tells the user to select "Allow all the time" only if they agree.

## Device Admin / Uninstall Protection Declaration

Sentinel uses Android Device Admin only for parent-controlled Uninstall Protection on a linked child device. When enabled, Android requires the Sentinel Device Admin component to be deactivated before Sentinel can be uninstalled.

Sentinel declares no Device Admin policies for wiping data, resetting passwords, locking the device, disabling the camera, encrypting storage, or monitoring failed logins. Device Admin does not read device content. The linked parent can turn Uninstall Protection on or off from the parent dashboard, and Sentinel explains that Android's normal Device Admin deactivation step applies before uninstall.

Device Admin status and Uninstall Protection status may be synchronized with Sentinel services so the linked parent can see whether protection is enabled or disabled. This information is used only for parental-control transparency and device-management status.

## Data Safety Draft

### App Data Collection

Answer that Sentinel collects user data.

### Sharing

Recommended default: answer "No" for sharing if Firebase, Appwrite, Google services, Crashlytics, Analytics, Maps/Location, and email delivery providers process data only as service providers on Sentinel's behalf and do not use it independently. Confirm your provider terms before submission.

### Security Practices

- Data is encrypted in transit: Yes, if production endpoints use HTTPS/TLS and cleartext traffic remains disabled.
- Users can request data deletion: Yes. In-app path: Settings > Delete Account and Data. Public website deletion page must also be live before submission.
- Data is not sold: Yes, if this matches actual business practice.

### Data Types To Declare

Personal info:
- Name: collected for parent profile/account management.
- Email address: collected for account creation, login, OTP, support, and deletion verification.
- Phone number: collected if used in account or parent profile flows.
- User IDs: collected for Firebase/Appwrite account identity and linked parent/child records.

Location:
- Approximate location: collected if coarse location is granted for family-location features.
- Precise location: collected if fine location is granted for family-location features.
- Background location: collected if "Allow all the time" is granted for family-location updates while app is closed or not in use.

App activity:
- App interactions / app usage: collected through Usage Access and Accessibility-derived enforcement events for screen-time reports and app-limit enforcement.
- Installed apps: collected through package visibility to show the linked parent the child device's apps and configure blocking, timers, and focus modes.

Device or other IDs:
- Device identifiers: Android ID, Firebase/Appwrite identifiers, device ID fields, parent/child device IDs, QR pairing IDs, and session identifiers.

App info and performance:
- Crash logs: collected through Firebase Crashlytics.
- Diagnostics: collected through service status, permission status, device health, connection state, and related logs/events.
- Other app performance data: collected if Firebase Analytics/Crashlytics records app events, device metadata, or reliability information.

Photos and videos:
- Camera access is used to scan QR pairing codes. Do not declare photo/video collection if camera frames are processed locally and not stored or uploaded.

Messages, contacts, audio, files, calendar, SMS, call logs:
- Do not declare as collected unless new code or SDK behavior adds those data types. The current audited app does not request SMS, call-log, contacts, microphone, or VPN access.

### Data Purposes

Select purposes that match each collected type:
- App functionality
- Account management
- Analytics, only for Firebase Analytics/app event data if enabled
- Developer communications, for OTP/support/account email communications
- Security, fraud prevention, and compliance, for authentication, rate limits, abuse prevention, crash diagnostics, and deletion verification

Do not select advertising or marketing unless those features are added.

### Required Or Optional

Required for core child-device monitoring:
- Account identifiers and linked device IDs
- Usage Access data
- Accessibility-derived foreground app/enforcement data
- Installed-app inventory for app selection/blocking
- Permission/service status

Optional or feature-dependent:
- Location and background location
- Camera for QR pairing if an alternate pairing flow exists; otherwise required for QR pairing
- Notifications, depending on platform behavior and whether monitoring transparency/status requires them
- Device Admin / Uninstall Protection, if parent-controlled and separately enabled

### Deletion

State that a signed-in parent can delete the account and associated data from Settings > Delete Account and Data. Also provide the public web deletion page URL and support email. Deletion should cover parent account records, consent records, pairing records, linked child-device records, installed-app inventory, usage history, location records, timers, blocked-app settings, control settings, permission status, and related records covered by the deletion service, except data retained for legal, security, dispute, provider backup, or operational log reasons.
