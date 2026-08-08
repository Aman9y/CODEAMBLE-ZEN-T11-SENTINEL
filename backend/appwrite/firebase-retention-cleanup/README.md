# Firebase Retention Cleanup

Scheduled Appwrite function for keeping Firebase Realtime Database retention
under control without Firebase scheduled functions.

## Runtime

- Node.js 20
- Entrypoint: `src/main.js`
- Schedule: `15 3 * * *`

## Environment variables

- `FIREBASE_SERVICE_ACCOUNT_BASE64` or `FIREBASE_SERVICE_ACCOUNT_JSON`
- `FIREBASE_DATABASE_URL`, defaults to
  `https://master2-dbbc1-default-rtdb.firebaseio.com`
- Optional: `CLEANUP_INCLUDE_LEGACY_USAGE`, defaults to `true`

## Retention policy

- Expired QR sessions, QR shares, OTP records, OTP rate limits, and parent
  consent event records are cleaned automatically.
- `/v2/usage_daily/{deviceId}/{yyyy-MM-dd}` keeps today plus the previous six
  Asia/Kolkata calendar dates.
- `/susage_data/{deviceId}/weeklyData/{yyyy-MM-dd}` follows the same seven-day
  retention while legacy fallback is still enabled.
- Terminal `/v2/commands/{deviceId}/{commandId}` records are removed after
  seven days. Pending commands are removed only after their `expiresAt`.
- `/v2/permission_logs/{deviceId}/{eventId}` is removed after thirty days.
- Expired `/v2/timer_state_requests` and `/v2/device_removals` are removed.
- Devices with no heartbeat for ninety days are marked stale, not hard-deleted.

Manual test payload:

```json
{"dryRun": true}
```
