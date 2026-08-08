# Firebase Legacy Removal Audit

## Status

The development Android runtime and `backend/database.rules.json` are v2-only. Database access defaults to denied, and no legacy root has a client read or write rule.

Repository scans may still find old path names in these non-runtime places:

- `backend/migrations/migrate-production-to-v2.js`, which must read production legacy data during the one-time Admin migration.
- Local SharedPreferences keys retained to preserve on-device cleanup compatibility.
- Historical changelog or migration documentation.

These occurrences are not Firebase client dependencies.

## Runtime Guarantees

- Pairing and removal are handled by Firebase RTDB transactions constrained by v2 rules.
- Android relationship validation uses `v2/device_owners` and scoped removal markers.
- Usage, catalog, inventory, app events, policies, block state, timer state/events, location, commands, permissions, status, and health all use canonical v2 paths.
- Parent and child authorization is derived from `parentUid` and exact `childAuthUid` in the ownership record.
- Legacy roots and unauthenticated access are covered by Firebase Emulator denial tests.

## Deletion Gate

Development legacy data can be deleted after the complete physical-device matrix passes. Production legacy data must remain until backup, migration dry-run, coordinated promotion, verification, and rollback observation are complete.

Any future runtime reference to a legacy Firebase root blocks deletion and must be removed or explicitly justified as part of the Admin migration only.
