# Sentinel - Parental Control & Monitoring App

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Master2 Logo" width="120"/>
</p>

<p align="center">
  <b>A comprehensive Android parental control application for monitoring and managing children's device usage</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform"/>
  <img src="https://img.shields.io/badge/Min%20SDK-26%20(Oreo)-blue.svg" alt="Min SDK"/>
  <img src="https://img.shields.io/badge/Target%20SDK-34-blue.svg" alt="Target SDK"/>
  <img src="https://img.shields.io/badge/Java-17-orange.svg" alt="Java"/>
</p>

---

## 📖 Overview

Master2 is a feature-rich parental control application that empowers parents to monitor and manage their children's Android device usage. It uses a parent-child architecture where devices connect via QR code pairing.

### ✨ Key Features

| Feature                | Description                                            |
| ---------------------- | ------------------------------------------------------ |
| 📱 QR Code Pairing | Connect parent and child devices instantly via QR scan |
| ⏱️ Smart Timers    | Set usage timers for specific apps                     |
| 📊 Usage Analytics | View detailed daily/weekly app usage statistics        |
| 🚫 App Blocking    | Block apps remotely from parent device                 |
| 🎯 Focus Mode      | Restrict access to distracting apps                    |
| 📅 Daily Limits    | Set per-app daily usage limits                         |
| 👥 Multi-Device    | Manage multiple child devices                          |
| 🔄 Real-time Sync  | Instant synchronization via Firebase                   |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      PARENT DEVICE                          │
│  ┌─────────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ Parent Dashboard │  │ Timer Mgmt   │  │  App Blocking │  │
│  └────────┬────────┘  └──────┬───────┘  └───────┬───────┘  │
└───────────┼──────────────────┼──────────────────┼───────────┘
            │                  │                  │
            ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────────┐
│                    FIREBASE REALTIME DB                      │
│  qr_share_codes │ smart_timers │ blocked_apps │ usage_stats │
└─────────────────────────────────────────────────────────────┘
            │                  │                  │
            ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────────┐
│                      CHILD DEVICE                           │
│  ┌─────────────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ Child Dashboard  │  │BlockService  │  │ Usage Tracker │  │
│  └─────────────────┘  └──────────────┘  └───────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Tech Stack

| Category             | Technology                              |
| -------------------- | --------------------------------------- |
| Language         | Java 17                                 |
| UI               | Material Design 3, View Binding         |
| Backend          | Firebase (Auth, Realtime DB, Firestore) |
| QR Code          | ZXing, CameraX                          |
| OTP Service      | Appwrite Functions                      |
| Background Tasks | Android Services, WorkManager           |
| Networking       | OkHttp, Retrofit                        |
| Charts           | AnyChart                                |

---

## 📂 Project Structure

```
app/src/main/java/com/example/master2/
├── 📁 adapters/         # RecyclerView adapters
├── 📁 config/           # App configuration
├── 📁 managers/         # Business logic managers
├── 📁 models/           # Data models
├── 📁 services/         # Background services
├── 📁 utils/            # Utility classes
├── 📁 workers/          # WorkManager workers
├── 📄 MainActivity.java           # Entry point
├── 📄 ParentDashboardActivity.java # Parent hub
├── 📄 ChildDashboardActivity.java  # Child hub
├── 📄 BlockService.java           # Accessibility service
└── ... (85+ more files)
```

---

## 🚀 Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android device/emulator (API 26+)
- Firebase project with Realtime Database enabled

### Setup

1. Clone the repository

   ```bash
   git clone https://github.com/yourusername/master2.git
   cd master2
   ```

2. Configure Firebase

   - Create a new Firebase project
   - Download `google-services.json`
   - Place it in `app/` directory

3. Configure Appwrite (for OTP)
   - Set up an Appwrite project.
   - **Note:** `AppwriteConfig.java` is excluded from Git for security. You must create this file in `online.monarchlabs.sentinel.config` using the provided template or your own credentials.

4. Build and Run
   ```bash
   ./gradlew assembleDebug
   ```

---

## 🛡️ Security & Privacy
To protect API keys and sensitive data, the following files are **ignored** in the repository:
* `google-services.json` (Firebase configuration)
* `AppwriteConfig.java` (Appwrite API keys)
* `local.properties` (SDK paths and local keys)
* `*.jks` / `*.keystore` (App signing keys)

> [!IMPORTANT]
> If you are cloning this repository, you **must** provide your own Firebase and Appwrite credentials for the app to function correctly.

---

## ⚙️ Manual Setup (Required)
For the app to monitor and block other applications, you must manually enable these permissions on the **Child Device**:
1. **Accessibility Service:** Settings > Accessibility > Master2 > Turn ON.
2. **Usage Access:** Settings > Security > Apps with usage access > Master2 > Turn ON.
3. **Display Over Other Apps:** Allow Master2 to show the block overlay screen.

---

## 📱 User Flows

### Parent Setup

1. Open app → Select "I'm a Parent"
2. Enter email, username, phone → Receive OTP
3. Verify OTP → Access Dashboard
4. Generate QR code for child pairing

### Child Setup

1. Open app → Select "I'm a Child"
2. Enter name → Scan parent's QR code
3. Grant required permissions
4. Connection established!

### Setting App Timers

1. Parent Dashboard → Timer Management
2. Select apps → Set duration
3. Child device enforces limits automatically

---

## 🔐 Required Permissions

| Permission                   | Purpose              |
| ---------------------------- | -------------------- |
| `PACKAGE_USAGE_STATS`        | App usage tracking   |
| `BIND_ACCESSIBILITY_SERVICE` | App blocking         |
| `SYSTEM_ALERT_WINDOW`        | Block screen overlay |
| `RECEIVE_BOOT_COMPLETED`     | Auto-start services  |
| `FOREGROUND_SERVICE`         | Background operation |

---

## 📊 Firebase Data Structure

```
Firebase Realtime Database
├── users/{userId}/                    # User profiles
├── qr_share_codes/{shareKey}/         # QR pairing data
├── parent_devices/{deviceId}/         # Parent-child mapping
├── blocked_apps/{childDeviceId}/      # Blocked apps list
├── smart_timers/{childDeviceId}/      # Timer configurations
├── daily_usage_limits/{childDeviceId}/ # Daily limits
└── susage_stats/{childDeviceId}/      # Usage statistics
```

---

## 🧪 Testing

```bash
# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

---

## 📄 Documentation

- [Project Architecture](docs/ARCHITECTURE.md)
- [API Documentation](docs/API.md)
- [Firebase Schema](docs/FIREBASE_SCHEMA.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)

---

## 🤝 Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 👥 Authors

-Monarch Labs _By Team of Monarch Labs_

---

## 🙏 Acknowledgments

- Firebase for real-time synchronization
- ZXing for QR code functionality
- Material Design for UI components

---

<p align="center">
  Made with ❤️ for safer digital experiences
</p>
