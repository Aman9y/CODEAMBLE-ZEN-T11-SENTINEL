# Sentinel / FamilyGuard - Final Google Play Readiness Audit

Audit date: June 13, 2026

Operator named by the requested publishing materials: **Aman Mandal**

Scope reviewed: source manifest, Android resources, Java source, Gradle configuration and dependencies, Firebase Authentication/Realtime Database/Firestore use, Appwrite function source, services, receivers, Accessibility Service, Device Admin, Usage Stats, location, QR pairing, consent, deletion, and retention code.

Artifact limitation: no final signed AAB was provided for this audit. Release AAB signing certificate, final package name, final merged AAB manifest, Play SDK Index results, and artifact-level secret scan are therefore **not confirmed**. `:app:processReleaseMainManifest` completed successfully on June 13, 2026.

## Executive Result

**Submission readiness: NOT READY**

The project has improved materially: `isMonitoringTool=child_monitoring` is present; SMS, phone-state, storage, overlay, task, exact-alarm, and data-sync foreground-service permissions were removed; a visible child-monitoring disclosure exists; parent consent is recorded; account deletion code exists; and backend retention code exists.

The principal P0 blocker remains the Accessibility implementation. It requests and uses broad window-content access, recursively reads visible text/content descriptions, detects Sentinel's Accessibility/Device Admin settings, and returns the user to Home. Documentation cannot make that behavior compliant. A new AAB with narrowed Accessibility behavior is required.

Estimated first-submission approval probability:

- **Current source:** 20-35%
- **After all P0 fixes and verified production configuration:** 60-75%

No approval probability is guaranteed.

---

# Phase 1 - Codebase Compliance Audit

## 1. Permission Inventory

All declarations below are in `app/src/main/AndroidManifest.xml`.

| Permission | Confirmed use | Required assessment | Recommendation | Risk |
|---|---|---|---|---|
| `INTERNET` | Firebase Auth, Realtime Database, Firestore, Appwrite functions, Maps/location services | Required | Keep | Low, but enables all remote data processing |
| `CAMERA` | `ChildLoginActivity` requests it and ZXing scans the parent's QR code | Required only for QR pairing | Keep; request only when scanner opens | Medium |
| `QUERY_ALL_PACKAGES` | `InstalledAppsManager.getInstalledApplications()` builds and uploads the child app inventory | Core to arbitrary installed-app selection, but Play eligibility is discretionary | Keep only if Play approves declaration; prominently disclose remote upload | High/P0 declaration risk |
| `FOREGROUND_SERVICE` | Multiple child monitoring/timer/location services call foreground-service APIs | Required for current architecture | Keep; verify each service starts/stops correctly | Medium-High |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Permission, app timer, restriction, and persistent notification services declare `specialUse` | Confirmed architecture, but each subtype must be accepted by Play | Keep only for services that continuously perform the declared user-visible function | High |
| `POST_NOTIFICATIONS` | Parent/child permission flows and ongoing timer/monitoring notifications | Required for visible monitoring and alerts on Android 13+ | Keep; do not misrepresent optional OS denial as consent | Medium |
| `KILL_BACKGROUND_PROCESSES` | `BlockService` calls `killBackgroundProcesses()` for restricted apps | Not necessary for permitted deterministic Home-action blocking and generally ineffective against other apps | **Remove permission and calls before release** | P0/High |
| `PACKAGE_USAGE_STATS` | Numerous services call `queryEvents()`/`queryUsageStats()` for screen time and timers | Core parental-control use | Keep with separate disclosure and Usage Access instructions | High |
| `RECEIVE_BOOT_COMPLETED` | `BootReceiver`/`ServiceWatchdog` restore monitoring and timer services after restart | Required for persistence of enabled controls | Keep; start only previously enabled features | Medium |
| `WAKE_LOCK` | Bounded partial wake locks in reset, usage limiter, and remote service code | Used, but ten-minute locks are broad | Keep only after timeout/release audit; shorten where possible | Medium |
| `ACCESS_FINE_LOCATION` | High-accuracy fused/native location collection and Firebase upload | Required for precise family-location feature | Keep only if precise location remains core | High |
| `ACCESS_COARSE_LOCATION` | Requested with fine location and accepted as platform fallback | Required by modern runtime request model | Keep | High because it is location data |
| `ACCESS_BACKGROUND_LOCATION` | Child location continues outside visible UI | Required for current continuous location behavior | Keep only with Play approval, prominent disclosure, and strong necessity evidence | P0/High |
| `FOREGROUND_SERVICE_LOCATION` | `RemoteBlockService` is a location foreground service | Required for active background location service | Keep while location FGS remains | High |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `BatteryOptimizationManager` and OEM helper request exemption | Used to improve persistent controls/location | Consider removing direct exemption request and using standard settings guidance unless Play/OEM need is compelling | High |

Special binding permissions correctly appear on components rather than as ordinary runtime permissions:

- `BIND_ACCESSIBILITY_SERVICE` on `BlockService`.
- `BIND_DEVICE_ADMIN` on `AppDeviceAdminReceiver`.

Removed since the earlier audit: `READ_PHONE_STATE`, legacy storage, `SYSTEM_ALERT_WINDOW`, `GET_TASKS`, `REORDER_TASKS`, exact alarms, `SET_ALARM`, and foreground data-sync permission. This is positive.

## 2. Accessibility Audit

### Events and capabilities requested

`accessibility_service_config.xml` requests:

- `typeWindowStateChanged`
- `typeWindowsChanged`
- `typeWindowContentChanged`
- `typeViewFocused`
- retrieval of interactive windows and window content
- reporting view IDs and including non-important views
- key-event filtering

### Actual data accessed

Confirmed behavior in `BlockService`:

- Reads event package names to identify the foreground app.
- Reads Usage Stats as a fallback/parallel foreground-app source.
- Calls `getRootInActiveWindow()`.
- Recursively reads node text and content descriptions.
- Detects switches/toggles and Settings-page text relating to Sentinel, Device Admin, and Accessibility.
- Uses Home/Recents global actions to remove restricted apps from the foreground.
- Calls `killBackgroundProcesses()` as an additional enforcement layer.
- Broadcasts foreground package names locally for timer logic.
- Usage/package activity is uploaded through other usage synchronization code to Firebase.

Passwords or private messages: no code was found that deliberately extracts passwords, payment details, messages, or form values. However, because the service currently retrieves and recursively reads window content, it has technical access to unrelated visible text. A declaration saying it only accesses package names would currently be false.

### Risk assessment

**P0 - must fix before submission.** Google Play permits deterministic rule-based Accessibility automation for narrow purposes, but requires accurate prominent disclosure and consent for non-accessibility tools. The current Settings-screen inspection and deactivation interference create Device and Network Abuse, deceptive behavior, and monitoring-app risk.

Required code changes:

1. Delete all logic that detects or blocks Accessibility, Device Admin, App Info, permissions, force-stop, or uninstall settings.
2. Delete `getRootInActiveWindow()`, recursive node text/content-description collection, and settings-keyword matching unless a separately permitted feature can prove strict necessity. No such necessity was confirmed.
3. Remove key-event filtering and `canRequestFilterKeyEvents`.
4. Remove `typeWindowContentChanged`, `typeViewFocused`, `flagReportViewIds`, `flagIncludeNotImportantViews`, and `flagRetrieveInteractiveWindows` unless testing proves package-only enforcement cannot work without a specific item.
5. Prefer `typeWindowStateChanged` package identity plus Usage Stats and a deterministic Home action.
6. Remove `killBackgroundProcesses()` and its permission.
7. Use a standalone Accessibility disclosure immediately before opening Android Accessibility settings. The general child-monitoring disclosure is useful but does not replace Google's required Accessibility-specific disclosure.

## 3. Device Admin Audit

`device_admin.xml` declares an empty `<uses-policies />`. The implementation starts `ACTION_ADD_DEVICE_ADMIN`, checks active status, and receives enable/disable callbacks.

| Device Admin policy | Declared | API use found | Result |
|---|---:|---:|---|
| Force lock | No | No `lockNow()` | Not used; keep absent |
| Wipe data | No | No `wipeData()` | Not used; keep absent |
| Reset/limit/expire password | No | No DevicePolicy password calls | Not used; keep absent |
| Disable camera | No | No `setCameraDisabled()` | Not used; keep absent |
| Encrypted storage | No | No | Not used; keep absent |
| Watch login | No | No | Not used; keep absent |

Actual purpose: active Device Admin adds an Android deactivation step before uninstall. Device Admin is not a full device-management solution.

Risk: **High** when combined with Accessibility interference. After removal of deactivation blocking, keep Device Admin optional and accurately describe its limited effect. Removing Device Admin entirely would further reduce review risk.

## 4. QUERY_ALL_PACKAGES Audit

Confirmed dependent functionality:

- Enumerates launchable installed applications.
- Collects package name, app name, system-app status, icon, timestamp, and count.
- Uploads inventory to Firebase under the child device ID.
- Lets the linked parent choose arbitrary apps for blocks, timers, schedules, and focus modes.
- Receives package-added/removed broadcasts and synchronizes changes.

Targeted `<queries>` are insufficient for a product whose core UI must present every currently or later installed launchable app, because the set is unknown at build time.

Policy risk remains **High**. Google says broad visibility is allowed only when core functionality would be broken without it and a less broad method is insufficient. Parental control is not one of the policy page's explicit examples. The declaration, listing, and video must make app selection a central feature, and the inventory must not be used for analytics or advertising.

## 5. Firebase and Backend Audit

### Confirmed services

- Firebase Authentication: parent password accounts, password reset, custom OTP token login.
- Firebase Realtime Database: family links, devices, apps, usage, location, commands, timers, permissions, consent, status, and deletion/retention targets.
- Firestore: parent user compatibility record; deletion function removes `users/{uid}`.
- Firebase Analytics: dependency present and collection explicitly enabled.
- Firebase Crashlytics: dependency present and collection explicitly enabled; role custom key is set and user ID is blank.
- Appwrite function: OTP send/verify, guardian-consent recording, account deletion, and scheduled retention entry point.
- Email delivery: SMTP/Gmail or an Appwrite email function, depending on deployment environment.

### Positive controls found

- Server-side Firebase ID token verification for consent and account deletion.
- Recent-authentication requirement for account deletion.
- OTP hashing/salting, expiry, attempt limits, and send rate limiting.
- Multi-path account/child data deletion and Firebase Auth deletion.
- Retention code for QR sessions, OTPs, consent events, app/permission events, location history, usage, diagnostics, and orphan devices.
- No server API key embedded in Android BuildConfig.

### Risks and unconfirmed protections

- **P0:** deployed Realtime Database and Firestore rules are not included. Documentation is not proof of deployment. Verify least-privilege rules in the production project.
- **P1:** Firebase App Check / Play Integrity App Check dependency and initialization were not found. Add App Check and enforce it for supported Firebase resources after staged rollout.
- **P1:** execution of the retention function on an Appwrite schedule is not confirmed. Code exists, but deployment and successful recurring runs need logs/evidence.
- **P1:** the same default Appwrite function ID is used for OTP and privacy actions. This is workable but increases blast radius. Separate functions/permissions are preferable.
- **P1:** Firestore is used but the manifest removes KTX registrars only; Firestore itself remains included and active. Include Firestore in rules, deletion, privacy, and Data Safety review.
- **P1:** Analytics is automatically enabled. Either keep and disclose it, or remove the dependency and explicit enablement for a lower-risk child-data posture.
- **P1:** confirm the Appwrite execution endpoint cannot be abused to invoke scheduled retention. The code keys scheduled execution off the Appwrite trigger header; production platform controls must prevent forged privileged invocations.
- **P1:** rotate/restrict Maps, Firebase, Appwrite, SMTP, and service-account credentials and verify no secret appears in the final AAB.
- **P2:** minimize sensitive package names, device IDs, coordinates, and backend error details in production logs.

## 6. Child Data Inventory

Confirmed child/family data categories:

- Child display name.
- Child device ID and Android-derived identifiers.
- Device name, model, manufacturer, app/OS metadata.
- Connection status, last-seen timestamps, heartbeat, service and permission status.
- Precise/coarse location, accuracy, timestamp, GPS state, and location requests.
- Installed app names, package names, system-app flag, icons, counts, install/removal events.
- Foreground-app and usage events, per-app duration, screen time, dates, and history.
- Blocked-app settings, limits, timers, focus modes, schedules, and commands.
- Parent/child relationship records and consent records.
- QR share/session identifiers, parent device/name, child device link, and timestamps.
- Crash, diagnostic, and Analytics data that may relate to a child-device installation.

No SMS, call logs, contacts, microphone recordings, media-library content, financial data, or health data was found.

---

# Phase 2 - Google Play Documentation

## 1. Production Privacy Policy

### Privacy Policy for Sentinel / FamilyGuard

Effective date: [EFFECTIVE DATE]

Sentinel / FamilyGuard is operated by **Aman Mandal** (“Sentinel,” “we,” “us,” or “our”).

Privacy contact: **[PRIVACY EMAIL]**
Support contact: **[SUPPORT EMAIL]**
Website: **[WEBSITE]**
Address: **[ADDRESS]**

### Scope and Authorized Use

Sentinel is parental-control software intended for use by a parent or legal guardian who is authorized to manage a child's device. It must not be used to secretly monitor an adult, partner, employee, or any person without lawful authority and appropriate notice.

### Data We Process

Depending on the features used, Sentinel processes parent name, email, phone number, account ID, authentication data, parent and child device identifiers, child display name, device information, connection and permission status, installed-app inventory and icons, app install/removal events, app activity and screen time, restrictions and timers, precise location and accuracy, QR pairing records, guardian-consent records, diagnostics, crashes, and Analytics data.

Camera frames are used locally to scan a QR pairing code. No storage or upload of camera photos or videos was found. Sentinel does not request SMS or call-log permissions.

### Purposes

We process data to authenticate parent accounts, pair authorized devices, show the parent installed apps, usage, status, and location, enforce app restrictions and time limits, synchronize parent commands, maintain visible monitoring services, prevent abuse, process account deletion, and diagnose failures.

### Local and Remote Processing

App-private local storage contains session, pairing, timer, restriction, and device state. Android backup and device transfer are disabled for this data.

Remote processing uses Firebase Authentication, Firebase Realtime Database, Firestore, Firebase Analytics, Firebase Crashlytics, Appwrite functions, Google Play services/Maps/location, and configured email delivery providers. Child-device information is made available to the authorized linked parent account. These providers process data on our behalf under their applicable terms.

Network connections use the transport security supplied by Android and the service endpoints. Sentinel does not claim end-to-end encryption between parent and child devices, certificate pinning, or encryption of every local value.

### Sharing and Advertising

We do not sell personal data or installed-app inventory. We do not use child data, location, usage, or installed apps for behavioral advertising. No advertising SDK was found. We disclose data to the linked parent account and to processors needed to operate authentication, databases, functions, email, maps/location, Analytics, and crash reporting. We may disclose information when legally required.

### Children and Guardian Consent

A parent or legal guardian must authorize setup, confirm their authority, review this policy, and consent before pairing. The parent is responsible for giving the child age-appropriate notice that the device is managed. Sentinel displays an in-app monitoring disclosure and ongoing Android notifications while applicable monitoring services run.

For United States users, Sentinel is designed to obtain parental authorization before collecting child-device personal information. Parents may review or request deletion of the child's information. Sentinel does not condition participation on information beyond what is reasonably needed for the selected parental-control features and does not use child data for behavioral ads.

### GDPR/UK GDPR

Where applicable, Aman Mandal acts as controller. Processing is based, as appropriate, on contract with the parent account holder, guardian consent, legitimate interests in securing and operating the service, and legal obligations. Data subjects may request access, correction, deletion, restriction, portability, objection, or withdrawal of consent by contacting **[PRIVACY EMAIL]**. International processing by service providers must use applicable transfer safeguards; executed agreements are **not confirmed** by this code audit.

### Retention

The backend code is designed to delete expired QR and OTP records, consent events after 30 days, app events after 30 days, permission events after 90 days, location history after 30 days, detailed usage/snapshots after 30 days, seven-day usage after eight days, diagnostics after 30 days, and orphan device records after 90 days. Account and active control data remain while needed for the service or until account deletion.

**Deployment qualification:** these periods must not be published as guaranteed until the Appwrite schedule is deployed, monitored, and verified. Provider backups/security logs may expire on separate provider schedules.

### Account and Data Deletion

A signed-in parent can use `Settings > Delete Account and Data`. The deletion service verifies the Firebase identity token, requires recent authentication, removes associated parent and linked child records from listed Firebase paths, deletes the Firestore user record, and deletes the Firebase Authentication user. Local parent-device data is then cleared. Data in provider backups/logs or already stored independently on another device may not disappear immediately.

Users must also be given a public web deletion page at **[WEBSITE]/delete-account** with an authenticated or support-assisted request process.

### Security

The implementation uses app-private storage, disabled Android backup, authenticated backend deletion/consent actions, OTP expiry/rate limits, and service transport security. Production Firebase rules, App Check enforcement, credential restrictions, incident response, and recurring retention execution must be verified before launch.

### Contact and Changes

We may update this policy and will update the effective date. Material changes affecting child data or guardian consent will be presented as required. Contact **[PRIVACY EMAIL]** or **[SUPPORT EMAIL]**.

## 2. Terms of Service

### Sentinel / FamilyGuard Terms of Service

Effective date: [EFFECTIVE DATE]

These Terms govern Sentinel / FamilyGuard, operated by **Aman Mandal**. Contact: **[SUPPORT EMAIL]**, **[WEBSITE]**, **[ADDRESS]**.

1. **Acceptance.** By creating an account, pairing a device, or using Sentinel, you accept these Terms and the Privacy Policy.
2. **Eligibility and authority.** The parent account is for an adult parent, legal guardian, or person with lawful authority to manage the child device. You must provide accurate information and protect account credentials.
3. **Permitted use.** Use Sentinel only for lawful, transparent parental supervision of a device you own or are authorized to manage. Provide notices and obtain consent required by law.
4. **Prohibited use.** Do not use Sentinel for secret adult monitoring, stalking, harassment, threats, exploitation, unauthorized access, unlawful tracking, evasion of security controls, or violation of another person's rights.
5. **Permissions.** Features depend on Android Accessibility, Usage Access, app visibility, notifications, location, foreground services, battery settings, and optional Device Admin. Android settings remain under device control, and disabling access may stop features.
6. **Monitoring limitations.** Sentinel is not an emergency service. App blocking, commands, location, and reports may be delayed or unavailable because of permissions, network conditions, Android/OEM restrictions, service outages, or device changes.
7. **Account responsibility.** You are responsible for account activity, paired devices, and control settings. Notify **[SUPPORT EMAIL]** of suspected unauthorized access.
8. **Third-party services.** Firebase, Appwrite, Google Play services, Maps, Android APIs, and email providers have their own terms and availability.
9. **Privacy.** Data processing is governed by the Privacy Policy. Do not pair a child device unless you have authority and have provided appropriate notice.
10. **Changes and suspension.** We may update, limit, suspend, or discontinue features and may restrict accounts to address misuse, security, legal requirements, or service availability.
11. **No warranty.** To the extent permitted by law, Sentinel is provided “as available” without a promise of uninterrupted or error-free operation.
12. **Liability.** To the extent legally permitted, Aman Mandal is not liable for indirect, incidental, special, or consequential losses. Non-excludable consumer rights remain unaffected.
13. **Termination and deletion.** You may disconnect devices, sign out, stop using Sentinel, or select `Settings > Delete Account and Data`. We may terminate access for unlawful use or material violation.
14. **Governing law.** Insert the applicable jurisdiction only after legal review: **[JURISDICTION TO BE CONFIRMED]**.
15. **Contact.** **[SUPPORT EMAIL]**, **[WEBSITE]**, **[ADDRESS]**.

## 3. Data Safety Form Answers

Use these answers for the current source build; re-check the final AAB.

| Data Safety category | Collected | Shared | Purpose | Required/optional | Ephemeral | Deletion |
|---|---:|---:|---|---|---:|---:|
| Name | Yes | No | Account management; linked device display | Parent name required; child display name required in current flow | No | Yes |
| Email address | Yes | No | Account, login, OTP, password reset, support | Required for parent account | No | Yes |
| Phone number | Yes | No | Parent account and phone-login index | Required by current signup | No | Yes |
| User IDs | Yes | No | Authentication, family linking, security | Required | No | Yes |
| Precise location | Yes | No | Family map and parent refresh | Required for location feature; current onboarding behavior must be verified | No | Yes |
| App interactions/other app activity | Yes | No | Screen time, timers, blocking | Required for core controls | No | Yes |
| Installed apps | Yes | No | Parent app selection and synchronization | Required for app-control feature | No | Yes |
| Crash logs | Yes | No | Diagnostics | Automatic in current source | No | Processor/account deletion controls apply |
| Diagnostics/performance | Yes | No | Reliability and service diagnostics | Automatic/current functionality | No | Yes where linked; provider controls may apply |
| Device or other IDs | Yes | No | Pairing, device identity, synchronization | Required | No | Yes |
| Authentication information | Yes | No | Login/security | Required | OTP validity is temporary; account records are not ephemeral | Yes |
| QR pairing data | Yes | No | Parent-child linking | Required for QR flow | No, though designed to expire | Yes |
| Parental-control settings | Yes | No | Core app functionality | Required when feature selected | No | Yes |
| Analytics data | Yes | No | Analytics | Automatic because Firebase Analytics is explicitly enabled | No | Subject to Firebase controls |

“Shared: No” assumes Firebase, Appwrite, Google, and email vendors act solely as service providers. Confirm contracts and independent-use terms. All remotely transmitted categories use encrypted transport through HTTPS/TLS-capable service connections. Do not claim end-to-end encryption.

## 4. Accessibility Service Declaration

**Use only after the P0 Accessibility changes are implemented:**

Sentinel is a parental-control app and is not an accessibility tool. On an authorized child device, Sentinel uses Accessibility Service to observe page views and taps in app and other actions (foreground window package changes). It compares the package name with the parent-selected restricted-app list and returns the device to Home when a restricted app is opened or has reached its time allowance. This provides the core user-facing app-blocking and screen-time enforcement feature.

Sentinel does not use Accessibility to read passwords, payment information, private messages, emails, typed text, or unrelated screen content. It does not use Accessibility for advertising, marketing, Analytics, or behavioral profiling. App identity and enforcement/usage information may be synchronized through Firebase and shown only to the linked parent account for parental-control functionality.

Before opening Android Accessibility settings, Sentinel displays a separate in-app disclosure describing the data accessed, how it is used and shared, and how to decline. Enabling the service requires affirmative action by the parent or legal guardian authorized to manage the child device.

Current source does not yet match this declaration because it reads Settings window content and blocks deactivation pages.

## 5. QUERY_ALL_PACKAGES Declaration

Sentinel's core purpose is parental app management on a linked child device. It must display the complete set of launchable installed apps so the parent can select any app for blocking, daily limits, schedules, and focus modes, including apps installed after pairing. Sentinel uploads app name, package name, system-app status, icon, and install/remove state to Firebase and makes the list available only to the linked parent account for these parental-control features. The inventory is not sold and is not used for advertising or Analytics.

Targeted package declarations are insufficient because Sentinel cannot know at build time which third-party, regional, newly released, or later-installed apps are on the child device. Without complete visibility, the core app-selection and enforcement feature is incomplete.

## 6. Device Admin Declaration

Device Admin is optional and is used only to require Android deactivation before Sentinel can be uninstalled from a managed child device. Sentinel declares and uses no lock, password, wipe, camera-disable, encryption, or login-monitoring Device Admin policies. Device Admin does not read device content. It must be enabled only by the parent or legal guardian authorized to manage the device, and Sentinel must not prevent access to Android's deactivation screen.

## 7. Location Declaration

Sentinel collects the linked child device's precise location, accuracy, status, and timestamp to display the device on the authorized parent's family map and support parent-requested refresh. Background access is used because the location feature operates when the child app is closed or not in use. Location is uploaded to Firebase under the linked child device ID and is made available to the linked parent account. It is not sold or used for advertising. A prominent in-app background-location disclosure and affirmative guardian action are shown before the Android permission request.

## 8. Camera Declaration

Camera access is used only on the child pairing screen to scan the QR code displayed by the parent device. ZXing processes frames to read the pairing payload. No storage or remote upload of camera photos or videos was found. Camera access is requested only when QR pairing starts.

---

# Phase 3 - Store Listing

## Title Options

- Sentinel Parental Controls
- FamilyGuard Parental Controls
- Sentinel Family Screen Time

## Short Description

`Parental controls for app limits, screen time, and family location.`

## Full Description

Sentinel helps parents and legal guardians guide a child's Android device use with visible, family-focused parental controls.

Pair a child device using a QR code, then manage app access, daily time limits, focus schedules, screen-time information, and family location through the linked parent account.

Features include:

- View per-app usage and screen-time information
- Select installed apps for restrictions and daily limits
- Create focus periods for study, rest, and family time
- Receive device, permission, timer, and connection status
- View the linked child device's location when location is enabled
- Restore enabled controls after a device restart
- Keep monitoring visible through child-device disclosures and ongoing notifications

Sentinel uses Usage Access for screen-time calculations and Accessibility Service to observe page views and taps in app and other actions for parent-selected app-limit enforcement. It uses broad app visibility to show the child device's installed apps for parent selection. When background location is enabled, the child device's location is collected even when the app is closed or not in use and is sent to the linked parent account through Sentinel's service.

Sentinel does not request SMS or call-log access, does not contain advertising SDKs, and does not sell installed-app, usage, or location information.

**For parents and legal guardians only.** Use Sentinel only on a child's device you are legally authorized to manage. Provide the child with age-appropriate notice. Do not use Sentinel for hidden monitoring of adults or other unauthorized persons.

## Reviewer Notes

Sentinel has two roles in one app: Parent and Child. The parent creates an account and QR pairing code. The authorized child device displays a monitoring disclosure, requests Android permissions, and scans the QR code. Accessibility is used only for app restriction enforcement after the required P0 code changes. Usage and location are uploaded to Firebase for the linked parent. Device Admin is optional and declares no admin policies.

---

# Phase 4 - Play Review Preparation

## 1. Reviewer Test Instructions

Prerequisites: provide a dedicated parent demo account and two test Android devices, or give the reviewer a reproducible two-device testing arrangement. Do not use real child data.

1. Install the same production build on Parent Device A and Child Device B.
2. On Device A, select `Parent`, sign in with the supplied demo account, and open device pairing.
3. Read and accept the parent/legal-guardian consent checkbox. Display the QR code.
4. On Device B, select `Child`, enter a fictional child display name, and review the visible monitoring disclosure.
5. Show the Accessibility-specific disclosure, choose Agree, then enable Sentinel in Android Accessibility settings.
6. Enable Usage Access. Grant notifications and location only as needed for the demonstrated features. Explain that Device Admin is optional.
7. Open the QR scanner on Device B and scan Device A's QR code.
8. On Device A, open the child's installed-app list and select a harmless test app for a short restriction or time limit.
9. On Device B, open the selected app and show Sentinel returning the device to Home.
10. Use the test app briefly while allowed, then show updated usage/screen-time data on Device A.
11. With location enabled on Device B, move Sentinel to the background and refresh the family map on Device A.
12. Show Device B's ongoing monitoring notification.
13. In Parent Settings, show `Delete Account and Data` but do not execute it unless using a disposable reviewer account.

Supply exact credentials, OTP behavior, Firebase/Appwrite test availability, and any wait times in Play Console App Access instructions.

## 2. Review Video Script

Target length: 2-4 minutes, plus a separate 30-second-or-shorter background-location video if Play requests that limit.

1. **Opening title:** “Sentinel parental-control review demonstration - production package [PACKAGE].”
2. **Parent role:** Launch app, choose Parent, sign in, and show the dashboard.
3. **Guardian consent:** Open pairing, slowly show the complete consent wording, check the box, and display the QR code.
4. **Child disclosure:** Launch child flow, slowly show all monitoring disclosure text and privacy link.
5. **Accessibility decline path:** Open the standalone Accessibility disclosure, decline/back out, then trigger it again.
6. **Accessibility accept path:** Accept, open Android settings, enable Sentinel, and return to the app.
7. **Other access:** Show Usage Access and installed-app explanation. Show location disclosure and grant flow in the location-specific video.
8. **Pairing:** Scan the QR code and show both devices reporting the link.
9. **Installed apps:** On the parent device, show the full child app list and select a test app.
10. **Enforcement:** Open the restricted test app on the child device and show the deterministic Home action.
11. **Usage:** Remove/expire the restriction as appropriate, use the test app, and show the usage update on the parent.
12. **Location:** Put the child app in the background, refresh location from the parent, and show the result.
13. **Transparency:** Show the ongoing Sentinel notification and the child dashboard disclosure entry.
14. **Close:** State that SMS/call logs are not accessed, Device Admin is optional, and data is limited to the linked parent account and service providers.

## 3. Data Safety Consistency Report

| Claim | Code status | Documentation action |
|---|---|---|
| No SMS/call-log collection | Confirmed by manifest/source audit | Safe to state |
| Installed apps collected remotely | Confirmed | Must declare Installed apps and explain linked-parent use |
| Usage/screen time collected remotely | Confirmed | Must declare App activity |
| Precise background location collected | Confirmed | Must declare precise location and background use |
| Analytics collected | Confirmed, explicitly enabled | Must declare Analytics or remove SDK/enablement |
| Crash diagnostics collected | Confirmed | Must declare crash logs/diagnostics |
| Account deletion supported | Code confirmed | Deployment/end-to-end success must be tested before claiming operational availability |
| Retention periods | Code confirmed | Scheduled deployment not confirmed; do not guarantee until verified |
| End-to-end encryption | Not implemented | Must not claim |
| App Check | Not found | Must not claim; recommended implementation |
| Accessibility reads only package names | False in current source | P0 code fix before using declaration |
| Device Admin wipes/locks/resets | False | State that no policies are used |
| No advertising | No ad SDK found | Safe for audited source; re-check final AAB |

---

# Phase 5 - Final Risk Report

## P0 - Must Fix

1. Remove Accessibility Settings-screen content inspection and all deactivation/settings blocking.
2. Minimize Accessibility events, flags, and capabilities; remove key filtering and window-content retrieval unless strictly proven necessary.
3. Remove `KILL_BACKGROUND_PROCESSES` and its enforcement calls.
4. Add a standalone Accessibility prominent disclosure and affirmative accept/decline flow that accurately matches the final code.
5. Verify and deploy least-privilege Realtime Database and Firestore rules; current production rules are not confirmed.
6. Produce the final AAB with the final non-`com.example.*` package and re-audit its merged manifest/dependencies.
7. Obtain Play approval for `QUERY_ALL_PACKAGES` and background location, supported by videos showing their indispensable core use.

## P1 - Should Fix Before Submission

1. Add Firebase App Check with Play Integrity and enforce it after staged validation.
2. Verify the Appwrite retention schedule is deployed and monitor successful runs.
3. Decide whether Firebase Analytics is necessary in a child-monitoring app; removal improves privacy posture.
4. Consider making continuous background location separately optional with graceful degradation.
5. Consider removing Device Admin or keep it clearly optional and limited.
6. Audit every `specialUse` foreground service and subtype against actual runtime behavior and Play declarations.
7. Reduce wake-lock duration and verify release in every path.
8. Separate OTP and privacy Appwrite functions/permissions to reduce blast radius.
9. Publish a public privacy-policy URL and account-deletion URL.
10. Replace hard-coded support email in the app with the final business support/privacy contacts.

## P2 - Nice to Have

1. Remove “bulletproof,” “nuclear,” and similar internal naming before screenshots/log reviews, though internal comments alone are not store claims.
2. Add automated tests for deletion coverage, retention, consent finalization, and denied-permission flows.
3. Add a production log policy and disable sensitive debug logging.
4. Add an internal data-flow diagram and processor register.

## Required Final AAB Audit Inputs

When the release AAB is generated, provide:

- Signed release `.aab`.
- Final package name and version code/name.
- `mapping.txt`.
- `output-metadata.json` if generated.
- Final merged manifest.
- `releaseRuntimeClasspath` dependency report.
- Signing certificate SHA-256 fingerprint, never the keystore/private key/password.
- Production Realtime Database and Firestore rules.
- Appwrite deployment settings with secrets removed and proof of the retention schedule.
- Privacy-policy and account-deletion URLs.
- Final Data Safety draft, declarations, listing, screenshots, reviewer credentials, and videos.

## Current Official Policy References

- AccessibilityService API: https://support.google.com/googleplay/android-developer/answer/10964491
- Broad package visibility: https://support.google.com/googleplay/android-developer/answer/10158779
- Monitoring-tool flag: https://support.google.com/googleplay/android-developer/answer/12955211
- Background location: https://support.google.com/googleplay/android-developer/answer/9799150
