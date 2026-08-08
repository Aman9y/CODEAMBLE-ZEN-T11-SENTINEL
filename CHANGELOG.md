# Changelog

All notable changes to the Master2 project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-01-07

### Added

- Initial release of Master2 Parental Control App
- Parent authentication via email OTP (Appwrite)
- Child device pairing via QR code scanning
- Real-time Firebase synchronization
- Smart timer functionality with auto-reset
- Daily usage limits per app
- App blocking via Accessibility Service
- Focus mode for blocking distracting apps
- Usage statistics tracking (daily/weekly)
- Multi-device support (multiple child devices)
- Boot receiver for service auto-start
- Service watchdog for reliability
- OEM compatibility manager (MIUI, VIVO, OPPO)
- Device admin support for enhanced protection

### Components

- 107+ Java classes
- 45 layout files
- 15+ background services
- Firebase Realtime Database integration

### Dependencies

- Firebase BOM 32.7.0
- ZXing 3.5.1
- CameraX 1.3.1
- Appwrite SDK 6.1.0
- WorkManager 2.9.0
- Material Design 1.10.0

---

## [Unreleased]

### Planned

- MVVM architecture refactoring
- Unit test coverage
- UI/UX improvements
- Service consolidation
- Enhanced error handling
- Push notification support
- Location tracking feature
- Screen time reports export
