# Architecture Documentation

## Overview

Master2 follows a distributed architecture with two main roles:

- Parent Device: Controls and monitors
- Child Device: Reports and receives commands

## Component Diagram

```
┌────────────────────────────────────────────────────────────────────┐
│                         PARENT DEVICE                               │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    UI Layer                                  │   │
│  │  ┌───────────────┐ ┌────────────────┐ ┌─────────────────┐   │   │
│  │  │ParentDashboard│ │TimerManagement │ │ ChildAppList    │   │   │
│  │  └───────────────┘ └────────────────┘ └─────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                  Manager Layer                               │   │
│  │  ┌────────────────┐ ┌──────────────┐ ┌──────────────────┐   │   │
│  │  │ChildDeviceManager│ │QRCodeManager│ │ConnectedDevices │   │   │
│  │  └────────────────┘ └──────────────┘ └──────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│                    FIREBASE REALTIME DATABASE                       │
│                                                                     │
│  ┌──────────┐ ┌────────────┐ ┌────────────┐ ┌───────────────┐     │
│  │  users   │ │qr_share_   │ │blocked_apps│ │smart_timers   │     │
│  │          │ │   codes    │ │            │ │               │     │
│  └──────────┘ └────────────┘ └────────────┘ └───────────────┘     │
└────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌────────────────────────────────────────────────────────────────────┐
│                         CHILD DEVICE                                │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    UI Layer                                  │   │
│  │  ┌───────────────┐ ┌────────────────┐ ┌─────────────────┐   │   │
│  │  │ChildDashboard │ │ChildUsageView  │ │ChildPermissions│   │   │
│  │  └───────────────┘ └────────────────┘ └─────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                  Service Layer                               │   │
│  │  ┌────────────┐ ┌────────────────┐ ┌────────────────────┐   │   │
│  │  │BlockService│ │RemoteBlockSvc  │ │SmartTimerService   │   │   │
│  │  └────────────┘ └────────────────┘ └────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────┘
```

## Core Components

### Activities

| Activity                | Role   | Purpose              |
| ----------------------- | ------ | -------------------- |
| MainActivity            | Both   | Entry, session check |
| ParentDashboardActivity | Parent | Main control hub     |
| ChildDashboardActivity  | Child  | Status display       |
| TimerManagementActivity | Parent | Timer UI             |
| ChildLoginActivity      | Child  | QR scanning          |

### Services

| Service                 | Type          | Purpose            |
| ----------------------- | ------------- | ------------------ |
| BlockService            | Accessibility | Monitor/block apps |
| RemoteBlockService      | Foreground    | Firebase listener  |
| SmartTimerService       | Foreground    | Timer countdown    |
| DailyTimerResetService  | Foreground    | Midnight reset     |
| RealTimeDataSyncService | Foreground    | Data sync          |

### Managers

| Manager                 | Purpose                 |
| ----------------------- | ----------------------- |
| SessionManager          | Login state persistence |
| ChildConnectionManager  | Pairing logic           |
| ConnectedDevicesManager | Multi-device handling   |
| SUsageDataManager       | Usage data collection   |

## Data Flow

### Timer Setting Flow

```
Parent sets timer → Firebase smart_timers/{deviceId}
                          ↓
Child SmartTimerService listens → Receives update
                          ↓
Timer countdown begins → Updates remainingTimeMs
                          ↓
Timer expires → Block apps via BlockService
```

### Usage Upload Flow

```
UsageEvents API → SUsageDataManager.getUsageForPeriod()
                          ↓
Process events → Calculate per-app time
                          ↓
Upload to Firebase → susage_stats/{deviceId}/{date}
                          ↓
Parent fetches → Display in ChildUsageViewActivity
```

## Security Considerations

1. Session tokens stored in SharedPreferences
2. Firebase rules should restrict access by parentDeviceId
3. OTP verification via Appwrite before account creation
4. Device ID used for device-specific data isolation
