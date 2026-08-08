# Sentinel NextDNS Cloudflare Worker

This Worker updates a NextDNS profile denylist without exposing the NextDNS API key in the Android APK.

## Required Worker secrets

```bash
wrangler secret put NEXTDNS_API_KEY
wrangler secret put SENTINEL_CLIENT_SECRET
```

`SENTINEL_CLIENT_SECRET` must match `CLOUDFLARE_CLIENT_SECRET` in `local.properties`.

## Android config

Add the deployed Worker URL to `local.properties`:

```properties
CLOUDFLARE_NEXTDNS_WORKER_URL=https://your-worker.your-subdomain.workers.dev
```

For the hackathon build, the Android app sends `profileId`, `deviceId`, and `domain`. The Worker validates the shared client secret and Firebase token presence, then calls NextDNS.

