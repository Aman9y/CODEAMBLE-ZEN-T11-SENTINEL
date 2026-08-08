# Firebase V2 Schema

The Android clients use only the `v2` namespace. Client access is default-deny and is authorized from the canonical ownership record at `v2/device_owners/{deviceId}`.

## Identity And Pairing

- `v2/users/{parentUid}`: parent account identity.
- `v2/parent_profiles/{parentUid}`: parent profile shown in the app.
- `v2/parent_clients/{parentUid}/{parentDeviceId}`: parent phone capability/session metadata.
- `v2/pairing_sessions/{sessionId}`: short-lived QR session created by the parent.
- `v2/device_owners/{deviceId}`: canonical parent/child ownership claim, including `parentUid`, `childAuthUid`, `connectionId`, `linkedAt`, status, and last-seen metadata.
- `v2/parent_device_links/{parentUid}/{deviceId}`: parent dashboard index for connected children.
- `v2/devices/{deviceId}`: child metadata and capabilities.

Relationship creation and removal are handled by Firebase RTDB client transactions plus rules. A child can claim ownership only through a valid active QR session, same-parent reconnect is allowed, different active-parent takeover is denied, and the owner parent can write the scoped removal marker plus cleanup.

## Feature Data

- `v2/usage_daily/{deviceId}/{yyyy-MM-dd}`: compact daily totals and package-duration values.
- `v2/app_catalog/{deviceId}`: bounded app labels/icons referenced by usage and inventory views.
- `v2/device_installs/{deviceId}`: current installed-app inventory.
- `v2/app_events/{deviceId}`: deduplicated install/uninstall history.
- `v2/device_policies/{deviceId}/blocked_apps`: parent blocking policy.
- `v2/device_policies/{deviceId}/app_timers`: parent timer policy.
- `v2/app_block_state/{deviceId}`: child execution state.
- `v2/timer_execution/{deviceId}` and `v2/timer_events/{deviceId}`: timer execution/status history.
- `v2/timer_state_requests/{deviceId}/{parentDeviceId}`: scoped timer refresh requests.
- `v2/locations/{deviceId}`: latest location and bounded heartbeat metadata.
- `v2/commands/{deviceId}`: scoped parent/child commands, including location refresh.
- `v2/permissions_current/{deviceId}` and `v2/permission_logs/{deviceId}`: current permissions and permission events.
- `v2/device_status/{deviceId}` and `v2/device_health/{deviceId}`: connectivity, bootstrap, and health status.

## Removal Contract

`v2/device_removals/{deviceId}` contains the durable removal marker with the exact `childAuthUid`, `targetParentUid`, `targetConnectionId`, issue/expiry timestamps, and reason. The child persists local disconnection and clears feature/session state before acknowledging the marker. Missing or mismatched ownership also triggers the same local cleanup.

## Usage Contract

A new `connectionId` performs one bootstrap upload for today and the previous six days. Later uploads contain only changed totals and changed app durations. Parents listen only to today's exact date; historical dates are fetched one at a time and cached by parent UID, device ID, connection ID, date, and history generation.

## Authorization

- Owner parent: reads child data and writes parent-owned policies/commands.
- Exact anonymous child auth UID: writes child telemetry and reads its policy/commands/removal marker.
- Unrelated or signed-out users: denied.
- Server-only indexes, OTP data, retention, and migration use Firebase Admin and have no client rules. Relationship mutations are client-side RTDB transactions constrained by `backend/database.rules.json`.

See `backend/database.rules.json` for the executable authorization contract.
