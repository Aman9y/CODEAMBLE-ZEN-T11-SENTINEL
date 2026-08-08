const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const {after, before, beforeEach} = test;

const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {get, ref, remove, set, update} = require("firebase/database");

const PROJECT_ID = "demo-sentinel-v2";
const DEVICE_ID = "child-device-1";
const NEW_DEVICE_ID = "child-device-2";
const REMOVED_DEVICE_ID = "removed-child-device";
const PARENT_UID = "parent-a";
const OTHER_PARENT_UID = "parent-b";
const CHILD_UID = "child-auth-a";
const OTHER_CHILD_UID = "child-auth-b";
const SESSION_A = "session-a";
const SESSION_B = "session-b";

let env;

function dbFor(uid) {
  return env.authenticatedContext(uid).database();
}

function owner(parentUid, childUid, sessionId, pairingKey, connectionId) {
  return {
    parentUid,
    childAuthUid: childUid,
    pairingSessionId: sessionId,
    pairingKey,
    connectionId,
    linkedAt: Date.now(),
    lastSeenAt: Date.now(),
    updatedAt: Date.now(),
    status: "active",
  };
}

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    database: {
      host: "127.0.0.1",
      port: 9000,
      rules: fs.readFileSync(
        path.join(__dirname, "..", "database.rules.json"),
        "utf8"
      ),
    },
  });
});

beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.database();
    const now = Date.now();
    await set(ref(db), {
      v2: {
        users: {
          [PARENT_UID]: {authUid: PARENT_UID, role: "parent"},
          [OTHER_PARENT_UID]: {authUid: OTHER_PARENT_UID, role: "parent"},
        },
        parent_profiles: {
          [PARENT_UID]: {displayName: "Parent A"},
          [OTHER_PARENT_UID]: {displayName: "Parent B"},
        },
        pairing_sessions: {
          [SESSION_A]: {
            parentUid: PARENT_UID,
            parentDeviceId: "parent-phone-a",
            parentDeviceName: "Parent A",
            baseQRKey: "qr-a",
            isActive: true,
            status: "active",
            expiresAt: now + 60000,
          },
          [SESSION_B]: {
            parentUid: OTHER_PARENT_UID,
            parentDeviceId: "parent-phone-b",
            parentDeviceName: "Parent B",
            baseQRKey: "qr-b",
            isActive: true,
            status: "active",
            expiresAt: now + 60000,
          },
        },
        parent_device_links: {
          [PARENT_UID]: {
            [DEVICE_ID]: {
              deviceId: DEVICE_ID,
              childAuthUid: CHILD_UID,
              connectionId: "connection-1",
            },
          },
        },
        device_owners: {
          [DEVICE_ID]: {
            parentUid: PARENT_UID,
            childAuthUid: CHILD_UID,
            connectionId: "connection-1",
            pairingSessionId: SESSION_A,
            pairingKey: "qr-a",
            status: "active",
            linkedAt: now,
            lastSeenAt: now,
            updatedAt: now,
          },
        },
        devices: {
          [DEVICE_ID]: {
            deviceId: DEVICE_ID,
            ownerParentUid: PARENT_UID,
            childAuthUid: CHILD_UID,
          },
        },
        usage_daily: {
          [DEVICE_ID]: {
            "2026-07-17": {totalUsageMillis: 1000},
          },
        },
        client_capabilities: {
          [DEVICE_ID]: {schemaVersion: 2},
        },
        device_removals: {
          [REMOVED_DEVICE_ID]: {
            trigger: true,
            removed_by_parent: true,
            childDeviceId: REMOVED_DEVICE_ID,
            childAuthUid: CHILD_UID,
            targetParentUid: PARENT_UID,
            targetConnectionId: "removed-connection",
            issuedAt: now,
            expiresAt: now + 60000,
            status: "pending",
          },
        },
      },
      parents: {
        [PARENT_UID]: {legacy: true},
      },
    });
  });
});

after(async () => {
  if (env) await env.cleanup();
});

test("owner parent can read its device data but another parent cannot", async () => {
  await assertSucceeds(get(ref(dbFor(PARENT_UID),
    `v2/usage_daily/${DEVICE_ID}/2026-07-17`)));
  await assertFails(get(ref(dbFor(OTHER_PARENT_UID),
    `v2/usage_daily/${DEVICE_ID}/2026-07-17`)));
});

test("only the child auth identity writes usage and status", async () => {
  await assertSucceeds(set(ref(dbFor(CHILD_UID),
    `v2/usage_daily/${DEVICE_ID}/2026-07-17/totalUsageMillis`), 2000));
  await assertSucceeds(set(ref(dbFor(CHILD_UID),
    `v2/device_status/${DEVICE_ID}`), {isOnline: true}));
  await assertFails(set(ref(dbFor(PARENT_UID),
    `v2/usage_daily/${DEVICE_ID}/2026-07-17/totalUsageMillis`), 3000));
  await assertFails(set(ref(dbFor(OTHER_CHILD_UID),
    `v2/device_status/${DEVICE_ID}`), {isOnline: true}));
});

test("only the owner parent writes policies", async () => {
  await assertSucceeds(set(ref(dbFor(PARENT_UID),
    `v2/device_policies/${DEVICE_ID}/blocked_apps/example`), {
    packageName: "example",
    blocked: true,
  }));
  await assertFails(set(ref(dbFor(CHILD_UID),
    `v2/device_policies/${DEVICE_ID}/blocked_apps/example`), {
    packageName: "example",
    blocked: false,
  }));
  await assertFails(set(ref(dbFor(OTHER_PARENT_UID),
    `v2/device_policies/${DEVICE_ID}/blocked_apps/example`), {
    packageName: "example",
    blocked: true,
  }));
});

test("child can claim an unowned device through an active QR session", async () => {
  await assertSucceeds(set(ref(dbFor(OTHER_CHILD_UID),
    `v2/device_owners/${NEW_DEVICE_ID}`),
    owner(PARENT_UID, OTHER_CHILD_UID, SESSION_A, "qr-a", "connection-2")));
  await assertSucceeds(update(ref(dbFor(OTHER_CHILD_UID)), {
    [`v2/devices/${NEW_DEVICE_ID}`]: {
      deviceId: NEW_DEVICE_ID,
      ownerParentUid: PARENT_UID,
      childAuthUid: OTHER_CHILD_UID,
    },
    [`v2/parent_device_links/${PARENT_UID}/${NEW_DEVICE_ID}`]: {
      deviceId: NEW_DEVICE_ID,
      childAuthUid: OTHER_CHILD_UID,
      connectionId: "connection-2",
    },
    [`v2/client_capabilities/${NEW_DEVICE_ID}`]: {schemaVersion: 2},
    [`v2/pairing_sessions/${SESSION_A}/connections/${NEW_DEVICE_ID}`]: {
      childDeviceId: NEW_DEVICE_ID,
      childAuthUid: OTHER_CHILD_UID,
    },
  }));
});

test("same parent reconnect can replace the anonymous child auth uid", async () => {
  await assertSucceeds(set(ref(dbFor(OTHER_CHILD_UID),
    `v2/device_owners/${DEVICE_ID}`),
    owner(PARENT_UID, OTHER_CHILD_UID, SESSION_A, "qr-a", "connection-2")));
});

test("different active parent cannot take over an owned child device", async () => {
  await assertFails(set(ref(dbFor(OTHER_CHILD_UID),
    `v2/device_owners/${DEVICE_ID}`),
    owner(OTHER_PARENT_UID, OTHER_CHILD_UID, SESSION_B, "qr-b", "connection-b")));
  await assertFails(set(ref(dbFor(OTHER_PARENT_UID),
    `v2/parent_device_links/${OTHER_PARENT_UID}/${DEVICE_ID}`), {
    deviceId: DEVICE_ID,
  }));
});

test("parent removal writes marker and deletes relationship data", async () => {
  await assertSucceeds(update(ref(dbFor(PARENT_UID),
    `v2/device_owners/${DEVICE_ID}`), {
    status: "removing",
    removalRequestedAt: Date.now(),
    removalReason: "removed_by_parent",
  }));

  const now = Date.now();
  await assertSucceeds(update(ref(dbFor(PARENT_UID)), {
    [`v2/device_removals/${DEVICE_ID}`]: {
      trigger: true,
      removed_by_parent: true,
      childDeviceId: DEVICE_ID,
      childAuthUid: CHILD_UID,
      targetParentUid: PARENT_UID,
      targetConnectionId: "connection-1",
      issuedAt: now,
      expiresAt: now + 60000,
      reason: "removed_by_parent",
      status: "pending",
    },
    [`v2/usage_daily/${DEVICE_ID}`]: null,
    [`v2/client_capabilities/${DEVICE_ID}`]: null,
    [`v2/devices/${DEVICE_ID}`]: null,
    [`v2/device_owners/${DEVICE_ID}`]: null,
    [`v2/parent_device_links/${PARENT_UID}/${DEVICE_ID}`]: null,
  }));
});

test("unrelated parent cannot remove another parent's child", async () => {
  await assertFails(update(ref(dbFor(OTHER_PARENT_UID),
    `v2/device_owners/${DEVICE_ID}`), {
    status: "removing",
    removalRequestedAt: Date.now(),
  }));
  await assertFails(remove(ref(dbFor(OTHER_PARENT_UID),
    `v2/device_owners/${DEVICE_ID}`)));
});

test("removed child can read and acknowledge its marker after owner deletion", async () => {
  const marker = ref(dbFor(CHILD_UID),
    `v2/device_removals/${REMOVED_DEVICE_ID}`);
  await assertSucceeds(get(marker));
  await assertSucceeds(update(marker, {
    acknowledgedAt: Date.now(),
    localCleanupComplete: true,
    acknowledgedByChildUid: CHILD_UID,
  }));
  await assertFails(update(ref(dbFor(OTHER_CHILD_UID),
    `v2/device_removals/${REMOVED_DEVICE_ID}`), {
    acknowledgedAt: Date.now(),
  }));
});

test("parent account data and pairing sessions remain parent-scoped", async () => {
  await assertSucceeds(get(ref(dbFor(PARENT_UID),
    `v2/parent_profiles/${PARENT_UID}`)));
  await assertFails(get(ref(dbFor(OTHER_PARENT_UID),
    `v2/parent_profiles/${PARENT_UID}`)));
  await assertSucceeds(set(ref(dbFor(PARENT_UID),
    "v2/pairing_sessions/session-new"), {
    parentUid: PARENT_UID,
    parentDeviceId: "parent-phone",
    baseQRKey: "qr-new",
    expiresAt: Date.now() + 60000,
  }));
  await assertFails(get(ref(dbFor(OTHER_PARENT_UID),
    "v2/pairing_sessions/session-new")));
});

test("permission alerts support the child reporter and owner parent detector", async () => {
  await assertSucceeds(set(ref(dbFor(CHILD_UID),
    `v2/permission_logs/${DEVICE_ID}/child-event`), {
    permissionName: "Usage Access",
    timestamp: Date.now(),
  }));
  await assertSucceeds(set(ref(dbFor(PARENT_UID),
    `v2/permission_logs/${DEVICE_ID}/parent-event`), {
    permissionName: "Uninstall Protection",
    timestamp: Date.now(),
  }));
  await assertFails(set(ref(dbFor(OTHER_PARENT_UID),
    `v2/permission_logs/${DEVICE_ID}/forged-event`), {
    timestamp: Date.now(),
  }));
});

test("legacy roots and unauthenticated access are denied", async () => {
  await assertFails(get(ref(dbFor(PARENT_UID), `parents/${PARENT_UID}`)));
  await assertFails(get(ref(env.unauthenticatedContext().database(),
    `v2/device_status/${DEVICE_ID}`)));
  await assertFails(set(ref(env.unauthenticatedContext().database(),
    `v2/device_status/${DEVICE_ID}`), {isOnline: true}));
});