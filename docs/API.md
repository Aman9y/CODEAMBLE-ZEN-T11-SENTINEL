# API Documentation

## Internal Service APIs

This document describes the internal APIs and interfaces used by Master2 components.

---

## SessionManager

Manages user session state via SharedPreferences.

### Methods

| Method              | Parameters                      | Returns | Description                   |
| ------------------- | ------------------------------- | ------- | ----------------------------- |
| `saveParentSession` | phoneNumber, userId, deviceName | void    | Save parent login session     |
| `saveChildSession`  | deviceId, parentName, shareKey  | void    | Save child connection session |
| `isLoggedIn`        | -                               | boolean | Check if user is logged in    |
| `getUserType`       | -                               | String  | Returns "parent" or "child"   |
| `logoutUser`        | -                               | void    | Clear all session data        |

### Usage

```java
SessionManager session = new SessionManager(context);

// Parent login
session.saveParentSession("1234567890", "userId123", "Dad's Phone");

// Child connection
session.saveChildSession("device_abc", "Dad's Phone", "shareKey123");

// Check session
if (session.isLoggedIn()) {
    String type = session.getUserType(); // "parent" or "child"
}
```

---

## ChildConnectionManager

Handles parent-child device pairing.

### Interfaces

```java
public interface OnConnectionListener {
    void onSuccess(String parentUserId);
    void onError(String error);
}
```

### Methods

| Method                                                                                        | Description               |
| --------------------------------------------------------------------------------------------- | ------------------------- |
| `connectToParent(shareKey, parentDeviceName, childDeviceId, childDeviceName, apps, listener)` | Initiate connection       |
| `clearExistingConnectionState(childDeviceId)`                                                 | Clear old connection      |
| `validateConnectionPermission(childDeviceId, shareKey, callback)`                             | Verify allowed to connect |

### Usage

```java
ChildConnectionManager manager = new ChildConnectionManager(context);

manager.connectToParent(
    shareKey,
    parentDeviceName,
    childDeviceId,
    childDeviceName,
    installedAppsList,
    new ChildConnectionManager.OnConnectionListener() {
        @Override
        public void onSuccess(String parentUserId) {
            // Connection successful
        }

        @Override
        public void onError(String error) {
            // Handle error
        }
    }
);
```

---

## OTPService

Handles OTP generation, sending, and verification.

### Methods

| Method                       | Returns                      | Description        |
| ---------------------------- | ---------------------------- | ------------------ |
| `sendOTP(email, userType)`   | CompletableFuture<OTPResult> | Send OTP to email  |
| `verifyOTP(email, otp)`      | CompletableFuture<OTPResult> | Verify entered OTP |
| `resendOTP(email, userType)` | CompletableFuture<OTPResult> | Resend OTP         |

### OTPResult Class

```java
public class OTPResult {
    boolean isSuccess();
    String getMessage();
    String getDocumentId();
    Exception getError();
}
```

### Usage

```java
OTPService otpService = new OTPService(context);

otpService.sendOTP("user@email.com", "parent")
    .thenAccept(result -> {
        if (result.isSuccess()) {
            // OTP sent successfully
        }
    });

otpService.verifyOTP("user@email.com", "123456")
    .thenAccept(result -> {
        if (result.isSuccess()) {
            // OTP verified
        }
    });
```

---

## SUsageDataManager

Collects and manages app usage statistics.

### Methods

| Method                                 | Returns                          | Description             |
| -------------------------------------- | -------------------------------- | ----------------------- |
| `getInstance(context)`                 | SUsageDataManager                | Get singleton instance  |
| `getTodayUsage()`                      | List<SUsageAppInfo>              | Get today's usage       |
| `getWeeklyUsage()`                     | Map<String, List<SUsageAppInfo>> | Get 7-day usage         |
| `uploadToFirebase(deviceId, listener)` | void                             | Upload data to Firebase |

### SUsageAppInfo

```java
public class SUsageAppInfo {
    String packageName;
    String appName;
    long usageTimeMs;
    String category;
    String iconBase64;
}
```

### Usage

```java
SUsageDataManager manager = SUsageDataManager.getInstance(context);

// Get today's usage
List<SUsageAppInfo> todayUsage = manager.getTodayUsage();

// Upload to Firebase
manager.uploadToFirebase(childDeviceId, new OnUploadCompleteListener() {
    @Override
    public void onSuccess() {
        Log.d("Usage", "Uploaded successfully");
    }

    @Override
    public void onError(String error) {
        Log.e("Usage", "Upload failed: " + error);
    }
});
```

---

## Firebase Paths Reference

| Path                            | Read By       | Write By                      |
| ------------------------------- | ------------- | ----------------------------- |
| `/users/{userId}`               | Owner         | Owner                         |
| `/qr_share_codes/{shareKey}`    | Child         | Parent (create), Child (scan) |
| `/blocked_apps/{childDeviceId}` | Child         | Parent                        |
| `/smart_timers/{childDeviceId}` | Child, Parent | Parent (set), Child (update)  |
| `/susage_stats/{childDeviceId}` | Parent        | Child                         |

---

## Broadcast Actions

| Action                                       | Sender             | Receiver       |
| -------------------------------------------- | ------------------ | -------------- |
| `online.monarchlabs.sentinel.BLOCKED_APPS_UPDATED`   | RemoteBlockService | BlockService   |
| `online.monarchlabs.sentinel.TIMER_UPDATED`          | SmartTimerService  | UI Activities  |
| `online.monarchlabs.sentinel.FOREGROUND_APP_CHANGED` | BlockService       | Timer Services |
