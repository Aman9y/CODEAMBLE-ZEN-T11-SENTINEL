# Production V2 Migration

This Admin-only migration is for the later coordinated production promotion.
It does not run from Android or Appwrite schedules.

Required environment variables:

```env
FIREBASE_DATABASE_URL=https://your-production-project-default-rtdb.firebaseio.com
FIREBASE_SERVICE_ACCOUNT_BASE64=base64_encoded_service_account_json
```

Run the report first:

```powershell
npm run dry-run
```

Apply only after an RTDB backup and review of `ownershipConflicts` and
`unresolvedOwnership`:

```powershell
node migrate-production-to-v2.js --apply --confirm-project=your-project-id
```

The migration is idempotent. Existing v2 values always win, legacy commands are
reduced to desired policy state, and records without enough identity data are
reported instead of receiving insecure ownership claims.
