# Troubleshooting Guide

## Common Issues and Solutions

---

## 🔴 Connection Issues

### Child shows "Disconnected" even after QR scan

Symptoms:

- QR scan succeeds
- Toast says "Connected"
- Dashboard shows "Disconnected"

Solutions:

1. Check `device_connection` SharedPreferences
2. Verify Firebase path `/device_status/{childDeviceId}` has `connected: true`
3. Ensure `ChildConnectionManager.completeConnection()` is called

Code to debug:

```java
SharedPreferences prefs = getSharedPreferences("device_connection", MODE_PRIVATE);
Log.d("DEBUG", "isConnected: " + prefs.getBoolean("is_connected", false));
Log.d("DEBUG", "parentName: " + prefs.getString("connected_parent_name", "none"));
```

---

### Parent doesn't see child device

Symptoms:

- Child shows connected
- Parent device list is empty

Solutions:

1. Check Firebase `/qr_share_codes/{shareKey}/scanned_devices/`
2. Verify `setupQRScanOnlyListener()` is active
3. Check for Firebase listener errors in logcat

---

## 🟡 Timer Issues

### Timer doesn't reset at midnight

Symptoms:

- Timer shows 0:00 next day
- Should auto-reset to daily duration

Solutions:

1. Verify `DailyTimerResetService` is running
2. Check AlarmManager permissions for exact alarms
3. Look for `lastResetDate` in Firebase

Force reset:

```java
DailyTimerResetService.performMidnightReset();
```

---

### Timer keeps running when app closed

Expected behavior! Timer runs via `SmartTimerService`.

To verify service is running:

```bash
adb shell dumpsys activity services | grep SmartTimer
```

---

## 🟠 App Blocking Issues

### Apps not getting blocked

Symptoms:

- App is in blocked list
- Child can still open app

Solutions:

1. Verify Accessibility Service is enabled
2. Check `BlockService.onAccessibilityEvent()` is receiving events
3. Ensure blocked apps list is synced

Check accessibility:

```java
AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
Log.d("DEBUG", "Accessibility enabled: " + am.isEnabled());
```

---

### Block screen not appearing

Symptoms:

- Blocked app opens briefly
- No overlay shown

Solutions:

1. Grant `SYSTEM_ALERT_WINDOW` permission
2. Check `AppBlockActivity` is launching
3. Verify notification channel exists

---

## 🔵 Usage Tracking Issues

### Usage data shows 0 for all apps

Symptoms:

- Child has used apps
- Usage stats all show 0

Solutions:

1. Grant `PACKAGE_USAGE_STATS` permission
2. Check `UsageStatsManager` access

Verify permission:

```java
AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                                  android.os.Process.myUid(),
                                  getPackageName());
Log.d("DEBUG", "Usage stats permission: " + (mode == AppOpsManager.MODE_ALLOWED));
```

---

### Usage data not uploading to Firebase

Solutions:

1. Check network connectivity
2. Verify child device ID is set
3. Look for Firebase write errors

---

## 🟣 Service Issues

### Services killed by OEM

Affected devices: Xiaomi (MIUI), Vivo, Oppo, Samsung

Solutions:

1. Enable uninstall protection from the child permissions screen
2. Disable battery optimization for app
3. Lock app in recent apps
4. Add to OEM's "protected apps" list

Check OEM:

```java
OEMCompatibilityManager.checkOEMRestrictions(context);
```

---

### ForegroundServiceDidNotStartInTimeException

Solutions:

1. Start foreground notification within 5 seconds
2. Use `startForeground()` in `onCreate()` not `onStartCommand()`
3. Check for heavy initialization blocking main thread

---

## 🟢 OTP Issues

### OTP not received

Solutions:

1. Check Appwrite function logs
2. Verify email address format
3. Check spam folder
4. Use fallback (check logcat for OTP in debug mode)

---

### OTP verification fails

Solutions:

1. Verify OTP hasn't expired (10 min default)
2. Check for whitespace in entered OTP
3. Ensure OTPService has the stored OTP

---

## Debug Commands

### View all running services

```bash
adb shell dumpsys activity services online.monarchlabs.sentinel
```

### View Firebase listener status

```bash
adb logcat -s Firebase
```

### Clear app data

```bash
adb shell pm clear online.monarchlabs.sentinel
```

### Force stop app

```bash
adb shell am force-stop online.monarchlabs.sentinel
```

---

## Contact

If issues persist, check the [Firebase Console](https://console.firebase.google.com) for database state and enable debug logging:

```java
FirebaseDatabase.getInstance().setLogLevel(Logger.Level.DEBUG);
```
