# Development And Production Environments

The Android app has `development` and `production` product flavors.

- `development` uses application ID `online.monarchlabs.sentinel.dev` and must use an independent Firebase project.
- `production` uses application ID `online.monarchlabs.sentinel` and the production Firebase project.
- Appwrite OTP/privacy can continue using the existing deployment. Option B does not require a new Appwrite relationship function.

## Development setup

1. Register `online.monarchlabs.sentinel.dev` in the development Firebase project.
2. Place its downloaded configuration at `app/src/development/google-services.json`. This file is ignored by Git.
3. Add the Firebase values to `local.properties` at `C:\Users\LENOVO\Desktop\Parental_care_hamza\local.properties`:

```properties
DEV_FIREBASE_PROJECT_ID=your-development-project
DEV_FIREBASE_DATABASE_URL=https://your-development-project-default-rtdb.firebaseio.com
```

If you want the development build to use different Appwrite OTP/privacy settings, add `DEV_APPWRITE_*` values. If you omit them, the build falls back to the existing `APPWRITE_*` values and then to the current production defaults.

Build with `./gradlew assembleDevelopmentDebug`.

## Production setup

The tracked production Firebase file is stored at `app/src/production/google-services.json`. Production Appwrite values may be supplied with `PRODUCTION_APPWRITE_*` properties; existing `APPWRITE_*` properties remain accepted as production fallbacks.

Build with `./gradlew assembleProductionRelease`.

At startup, the app verifies the resolved Firebase project ID and RTDB URL. A development build fails immediately if it resolves production Firebase credentials or has missing development Firebase values. The startup guard logs the Appwrite project ID but does not require a separate Appwrite project.