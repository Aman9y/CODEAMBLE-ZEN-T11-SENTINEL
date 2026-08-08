# Sentinel / FamilyGuard Google Play Publishing Pack

Audit date: 2026-06-11

Status: **Not ready for production submission.** This pack is based on the checked-in Android app, manifests, resources, Gradle dependencies, and backend function. Items described as required fixes must be completed before the corresponding wording is published.

## Audit Basis

Confirmed implementation:

- Android application ID and namespace: `online.monarchlabs.sentinel`.
- Target SDK 35; minimum SDK 26.
- Parent accounts use Firebase Authentication. Signup collects parent name, email, phone number, and password.
- Firebase Realtime Database is used for parent-child links, device state, commands, installed apps, app events, usage records, timers, restrictions, location, and QR pairing records.
- Firebase Analytics and Firebase Crashlytics collection are explicitly enabled at application startup.
- Appwrite functions are used in email OTP workflows. The checked-in backend function also accesses Firebase Admin and can send email through SMTP/Gmail or another Appwrite email function.
- A child name is stored locally before permission onboarding and is later included in linked-device records.
- QR pairing uses Camera permission and ZXing. QR payloads include a share key, parent device ID, and parent device name. A parent QR key is derived partly from `ANDROID_ID` and stored locally.
- Precise location, accuracy, timestamp, and status are uploaded to `child_location/{deviceId}`. The child service requests high-accuracy updates approximately every two minutes and supports parent-requested refreshes.
- Installed app inventory is uploaded to Firebase, including package name, app name, system-app status, update timestamp, and a Base64 app icon.
- Usage Stats and accessibility events are used to identify foreground apps and enforce app restrictions. Usage records are uploaded to Firebase.
- Device Admin XML declares no device-admin policies. Device Admin is used only to make deactivation necessary before uninstall.
- Device Admin is presented as optional, but Accessibility, Usage Access, notifications, battery-optimization exemption, and background location are currently treated as mandatory in child onboarding.
- App backup and device transfer are disabled for app-private data.
- No SMS permission is present.
- No in-app account deletion implementation was found. Logout does not delete the Firebase Auth account or all cloud records.
- No automatic server-side retention or expiry policy was confirmed for general account, location, installed-app, or usage data. OTP records have a five-minute validity but are not confirmed to be automatically deleted.
- Actual deployed Firebase Realtime Database rules were not found. `docs/FIREBASE_SCHEMA.md` contains recommended rules only and is not proof of deployed access control.

Critical discrepancies:

- Accessibility reads the visible text and content descriptions of Android Settings screens and blocks access to Sentinel's Accessibility and Device Admin detail pages by sending the user Home. This must be removed before Play submission.
- The current Accessibility configuration requests window-content retrieval, view IDs, non-important views, and key-event filtering. These capabilities are broader than necessary for package-based app blocking.
- `Firebase Analytics` is included and explicitly enabled. Data Safety must disclose analytics unless it is removed and disabled before release.
- The in-app Terms screen makes unsupported claims about end-to-end encryption, AES-256-GCM, certificate pinning, MFA, biometrics, machine learning, Kafka, Redis, MongoDB, Kubernetes, Cloudflare, geofencing, network traffic analysis, and deep packet inspection. Replace it completely.
- `isMonitoringTool=child_monitoring` metadata is absent from the manifest.
- `READ_PHONE_STATE`, legacy storage permissions, `GET_TASKS`, `REORDER_TASKS`, `SYSTEM_ALERT_WINDOW`, and `SET_ALARM` are declared without confirmed necessary usage. `KILL_BACKGROUND_PROCESSES` is used, but is not a reliable way to stop other apps and should be removed with that code path.
- Both `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` are declared. Use only the permission model for which the app qualifies; parental-control timer resets are unlikely to justify `USE_EXACT_ALARM` automatic grant treatment.

---

# 1. Privacy Policy

## Privacy Policy for Sentinel / FamilyGuard

Effective date: [EFFECTIVE DATE]

Sentinel / FamilyGuard (the “App”) is provided by **[LEGAL ENTITY NAME]**, located at **[BUSINESS ADDRESS]** (“we,” “us,” or “our”). Privacy questions and requests may be sent to **[PRIVACY CONTACT EMAIL]**.

The App is parental-control software intended only for a parent or legal guardian to supervise a child device that the parent or guardian is authorized to manage. It must not be used to monitor a spouse, partner, employee, other adult, or any person without lawful authority and transparent notice.

## Information We Collect

We collect and process the following information when the relevant features are used:

- **Parent account information:** parent name, email address, phone number, Firebase user ID, authentication status, account creation information, and parent device information. We use this to create and secure the parent account, authenticate the parent, recover access, and associate authorized child devices.
- **Authentication information:** passwords are submitted to Firebase Authentication. Email OTP workflows process the email address, a short-lived OTP, an OTP hash and salt, attempt count, timestamps, and a Firebase custom authentication token. We use this to verify account access. We do not claim that the App itself can read a Firebase-stored plaintext password.
- **Child and device identifiers:** child-provided display name, child device ID, parent device ID, Android-derived device identifiers, device name, model, manufacturer, Android/app information, connection status, last-seen time, and pairing relationships. We use these to identify linked devices, maintain the authorized family connection, and show device status.
- **Precise location:** latitude, longitude, accuracy, timestamp, GPS/permission status, and parent-requested location updates. The child device can collect and upload location while the App is closed or not in use. We use this to display the linked child device's location to the authorized parent account.
- **App activity and usage:** foreground-app events, package names, app names, per-app usage duration, total screen time, dates, timestamps, timer state, and app restriction events. We use this to provide screen-time reports, app limits, schedules, focus modes, and blocking.
- **Installed apps:** package name, app name, system-app status, app icon, app count, installation/removal events, and timestamps. We use this to let the authorized parent select apps for blocking, schedules, and limits and to keep that list current.
- **Parental-control settings:** blocked-app lists, schedules, timers, focus-mode settings, daily limits, uninstall-protection setting, commands, and completion/status information. We use this to apply and synchronize the controls selected by the parent.
- **QR pairing data:** QR share key, parent device ID and name, parent account identifier/name, linked child device information, pairing status, and timestamps. We use this to link a child device to the intended parent account.
- **Diagnostics and analytics:** Firebase Analytics may collect app interaction and device-related analytics, and Firebase Crashlytics may collect crash logs, diagnostics, SDK version, component/event labels, and exception details. We use this to understand app operation and diagnose failures. **Release note:** this statement reflects the current code. Remove Analytics and disable collection if analytics is not intended for production.

The App does not request SMS or call-log access in the audited release.

## How We Use Information

We use information only to:

- authenticate parent accounts and protect account access;
- pair and maintain authorized parent-child device links;
- show installed apps, screen-time information, device status, and location to the linked parent;
- apply parent-selected app restrictions, schedules, focus modes, and time limits;
- send operational notifications and maintain required foreground services;
- detect installations/removals and synchronize parental-control state;
- provide support, prevent abuse, and diagnose crashes or service failures; and
- comply with law and enforce our terms.

We do not sell personal information. We do not use child data, location, installed-app inventory, or usage data for behavioral advertising. The audited App contains no advertising SDK. We do not use this data to create advertising profiles.

## Local and Remote Storage

The App stores session data, pairing state, device identifiers, timer state, blocked-app state, and related settings in app-private local storage. Android cloud backup and device-to-device transfer are disabled for this app data.

The App remotely stores account and family-control data in Firebase services, including Firebase Authentication and Firebase Realtime Database. Firebase Analytics and Crashlytics are also enabled in the audited build. Email OTP processing uses Appwrite functions and may use an email delivery provider configured by the operator. Google Play services are used for location, and Google Maps is used to display location.

Information is transmitted over encrypted network connections provided by HTTPS/TLS-capable Firebase, Appwrite, Google, and email-service endpoints. The App does **not** currently implement independently verified end-to-end encryption or certificate pinning, and we do not claim that it does.

These providers process data on our behalf under their applicable terms. Their infrastructure may process information in countries other than the user's country.

## Sharing

We disclose family-control information to the parent account linked through the App. We also provide data to service providers that operate authentication, database, analytics, crash reporting, location/maps, function execution, and email delivery for us. We may disclose information when required by law, to protect users, or as part of a corporate transaction subject to appropriate notice and safeguards.

Installed-app inventory, usage information, and child location are not sold and are not shared with advertisers or data brokers.

## Children and Parent or Legal Guardian Consent

The App is not offered for independent use by a child. A parent or legal guardian must create or control the parent account, install or authorize the child-device setup, review the disclosures, consent to the collection and use described here, and enable Android permissions on a device the parent or guardian is legally authorized to manage.

The linked child should receive age-appropriate notice that the device is managed and that app usage, installed apps, device status, and location may be visible to the parent or legal guardian. The App must remain visible on the child device and must not be represented as hidden or covert monitoring software.

## COPPA-Oriented Notice

For users in the United States, we rely on verifiable authorization from the parent or legal guardian before collecting personal information from a child device. We collect only information reasonably necessary for the parental-control features described above. We do not condition a child's participation on providing information beyond what is reasonably necessary for those features, and we do not use child information for behavioral advertising.

A parent or legal guardian may request access to, correction of, or deletion of the child's information and may revoke consent by contacting **[PRIVACY CONTACT EMAIL]**. Revoking consent may require unlinking the device and will disable features that depend on remote data.

**Implementation requirement before launch:** add a recorded, affirmative parent/legal-guardian consent step before pairing and preserve consent version/time. The current code does not confirm such a record.

## GDPR-Oriented Notice

Where the GDPR or UK GDPR applies, **[LEGAL ENTITY NAME]** is the controller for information processed to provide the App. Depending on the context, processing is based on performance of a contract with the parent account holder, the parent's or legal guardian's consent, our legitimate interests in securing and operating the service, and compliance with legal obligations. Child consent rules vary by country; the parent or legal guardian must have authority to provide the required authorization.

Data subjects may request access, correction, deletion, restriction, portability, or objection, and may withdraw consent where consent is the basis for processing. Requests may be sent to **[PRIVACY CONTACT EMAIL]**. Users may also complain to their local data-protection authority.

International transfers must be protected through the service providers' applicable transfer mechanisms. **Not confirmed:** the operator's executed data-processing agreements and transfer terms must be verified before launch.

## Retention

The current implementation does not confirm automatic deletion periods for general Firebase account, location, installed-app, usage, or parental-control records. Accordingly, those records may remain until the account or linked-device data is deleted. Short-lived email OTPs expire for authentication purposes after five minutes, although automatic physical deletion of expired OTP records is not confirmed.

Before production launch, **[LEGAL ENTITY NAME]** must implement and publish a concrete retention schedule. Recommended production schedule: current/last-known location for no more than 30 days; detailed app usage and app events for no more than 12 months; inactive QR pairing records for no more than 24 hours; diagnostics according to Firebase retention controls; and account/control data until account deletion, followed by deletion from active systems within 30 days and backup expiry within 90 days. Do not publish these recommended periods until implemented and verified.

## Account and Data Deletion

The audited App provides logout and device-disconnection functions, but no complete in-app parent-account deletion flow was found. Logout does not delete the Firebase Authentication account or all cloud data.

Until an in-app deletion flow is implemented, a parent may request account and associated child-data deletion by emailing **[PRIVACY CONTACT EMAIL]** from the registered address and including the account email and linked device names. We will verify the requester and delete or de-identify the account, pairing records, location, installed-app inventory, usage history, control settings, and related records unless retention is required by law.

**Required before Play submission:** provide both an in-app account-deletion path and a public account-deletion web URL, and implement server-side deletion across Firebase Auth, all Realtime Database paths, Appwrite/OTP data, and configured processors.

## Security

We use app-private local storage, disabled Android backup, authenticated backend services, and encrypted network transport. No method is completely secure. Actual deployed Firebase rules, production credentials, access logging, deletion jobs, and incident-response procedures must be reviewed before launch.

## Changes and Contact

We may update this policy and will revise the effective date. Material changes affecting child information or consent will be presented to the parent or legal guardian as required.

Contact: **[LEGAL ENTITY NAME]**, **[BUSINESS ADDRESS]**, **[PRIVACY CONTACT EMAIL]**, **[SUPPORT URL]**.

---

# 2. Google Play Data Safety Form

Use the following answers for the **currently audited build**. “Shared: No” assumes Firebase, Appwrite, Google Maps/Location, Crashlytics, Analytics, and email delivery are service providers processing data on the developer's behalf and not using it for their own independent purposes. Confirm contracts and SDK behavior before submission.

| Data type | Collected | Shared | Purpose | Required or optional | Ephemeral | Encrypted in transit | Deletion request |
|---|---:|---:|---|---|---:|---:|---:|
| Parent name, email, phone | Yes | No | Account management, authentication, family linking, support | Required for current parent signup | No | Yes | Yes, but complete mechanism must be implemented |
| Firebase user ID/account identifier | Yes | No | Account management, security, device association | Required | No | Yes | Yes |
| Password/authentication credentials | Yes, processed by Firebase Auth | No | Authentication | Required for password login/signup | No | Yes | Yes through account deletion |
| Email OTP data and custom auth token | Yes | No | Authentication/security | Required only for OTP flows | OTP validity is temporary; stored record deletion not confirmed | Yes | Yes |
| Child display name | Yes | No | Identify the linked child device to the parent | Required in current child flow | No | Yes when uploaded | Yes |
| Child/parent device IDs, Android-derived identifiers | Yes | No | Pairing, device identity, service synchronization, fraud/security | Required | No | Yes | Yes |
| Precise location and accuracy | Yes | No | Linked-child location display and refresh | Required by current child onboarding; should be separately consented | No; latest value is remotely stored | Yes | Yes |
| App activity and foreground-app events | Yes | No | Screen-time reports, limits, blocking, schedules | Required for core controls | No | Yes | Yes |
| Usage duration/screen time | Yes | No | Usage reports and time-limit enforcement | Required for core controls | No | Yes | Yes |
| Installed apps, package names, app names, icons | Yes | No | Parent app selection, restrictions, schedules, limits | Required for app-management features | No | Yes | Yes |
| Install/uninstall events | Yes | No | Keep parent app list current and show status | Required for app-management features | No | Yes | Yes |
| Device model, manufacturer, OS/app/device status | Yes | No | Device display, compatibility, connection state, diagnostics | Required | No | Yes | Yes |
| Crash logs and diagnostics | Yes | No | App functionality, diagnostics, security/compatibility | Automatic in current code; disclose as required | No | Yes | Yes, subject to processor controls |
| Analytics/app interactions/device analytics | Yes | No | Analytics | Automatic in current code | No | Yes | Yes, subject to processor controls |
| QR share key and pairing payload | Yes | No | Parent-child linking | Required only for QR pairing | No; parent key is persistent and Firebase records may persist | Yes | Yes |
| Parental-control settings, blocked apps, timers, schedules | Yes | No | App functionality and synchronization | Required for selected controls | No | Yes | Yes |
| Notification settings/status | Yes | No | Operational notifications and permission status | Current onboarding treats notifications as required | Mostly local; status/events may be remote | Yes when transmitted | Yes |
| Device Admin/uninstall-protection state | Yes | No | Parent-selected uninstall-protection state | Device Admin itself is optional | No | Yes | Yes |

Data Safety purpose selections likely needed:

- **App functionality:** account data, identifiers, location, usage, installed apps, pairing, controls, device status.
- **Account management:** name, email, phone, credentials, Firebase UID.
- **Analytics:** Firebase Analytics data in the current build.
- **Fraud prevention, security, and compliance:** authentication tokens/OTP, connection/security diagnostics where applicable.
- **Developer communications:** email address only if used for OTP, password reset, or support communications; do not select marketing unless implemented.

Not collected based on the audited code: SMS/MMS, call logs, contacts, calendar, microphone/audio recordings, financial information, health data, photos/videos from the library, and files/documents. Camera frames are used for QR scanning; no camera image upload or storage was found.

Important: Google Play may categorize installed-app inventory under **App activity > Installed apps**, location under **Location > Precise location**, crash data under **App info and performance**, and IDs under **Device or other identifiers**.

---

# 3. Permission Declaration Justifications

## Accessibility Service

**Play Console wording after required remediation:**

Sentinel is a parental-control app. On a child device, its Accessibility Service observes page views and taps in app and other actions (window-change events and the foreground package name) so it can immediately detect when a parent-restricted app is opened. It then returns the child device to the Home screen to enforce parent-configured app blocks and screen-time limits. This is core functionality because timely app blocking cannot be delivered reliably by ordinary app UI alone. The benefit is consistent enforcement of schedules, focus modes, and daily app limits selected by the parent or legal guardian. The service must not collect typed text, passwords, payment information, private messages, or unrelated screen content. Foreground package information may be synchronized with the linked parent account as part of usage and control functionality. A separate prominent disclosure and affirmative parent/legal-guardian consent are required before Android Accessibility settings open.

**Current-code warning:** do not submit this declaration until Settings-screen text scanning, content-description collection, deactivation blocking, and unnecessary accessibility flags/capabilities are removed. Otherwise the declaration would be false.

## QUERY_ALL_PACKAGES

Sentinel's core parental-control feature requires the complete list of launchable apps installed on the linked child device. The parent uses that list to select any installed app for blocking, focus modes, schedules, and daily limits, including apps installed after pairing. The app sends package name, app name, system-app status, icon, and update/install status to the linked parent account through Firebase solely to provide these family-control features. The inventory is not sold and is not used for advertising. Targeted package queries are insufficient because Sentinel cannot know in advance which third-party apps are installed now or will be installed later, and omitting unknown apps would make parent selection and enforcement incomplete.

Risk note: parental-control apps are not expressly listed in Google's standard permitted examples. Approval is not guaranteed; the listing and reviewer video must demonstrate that broad app discovery is indispensable to the primary user-facing function.

## Usage Stats

Usage Access reads per-app foreground activity and usage duration on the child device. It is used to calculate screen time, produce linked-parent reports, apply daily limits, and determine when a restricted app has reached its allowance. This is core parental-control functionality. Package names and usage durations leave the child device and are stored in Firebase for display to the linked parent account. It is not used for advertising. Parent/legal-guardian authorization and a clear child-device disclosure are required.

## Device Admin

Device Admin is optional and is used only so Android requires the user to deactivate the admin component before uninstalling the app. No lock-screen, password reset, wipe-data, camera-disable, storage-encryption, or other Device Admin policy is declared or used. The benefit is parent-visible resistance to casual removal of controls on a managed child device. Device Admin status and the parent-selected uninstall-protection setting may be synchronized through Firebase; Device Admin does not itself upload device content. Parent/legal-guardian authorization is required. The app must never use Accessibility to prevent a user from opening the deactivation screen.

## Location

Sentinel collects the linked child device's precise location, accuracy, timestamp, and GPS/permission status to display current location on the authorized parent's map and support a parent-requested refresh. Background access is used because location updates continue while the child app is closed or not in use. This feature provides a family-safety benefit and must be prominently described in the store listing. Location leaves the device and is stored in Firebase under the child device ID for access by the linked parent account. It is not used for ads or analytics. Parent/legal-guardian authorization and a dedicated prominent background-location disclosure are required. Consider making continuous location separately optional and offering one-time parent-requested location to reduce scope.

## Camera

Camera access is used only on the child-device pairing screen to scan a QR code shown by the parent device. The QR code links the child device to the intended parent account. Camera frames are processed by the embedded ZXing scanner; no photo or video storage or upload was found. Camera access is required only when QR pairing is selected. Parent/legal-guardian authorization is required as part of pairing.

## Foreground Services

Foreground services maintain active parental-control functions that users expect to continue outside the visible UI: app-limit monitoring, restriction synchronization, parent commands, child location updates, connection status, timer state, permission status, and persistent timer notifications. The user receives an ongoing notification while applicable services run. Data-sync services transmit usage, control, connection, app, and status data to Firebase; the location service transmits child location. Parent/legal-guardian authorization is required. Every declared foreground-service type and special-use subtype must match actual behavior, and services that do not call `startForeground()` promptly should not be declared or started as foreground services.

## Notifications

Notification permission is used for ongoing foreground-service notices, timer status, focus-mode start/end, limit expiration, and system/permission status. Notifications provide transparency and timely parental-control information. Notification text is displayed locally; related timer/status data may already be synchronized through Firebase. Parent/legal-guardian authorization is required on the child device. This should not block pairing when the platform allows core controls to function without optional notifications unless technically necessary and accurately explained.

## Exact Alarms

Exact alarms are used for time-sensitive daily limit resets at local midnight and scheduled service/timer checks. The user benefit is predictable restoration of daily allowances and enforcement of parent-created schedules. Alarm scheduling itself does not transmit data, though the triggered work may synchronize timer state with Firebase. Parent/legal-guardian authorization is required for the parental-control setup. Prefer `SCHEDULE_EXACT_ALARM` with user-granted special access or inexact WorkManager/AlarmManager where acceptable. Remove `USE_EXACT_ALARM` unless the app clearly qualifies under current Play policy.

## Boot Completed

Boot Completed is used to restore previously enabled child-device parental-control services, schedules, timers, connection monitoring, and status notifications after a device restart. Without restart recovery, existing parent-selected limits could silently stop applying. Boot handling may resume Firebase synchronization and location only when the corresponding feature and permission were already enabled. Parent/legal-guardian authorization is established during setup.

## Wake Lock

Partial wake locks are used for bounded periods while timer resets, restriction checks, and child-device synchronization complete when the screen is off. The benefit is reliable completion of time-sensitive control work. Wake locks do not themselves collect or transmit data; the work they protect may synchronize controls, usage, or location. Parent/legal-guardian authorization is required through setup. Audit all wake locks for the shortest practical timeout and guaranteed release.

## Battery Optimization Exemption

The app requests exemption from battery optimization to reduce interruption of child-device location updates, app-limit enforcement, timer resets, and parent-child synchronization. This is a special-access request, not a runtime permission, and must be requested with a clear explanation and direct user action. Data handled by the protected services may leave the device as disclosed above. Parent/legal-guardian authorization is required. The current onboarding makes the exemption mandatory; Google/OEM review risk is lower if the app degrades gracefully and clearly explains which reliability features may be affected when declined.

---

# 4. Accessibility Service Declaration

Sentinel is not an accessibility tool and will not declare `isAccessibilityTool=true`.

Sentinel uses Android Accessibility Service only on a transparently managed child device to enforce parent-selected app blocking and screen-time limits. The service receives window-state/window-change events and uses the event's package name to determine whether the currently opened app is on the parent's restricted list. When a restricted app is opened or its allowed time has expired, Sentinel performs a deterministic Home action so the restricted app is no longer foregrounded.

The service is not used to spy on the child or any other person. It is not used to read or collect passwords, payment details, private messages, emails, web content, keystrokes, form entries, or unrelated on-screen content. It is not used for advertising, behavioral profiling, analytics, or marketing. Only app identity and enforcement events needed for parental-control functionality may be synchronized to the linked parent account.

Before the user is sent to Android Accessibility settings, Sentinel displays a separate prominent in-app disclosure explaining the data accessed, the purpose, whether it is sent to the linked parent, and how to decline. Enabling the service requires an affirmative action by the parent or legal guardian who is authorized to manage the child device.

**Mandatory code alignment:** restrict events and flags to the minimum necessary; set `canRetrieveWindowContent=false` if package-name events are sufficient; remove key-event filtering; remove all node-tree text/content-description scanning; and never block Accessibility, Device Admin, App Info, uninstall, force-stop, permission, or other system settings.

---

# 5. QUERY_ALL_PACKAGES Declaration

Sentinel is a parental-control app whose core purpose includes letting a parent or legal guardian choose which apps on a linked child device are allowed, blocked, scheduled, or subject to daily time limits. To present that selection interface accurately, Sentinel must discover the complete inventory of launchable apps installed on the child device, including newly installed and uncommon third-party apps.

The inventory is used only for parental-control functionality. It is not sold, licensed to data brokers, or used for advertising, marketing, or analytics. Package name, app name, system-app status, app icon, and install/remove status are uploaded to Firebase and made available to the authorized linked parent account so the parent can configure controls.

Targeted `<queries>` declarations are insufficient because the set of apps installed on a child device is not known when Sentinel is built. A fixed package list would omit apps that are newly released, region-specific, sideloaded, or installed after pairing, preventing the parent from selecting and managing them and making the core app-control feature incomplete.

---

# 6. Device Admin Declaration and Policy Audit

`res/xml/device_admin.xml` contains an empty `<uses-policies />` element.

| Device Admin policy | Declared | Code use found | Action |
|---|---:|---:|---|
| Limit password | No | No | Keep absent |
| Watch login | No | No | Keep absent |
| Reset password | No | No | Keep absent. The app's account password-reset UI is unrelated to Device Admin |
| Force lock | No | No `lockNow()` found | Keep absent |
| Wipe data | No | No `wipeData()` found | Keep absent |
| Expire password | No | No | Keep absent |
| Encrypted storage | No | No | Keep absent |
| Disable camera | No | No `setCameraDisabled()` found | Keep absent |

Actual confirmed use: launch `ACTION_ADD_DEVICE_ADMIN`, test whether the admin component is active, and receive enable/disable callbacks. Active Device Admin creates an Android deactivation step before uninstall.

Recommendation: keep the empty policy declaration if Device Admin remains, describe it only as optional uninstall deactivation friction, and remove any wording implying lock, wipe, password, camera, or full device-management powers. Remove the Accessibility logic that blocks the Device Admin deactivation screen. If uninstall friction is not essential, remove Device Admin entirely because it materially increases monitoring-app review risk.

---

# 7. Store Listing Content

## Title Options

- Sentinel Parental Controls
- FamilyGuard Parental Controls
- Sentinel Family Screen Time

Avoid titles containing “spy,” “secret,” “hidden,” “tracker,” or “stalker.” Confirm trademark availability before selecting a name.

## Short Description

`Parental controls for app limits, screen time, and family location.`

69 characters including spaces.

## Full Description

Sentinel helps parents and legal guardians guide a child's Android device use with clear, visible parental controls.

Link a child device to the parent's account using a QR code, then manage app access, daily limits, focus schedules, screen-time information, and family location from the linked parent experience.

Key features:

- View screen-time and per-app usage for a linked child device
- Choose installed apps for blocking, schedules, and daily limits
- Create focus periods for study, rest, or family time
- Receive device, timer, and control status updates
- View the linked child device's location when location access is enabled
- Restore enabled controls after the child device restarts
- Keep the child app visible with ongoing system notifications where required

Important permission information:

Sentinel uses Usage Access to calculate app usage and screen time. It uses Accessibility Service only to detect a restricted app opening and return the device to Home so parent-selected limits can be enforced. It uses broad app visibility to show the full installed-app list for parent selection. If enabled, background location is collected even when the app is closed or not in use and is shared with the linked parent account through our service. Device Admin is optional and only adds an Android deactivation step before uninstall.

Sentinel does not access SMS messages or call logs and contains no behavioral advertising. Installed-app, usage, and location information is not sold.

**For parents and legal guardians only.** Use Sentinel only on a child's device that you are legally authorized to manage. The child should be informed in an age-appropriate way that the device is managed. Sentinel must not be used for hidden monitoring of adults, partners, employees, or any person without lawful authority and notice.

## Feature List

- Parent-child QR linking
- Installed-app selection
- App blocking and focus modes
- Per-app daily limits
- Screen-time and usage reports
- Parent-visible child-device location
- Install/remove status updates
- Timer and service notifications
- Optional Device Admin uninstall deactivation step

## Privacy-Friendly Wording

Prefer: “linked child device,” “parent-selected controls,” “screen-time information,” “family location,” “visible device management,” and “parent or legal guardian authorization.”

Avoid: “secretly monitor,” “stealth,” “undetectable,” “spy,” “read everything,” “track anyone,” “bulletproof,” “unremovable,” “prevent the user from disabling,” or “surveillance.” Remove these themes from comments, UI, screenshots, website copy, and reviewer materials where user-facing or behaviorally relevant.

## Content Rating Notes

- Utility/parental-control application; no game violence, sexual content, gambling, drugs, or user-generated social content was found.
- The app displays installed-app names/icons and usage selected by the device owner; third-party app titles/icons could indirectly reflect mature apps installed on the child device.
- Location sharing occurs within the linked family account.
- The app is intended for adults acting as parents/legal guardians, even though it is installed on a child's device.

---

# 8. In-App Disclosure Screens

Each disclosure should appear immediately before the relevant Android settings/runtime request. Accessibility must have its own affirmative consent and must not be bundled with other permissions.

## Accessibility Disclosure

**Title:** App blocking with Accessibility

**Body:** Sentinel uses Android Accessibility Service on this child device to observe page views and taps in app and other actions to detect when a restricted app is opened. If that app is blocked or has reached a parent-set time limit, Sentinel returns the device to the Home screen. App package names and enforcement events may be sent securely to the linked parent account for screen-time and parental-control features. Sentinel does not use Accessibility to read passwords, payment details, private messages, typed text, or unrelated screen content, and it does not use Accessibility for advertising.

**Consent:** `I am the parent or legal guardian authorized to manage this device, and I agree to enable Accessibility for app-limit enforcement.`

Buttons: `Agree and open settings` / `Not now`

## Usage Access Disclosure

**Title:** Screen-time and app usage

**Body:** Sentinel uses Usage Access to read which apps are used and for how long on this child device. Package names, app names, usage duration, and timestamps are used to create screen-time reports and enforce parent-set app limits. This information is uploaded to our Firebase service and shown to the linked parent account. It is not sold or used for advertising.

Buttons: `Continue to Usage Access` / `Not now`

## Background Location Disclosure

**Title:** Child-device location

**Body:** Sentinel collects precise location data to show this linked child device on the parent's family map and respond to parent-requested location refreshes, even when the app is closed or not in use. Location, accuracy, and timestamp are uploaded to our Firebase service and are available to the linked parent account. Location is not sold or used for advertising.

Buttons: `Continue to location settings` / `Not now`

## Device Admin Disclosure

**Title:** Optional uninstall protection

**Body:** If you enable Device Admin, Android will require this admin component to be deactivated before Sentinel can be uninstalled. Sentinel does not use Device Admin to lock the device, reset passwords, erase data, or disable the camera. This feature is optional and should be enabled only by the parent or legal guardian authorized to manage the child device.

Buttons: `Enable Device Admin` / `Skip`

## Installed Apps / QUERY_ALL_PACKAGES Disclosure

**Title:** Installed-app list

**Body:** Sentinel reads the complete list of launchable apps installed on this child device so the linked parent can choose apps for blocking, schedules, focus modes, and daily limits. App name, package name, system-app status, icon, and install/remove status are uploaded to our Firebase service and shown to the linked parent account. The list is not sold or used for advertising or analytics.

Buttons: `Continue` / `Cancel setup`

## Parent Consent Before Pairing

**Title:** Parent or legal guardian consent

**Body:** By pairing this child device, I confirm that I am the child's parent or legal guardian and am legally authorized to manage this device. I understand that Sentinel will collect and send the linked parent account information about this device, including installed apps, app usage and screen time, device identifiers and status, parental-control settings, and precise location when location access is enabled. I will provide the child with age-appropriate notice that the device is managed. I understand that Sentinel is not for hidden monitoring of adults or any person without lawful authority and notice. I have reviewed the Privacy Policy.

Checkboxes:

- `I confirm that I am the authorized parent or legal guardian.`
- `I consent to the collection and use described above and in the Privacy Policy.`

Buttons: `Pair child device` / `Cancel`

Record consent version, Firebase UID, child device ID, timestamp, and policy version on the server.

---

# 9. Final Pre-Submission Checklist

## Manifest and Code

- [x] Rename the namespace/application ID and hard-coded package/action strings to `online.monarchlabs.sentinel`.
- [ ] Add manifest-level `<meta-data android:name="isMonitoringTool" android:value="child_monitoring" />` to every release track/version.
- [ ] Remove `BIND_ACCESSIBILITY_SERVICE` from `<uses-permission>`; it is a service binding permission and should remain only on the service's `android:permission` attribute.
- [ ] Remove Accessibility logic that reads Settings text/content descriptions or blocks Accessibility, Device Admin, App Info, uninstall, permissions, or force-stop screens.
- [ ] Minimize Accessibility event types/flags; remove key-event filtering and window-content retrieval unless a documented enforcement path proves them essential.
- [ ] Remove unconfirmed `READ_PHONE_STATE`.
- [ ] Remove legacy `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`; no necessary use was found and target SDK 35 makes them inappropriate for this flow.
- [ ] Remove unconfirmed `SYSTEM_ALERT_WINDOW` and any overlay declaration/UI if no overlay feature exists.
- [ ] Remove obsolete/unnecessary `GET_TASKS` and `REORDER_TASKS` unless a current API use is proven.
- [ ] Remove `KILL_BACKGROUND_PROCESSES` and force-stop shell attempts; enforce only through the permitted Accessibility behavior.
- [ ] Remove `SET_ALARM` unless a distinct alarm-clock intent feature is implemented.
- [ ] Keep only one appropriate exact-alarm permission. Prefer `SCHEDULE_EXACT_ALARM` or redesign with inexact alarms/WorkManager; remove `USE_EXACT_ALARM` unless eligibility is confirmed.
- [ ] Verify each foreground service type, special-use subtype, start path, ongoing notification, and stop condition against current FGS policy.
- [ ] Consider making notifications, battery exemption, continuous location, and Device Admin optional with graceful degradation.
- [x] Replace hard-coded legacy package comparisons used to protect/skip the Sentinel package.
- [ ] Add a clear child-visible “This device is managed by Sentinel” status and keep required notifications visible.

## Data and Security

- [ ] Deploy and independently review restrictive Firebase rules. Recommended documentation is not deployment proof.
- [ ] Ensure each parent can read/write only their own account and explicitly linked child records. Current documented examples contain broad authenticated reads/writes that would be unacceptable if deployed.
- [ ] Implement server-side deletion across Firebase Auth and every database path keyed by UID/device ID/share key.
- [ ] Implement retention/TTL jobs for QR records, OTP records, location history, usage history, app events, diagnostics, and orphaned devices.
- [ ] Rotate and restrict Firebase, Maps, Appwrite, SMTP, and service-account credentials; restrict Maps key by package/signing certificate.
- [ ] Confirm Appwrite function authentication and prevent unauthenticated OTP abuse/account enumeration; add rate limiting.
- [ ] Remove Firebase Analytics or keep it and accurately declare Analytics in Data Safety and the privacy policy. Obtain any consent required by region/child context.
- [ ] Review Crashlytics custom keys/logs to ensure no child name, email, device ID, package inventory, location, QR secret, or auth token is logged.
- [ ] Remove precise coordinates and sensitive identifiers from production logs.
- [ ] Confirm release network security configuration disallows user-added CAs and cleartext as intended. Do not claim certificate pinning unless implemented.

## User-Facing Content

- [ ] Replace `activity_terms_and_services.xml` completely; current technical/security claims are unsupported and deceptive.
- [ ] Remove the unsupported “end-to-end encryption” string.
- [ ] Add the separate Accessibility disclosure and affirmative consent flow before opening settings.
- [ ] Add compliant background-location disclosure immediately before the runtime/settings request.
- [ ] Add Usage Access, installed-app, Device Admin, and parent-consent disclosures.
- [ ] Link the final privacy policy inside the app and in Play Console using a public, active, non-PDF URL.
- [ ] Publish an account deletion URL and add in-app deletion under account settings.
- [ ] Ensure store listing, screenshots, onboarding, privacy policy, Data Safety, and declarations use identical descriptions of collection and sharing.

## Play Console

- [ ] Complete Accessibility Service declaration and upload a short video showing disclosure, accept/decline flows, settings enablement, and actual app blocking.
- [ ] Complete QUERY_ALL_PACKAGES declaration and show full installed-app selection as core functionality.
- [ ] Complete background-location declaration with one feature only, a 30-second-or-shorter video, disclosure, runtime prompt, background behavior, and parent map result.
- [ ] Complete foreground-service declarations for every declared type/special-use subtype.
- [ ] Complete Data Safety using the audited-build table, including Analytics and Crashlytics unless removed.
- [ ] Complete Target Audience carefully. The account holder is a parent/legal guardian, but the app processes child-device data; obtain specialist advice before selecting Families enrollment/age groups.
- [ ] Complete content rating using the notes above.
- [ ] Provide App Access instructions that bypass no disclosures and explain parent and child roles.

## Reviewer Access Instructions

Provide:

- Parent demo account email/password or a reliable OTP test procedure.
- A pre-linked test parent and child device if pairing cannot be reproduced by one reviewer device.
- Step-by-step path: Parent login -> display QR -> Child role -> child name -> disclosures/permissions -> scan QR -> parent selects app -> child opens app -> block occurs -> parent views usage/location.
- Firebase/Appwrite test environment that remains available throughout review.
- Any required test phone numbers/emails and how OTP delivery works.
- A note that Device Admin is optional and can be skipped.

## Required Videos

- Accessibility declaration video: app launch, standalone disclosure, consent, Android enablement, restricted-app selection, and actual enforcement; also show decline/re-entry flow.
- Background-location video: disclosure, runtime/settings grant, app moved to background, child location update, and parent map display.
- QUERY_ALL_PACKAGES evidence: installed-app list and selecting arbitrary installed apps for limits/blocking.
- Foreground-service evidence if requested: ongoing notification and feature active while app UI is not visible.

## Screenshots

- Parent dashboard with linked child device.
- Installed-app selection and app-limit controls.
- Screen-time/usage report.
- Focus schedule/timer setup.
- Parent map showing a clearly labeled demo location.
- Child-visible managed-device/status screen.
- Do not show real child names, emails, precise home locations, device IDs, QR keys, or other personal data.

---

# 10. Risk Report

## Approval Estimate

Current audited build: **10-20% first-submission approval chance**.

After all critical fixes, accurate declarations, deployed security controls, disclosures, and reviewer videos: **55-70% first-submission approval chance**. This remains a high-scrutiny category, and no approval can be guaranteed.

## High-Risk Policy Areas

1. **Accessibility misuse / device and network abuse:** blocking the user from disabling Accessibility or Device Admin and reading Settings-screen text is the highest risk.
2. **Stalkerware/monitoring policy:** missing `isMonitoringTool=child_monitoring`, mandatory background tracking, and uninstall-resistance behavior can resemble covert monitoring unless transparency is strong.
3. **Background location:** continuous high-accuracy upload approximately every two minutes is invasive and currently mandatory.
4. **QUERY_ALL_PACKAGES:** broad inventory is sensitive, remotely uploaded, and parental control is not explicitly listed among ordinary permitted examples.
5. **Deceptive behavior:** current Terms and UI claim security and infrastructure not present in code.
6. **User Data / child data:** no confirmed affirmative parent-consent record, incomplete deletion, no implemented retention schedule, and no proof of deployed least-privilege Firebase rules.
7. **Exact alarms and foreground services:** overlapping permissions and numerous persistent/special-use services may be viewed as excessive.
8. **Analytics in a child-data context:** Analytics is explicitly enabled despite privacy-oriented product wording.

## Likely Rejection Reasons

- Accessibility used to prevent users from disabling controls or accessing system settings.
- Accessibility disclosure does not describe actual screen-content access and remote foreground-app/usage processing.
- Monitoring app lacks the required manifest classification or appears hidden/unremovable.
- Background location is mandatory, insufficiently disclosed, or broader than the demonstrated core feature.
- QUERY_ALL_PACKAGES justification does not prove that complete app inventory is indispensable.
- Data Safety says no analytics while Firebase Analytics is enabled, or says data is ephemeral/local when Firebase uploads exist.
- Privacy policy claims deletion, encryption, retention, or security controls that are not implemented.
- No functional account deletion mechanism/URL.
- Unsupported or deceptive claims in Terms/store content.
- Reviewer cannot access both parent and child flows or cannot reproduce permission-dependent features.

## Exact Fix Order Before Submission

1. Remove system-settings/deactivation blocking and minimize Accessibility capabilities.
2. Add `isMonitoringTool=child_monitoring`, persistent child-visible transparency, and standalone disclosures/consent.
3. Remove unused/redundant permissions and simplify exact alarms/foreground services.
4. Replace the Terms screen and all unsupported privacy/security wording.
5. Decide whether continuous location and Analytics are truly necessary; reduce or remove them where possible.
6. Deploy least-privilege Firebase rules and secure/rate-limit Appwrite OTP functions.
7. Implement parent consent records, full account/data deletion, and automatic retention jobs.
8. Rename the package and restrict production credentials to the final package/signing key.
9. Re-audit the release AAB's merged manifest and dependency report, then update Data Safety from the actual artifact.
10. Record reviewer videos and submit consistent listing, policy, declarations, screenshots, and App Access instructions.

## Official Policy References

- Google Play AccessibilityService API policy: https://support.google.com/googleplay/android-developer/answer/10964491
- Google Play broad package visibility policy: https://support.google.com/googleplay/android-developer/answer/10158779
- Google Play background location guidance: https://support.google.com/googleplay/android-developer/answer/9799150
- Google Play monitoring-tool flag guidance: https://support.google.com/googleplay/android-developer/answer/12955211
- Google Play foreground service requirements: https://support.google.com/googleplay/android-developer/answer/13392821
