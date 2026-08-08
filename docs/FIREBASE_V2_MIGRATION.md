# Firebase V2 Migration And Promotion

Development and production are intentionally separate environments. Development runs the v2-only Android client and v2-only database rules. The current production application and database remain unchanged until physical-device acceptance testing is complete.

## Development Gate

Before promotion, verify on the development project:

- Same parent UID controls the child from multiple parent phones.
- Reinstall/reconnect to the same parent succeeds.
- A different active parent is rejected until removal or the intentional 90-day stale-owner release.
- Parent removal disconnects foreground, background, restarted, and temporarily offline children.
- Usage bootstraps today plus six prior dates once per connection/schema, then writes only deltas.
- Historical parent views read one exact date once and subsequently use local cache.
- Inventory, app events, blocking, timers, location, permissions, and uninstall-protection status remain functional.
- Firebase Emulator authorization tests and `assembleProductionDebug` pass.

## Production Migration

`backend/migrations/migrate-production-to-v2.js` is an Admin-only, idempotent migration. It reads supported legacy production structures, preserves existing valid v2 values, reports conflicts, and plans canonical v2 writes.

Required environment variables:

- `FIREBASE_DATABASE_URL`
- `FIREBASE_SERVICE_ACCOUNT_JSON` or `FIREBASE_SERVICE_ACCOUNT_BASE64`

Run without `--apply` for dry-run output. Before applying:

1. Back up production RTDB.
2. Run dry-run and review counts and ownership conflicts.
3. Confirm the service-account project ID and database URL are production.
4. Apply with `--apply --confirm-project=<project-id>`.
5. Deploy the tested Android release and v2 rules as one coordinated promotion. Existing Appwrite OTP/privacy functions are unchanged by Option B.
6. Verify pairing, removal, usage, blocking, timers, location, permissions, and account workflows.
7. Remove production legacy data only after the observation and rollback window.

The development app does not contain legacy read/write fallbacks. Compatibility is provided only by the production migration and coordinated release process.
