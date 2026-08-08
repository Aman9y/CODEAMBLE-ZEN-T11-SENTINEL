# Parent Email OTP Login and Privacy Lifecycle

Appwrite Function ID expected by Android:

```text
parent_email_otp_login
```

The Android app can override this with `APPWRITE_PARENT_OTP_FUNCTION_ID` in `local.properties`.

## Required Environment Variables

Set these in the Appwrite Function settings:

```env
FIREBASE_SERVICE_ACCOUNT_BASE64=base64_encoded_service_account_json
FIREBASE_DATABASE_URL=https://master2-dbbc1-default-rtdb.firebaseio.com
EMAIL_USER=your_gmail_address@gmail.com
EMAIL_PASS=your_gmail_app_password
```

`FIREBASE_SERVICE_ACCOUNT_JSON` also works, but base64 is easier to paste safely because Firebase private keys contain newlines.

`EMAIL_FROM` is optional. If it is not set, Gmail sends from `EMAIL_USER`.

The Appwrite email function variables are only needed if you remove Gmail SMTP and use the fallback Appwrite function call.

Never add Firebase service account JSON or Appwrite server API keys to Android or git.

## Function Permissions

This function must allow execution by guests/anyone because parents use it while logged out. The function still only returns a Firebase custom token after the emailed OTP is verified.

Set the function schedule to `17 3 * * *` and timeout to 900 seconds. Scheduled runs enforce retention and finalize pending guardian-consent records.

## Flow

`action=send`:

- Finds parent by email in Firebase Realtime Database.
- Creates a 6-digit OTP.
- Stores only a salted hash under `parent_email_login_otps`.
- Sends the OTP email through Gmail SMTP.

`action=verify`:

- Verifies OTP server-side.
- Returns a Firebase custom token for the parent UID.

Android then calls `FirebaseAuth.signInWithCustomToken()`.

Authenticated Android calls also use this same function ID:

- `action=recordConsent` records guardian consent after verifying a Firebase ID token.
- `action=deleteAccount` deletes the verified parent's account and associated child data.
