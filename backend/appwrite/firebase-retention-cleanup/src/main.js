const admin = require("firebase-admin");

const DAY_MS = 24 * 60 * 60 * 1000;
const DATABASE_URL = "https://master2-dbbc1-default-rtdb.firebaseio.com";
const TIME_ZONE = "Asia/Kolkata";

const TERMINAL_COMMAND_STATUSES = new Set([
  "completed",
  "complete",
  "failed",
  "failure",
  "cancelled",
  "canceled",
  "expired",
]);

const RETENTION = {
  usageDays: 7,
  terminalCommandDays: 7,
  permissionLogDays: 30,
  temporaryRequestDays: 1,
  staleDeviceDays: 90,
};

function json(res, status, data) {
  return res.json(data, status);
}

function parseBody(req) {
  if (!req || !req.body) return {};
  if (typeof req.body === "object") return req.body;
  try {
    return JSON.parse(req.body);
  } catch (_error) {
    return {};
  }
}

function loadServiceAccount() {
  if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    return JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
  }

  if (process.env.FIREBASE_SERVICE_ACCOUNT_BASE64) {
    return JSON.parse(
      Buffer.from(process.env.FIREBASE_SERVICE_ACCOUNT_BASE64, "base64").toString("utf8")
    );
  }

  throw new Error("Missing FIREBASE_SERVICE_ACCOUNT_JSON or FIREBASE_SERVICE_ACCOUNT_BASE64");
}

function initializeFirebase() {
  if (admin.apps.length > 0) return;

  admin.initializeApp({
    credential: admin.credential.cert(loadServiceAccount()),
    databaseURL: process.env.FIREBASE_DATABASE_URL || DATABASE_URL,
  });
}

function nowDateParts() {
  const formatter = new Intl.DateTimeFormat("en-CA", {
    timeZone: TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });

  const parts = {};
  for (const part of formatter.formatToParts(new Date())) {
    if (part.type !== "literal") parts[part.type] = part.value;
  }

  return {
    year: Number(parts.year),
    month: Number(parts.month),
    day: Number(parts.day),
  };
}

function dateKeyFromParts(parts) {
  const year = String(parts.year).padStart(4, "0");
  const month = String(parts.month).padStart(2, "0");
  const day = String(parts.day).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function addDays(parts, offsetDays) {
  const date = new Date(Date.UTC(parts.year, parts.month - 1, parts.day + offsetDays));
  return {
    year: date.getUTCFullYear(),
    month: date.getUTCMonth() + 1,
    day: date.getUTCDate(),
  };
}

function oldestKeptDateKey(daysToKeep) {
  return dateKeyFromParts(addDays(nowDateParts(), -(daysToKeep - 1)));
}

function isDateKey(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(String(value || ""));
}

function addDelete(updates, path, counters, counterName) {
  if (Object.prototype.hasOwnProperty.call(updates, path)) return;
  updates[path] = null;
  counters[counterName] = (counters[counterName] || 0) + 1;
}

function addSet(updates, path, value, counters, counterName) {
  updates[path] = value;
  counters[counterName] = (counters[counterName] || 0) + 1;
}

function timestampFrom(value, fields) {
  if (!value || typeof value !== "object") return 0;

  for (const field of fields) {
    const timestamp = Number(value[field] || 0);
    if (timestamp > 0) return timestamp;
  }

  return 0;
}

function normalizedStatus(value) {
  return String((value && value.status) || "").trim().toLowerCase();
}

async function pruneDateKeyChildren(db, root, oldestKeptKey, updates, counters, counterName) {
  const snapshot = await db.ref(root).once("value");
  snapshot.forEach((device) => {
    device.forEach((record) => {
      if (isDateKey(record.key) && record.key < oldestKeptKey) {
        addDelete(updates, `${root}/${device.key}/${record.key}`, counters, counterName);
      }
    });
  });
}

async function deleteFlatRecordsByTimestamp(db, root, fields, cutoff, updates, counters, counterName) {
  const snapshot = await db.ref(root).once("value");
  snapshot.forEach((record) => {
    const value = record.val() || {};
    const timestamp = timestampFrom(value, fields);
    if (timestamp > 0 && timestamp < cutoff) {
      addDelete(updates, `${root}/${record.key}`, counters, counterName);
    }
  });
}

async function deleteTwoLevelRecordsByTimestamp(db, root, fields, cutoff, updates, counters, counterName) {
  const snapshot = await db.ref(root).once("value");
  snapshot.forEach((parent) => {
    parent.forEach((record) => {
      const value = record.val() || {};
      const timestamp = timestampFrom(value, fields);
      if (timestamp > 0 && timestamp < cutoff) {
        addDelete(updates, `${root}/${parent.key}/${record.key}`, counters, counterName);
      }
    });
  });
}

async function pruneImmediateTemporaryData(db, now, updates, counters) {
  const oneDayCutoff = now - DAY_MS;
  const thirtyDayCutoff = now - RETENTION.permissionLogDays * DAY_MS;

  await deleteFlatRecordsByTimestamp(
    db,
    "qr_sessions",
    ["expiresAt"],
    now,
    updates,
    counters,
    "expiredQrSessions"
  );
  await deleteFlatRecordsByTimestamp(
    db,
    "qr_sessions",
    ["createdAt", "timestamp"],
    oneDayCutoff,
    updates,
    counters,
    "oldQrSessions"
  );
  await deleteFlatRecordsByTimestamp(
    db,
    "qr_shares",
    ["expiresAt", "createdAt", "timestamp"],
    oneDayCutoff,
    updates,
    counters,
    "qrShares"
  );
  await deleteFlatRecordsByTimestamp(
    db,
    "qr_share_codes",
    ["expiresAt", "createdAt", "timestamp"],
    oneDayCutoff,
    updates,
    counters,
    "qrShareCodes"
  );
  await deleteFlatRecordsByTimestamp(
    db,
    "parent_email_login_otps",
    ["expiresAt", "createdAt"],
    now,
    updates,
    counters,
    "parentLoginOtps"
  );
  await deleteFlatRecordsByTimestamp(
    db,
    "otp_records",
    ["expiresAt", "createdAt"],
    now,
    updates,
    counters,
    "otpRecords"
  );
  await deleteTwoLevelRecordsByTimestamp(
    db,
    "otp_send_rate_limits",
    ["windowStartedAt", "lastSentAt", "updatedAt", "createdAt"],
    oneDayCutoff,
    updates,
    counters,
    "otpRateLimits"
  );
  await deleteTwoLevelRecordsByTimestamp(
    db,
    "parent_consent_events",
    ["expiresAt", "affirmedAt", "createdAt", "updatedAt"],
    thirtyDayCutoff,
    updates,
    counters,
    "parentConsentEvents"
  );
}

async function pruneLegacyWeeklyUsage(db, oldestKeptKey, updates, counters) {
  const snapshot = await db.ref("susage_data").once("value");
  snapshot.forEach((device) => {
    device.child("weeklyData").forEach((day) => {
      if (isDateKey(day.key) && day.key < oldestKeptKey) {
        addDelete(updates, `susage_data/${device.key}/weeklyData/${day.key}`, counters, "legacyUsageDaily");
      }
    });
  });
}

async function pruneCommands(db, now, updates, counters) {
  const terminalCutoff = now - RETENTION.terminalCommandDays * DAY_MS;
  const snapshot = await db.ref("v2/commands").once("value");

  snapshot.forEach((device) => {
    device.forEach((command) => {
      const value = command.val() || {};
      const expiresAt = Number(value.expiresAt || 0);

      if (expiresAt > 0 && expiresAt < now) {
        addDelete(updates, `v2/commands/${device.key}/${command.key}`, counters, "expiredCommands");
        return;
      }

      if (!TERMINAL_COMMAND_STATUSES.has(normalizedStatus(value))) return;

      const timestamp = timestampFrom(value, [
        "completedAt",
        "finishedAt",
        "updatedAt",
        "executedAt",
        "createdAt",
        "issuedAt",
        "timestamp",
      ]);

      if (timestamp > 0 && timestamp < terminalCutoff) {
        addDelete(updates, `v2/commands/${device.key}/${command.key}`, counters, "terminalCommands");
      }
    });
  });
}

async function prunePermissionLogs(db, now, updates, counters) {
  const cutoff = now - RETENTION.permissionLogDays * DAY_MS;
  const snapshot = await db.ref("v2/permission_logs").once("value");

  snapshot.forEach((device) => {
    device.forEach((event) => {
      const value = event.val() || {};
      const timestamp = timestampFrom(value, [
        "createdAt",
        "changedAt",
        "updatedAt",
        "timestamp",
        "eventAt",
      ]);

      if (timestamp > 0 && timestamp < cutoff) {
        addDelete(updates, `v2/permission_logs/${device.key}/${event.key}`, counters, "permissionLogs");
      }
    });
  });
}

async function pruneTemporaryRequests(db, now, updates, counters) {
  const cutoff = now - RETENTION.temporaryRequestDays * DAY_MS;
  const snapshot = await db.ref("v2/timer_state_requests").once("value");

  snapshot.forEach((device) => {
    const value = device.val() || {};
    const deviceExpiresAt = Number(value.expiresAt || 0);
    const deviceStatus = normalizedStatus(value);
    const deviceTimestamp = timestampFrom(value, ["completedAt", "updatedAt", "createdAt", "issuedAt", "timestamp"]);

    if (deviceExpiresAt > 0 && deviceExpiresAt < now) {
      addDelete(updates, `v2/timer_state_requests/${device.key}`, counters, "timerStateRequests");
      return;
    }

    if (TERMINAL_COMMAND_STATUSES.has(deviceStatus) && deviceTimestamp > 0 && deviceTimestamp < cutoff) {
      addDelete(updates, `v2/timer_state_requests/${device.key}`, counters, "timerStateRequests");
      return;
    }

    device.forEach((request) => {
      const requestValue = request.val() || {};
      if (!requestValue || typeof requestValue !== "object") return;

      const expiresAt = Number(requestValue.expiresAt || 0);
      const status = normalizedStatus(requestValue);
      const timestamp = timestampFrom(requestValue, [
        "completedAt",
        "updatedAt",
        "createdAt",
        "issuedAt",
        "timestamp",
      ]);

      if (
        (expiresAt > 0 && expiresAt < now) ||
        (TERMINAL_COMMAND_STATUSES.has(status) && timestamp > 0 && timestamp < cutoff)
      ) {
        addDelete(
          updates,
          `v2/timer_state_requests/${device.key}/${request.key}`,
          counters,
          "timerStateRequests"
        );
      }
    });
  });
}

async function pruneDeviceRemovals(db, now, updates, counters) {
  const cutoff = now - RETENTION.temporaryRequestDays * DAY_MS;
  const snapshot = await db.ref("v2/device_removals").once("value");

  snapshot.forEach((device) => {
    const value = device.val() || {};
    const expiresAt = Number(value.expiresAt || 0);
    const issuedAt = Number(value.issuedAt || 0);
    const status = normalizedStatus(value);

    if (
      (expiresAt > 0 && expiresAt < now) ||
      (TERMINAL_COMMAND_STATUSES.has(status) && issuedAt > 0 && issuedAt < cutoff)
    ) {
      addDelete(updates, `v2/device_removals/${device.key}`, counters, "deviceRemovals");
    }
  });
}

async function markStaleDevices(db, now, updates, counters) {
  const cutoff = now - RETENTION.staleDeviceDays * DAY_MS;
  const [owners, statuses, health] = await Promise.all([
    db.ref("v2/device_owners").once("value"),
    db.ref("v2/device_status").once("value"),
    db.ref("v2/device_health").once("value"),
  ]);

  owners.forEach((owner) => {
    const ownerValue = owner.val() || {};
    if (normalizedStatus(ownerValue) === "stale") return;

    const statusValue = statuses.child(owner.key).val() || {};
    const healthValue = health.child(owner.key).val() || {};
    const lastSeen = Math.max(
      timestampFrom(statusValue, ["lastHeartbeat", "lastSeen", "updatedAt", "timestamp"]),
      timestampFrom(healthValue, ["lastHeartbeat", "lastSeen", "updatedAt", "timestamp"]),
      timestampFrom(ownerValue, ["updatedAt", "linkedAt"])
    );

    if (lastSeen > 0 && lastSeen < cutoff) {
      addSet(updates, `v2/device_owners/${owner.key}/status`, "stale", counters, "staleDevicesMarked");
      addSet(updates, `v2/device_owners/${owner.key}/staleSince`, now, counters, "staleDeviceFields");
      addSet(updates, `v2/devices/${owner.key}/status`, "stale", counters, "staleDeviceFields");
      addSet(updates, `v2/devices/${owner.key}/staleSince`, now, counters, "staleDeviceFields");

      if (ownerValue.parentUid) {
        addSet(
          updates,
          `v2/parent_device_links/${ownerValue.parentUid}/${owner.key}/status`,
          "stale",
          counters,
          "staleDeviceFields"
        );
        addSet(
          updates,
          `v2/parent_device_links/${ownerValue.parentUid}/${owner.key}/staleSince`,
          now,
          counters,
          "staleDeviceFields"
        );
      }
    }
  });
}

async function runCleanup(options = {}) {
  const db = admin.database();
  const now = Date.now();
  const oldestUsageDate = oldestKeptDateKey(RETENTION.usageDays);
  const includeLegacyUsage = options.includeLegacyUsage !== false;
  const counters = {};
  const updates = {};

  await pruneImmediateTemporaryData(db, now, updates, counters);
  await pruneDateKeyChildren(db, "v2/usage_daily", oldestUsageDate, updates, counters, "usageDaily");
  if (includeLegacyUsage) {
    await pruneLegacyWeeklyUsage(db, oldestUsageDate, updates, counters);
  }
  await pruneCommands(db, now, updates, counters);
  await prunePermissionLogs(db, now, updates, counters);
  await pruneTemporaryRequests(db, now, updates, counters);
  await pruneDeviceRemovals(db, now, updates, counters);
  await markStaleDevices(db, now, updates, counters);

  const updateCount = Object.keys(updates).length;
  if (!options.dryRun && updateCount > 0) {
    await db.ref().update(updates);
  }

  return {
    success: true,
    dryRun: Boolean(options.dryRun),
    oldestUsageDate,
    updateCount,
    counters,
  };
}

module.exports = async ({ req, res, log, error }) => {
  try {
    initializeFirebase();

    const body = parseBody(req);
    const includeLegacyUsage = body.includeLegacyUsage !== undefined
      ? Boolean(body.includeLegacyUsage)
      : process.env.CLEANUP_INCLUDE_LEGACY_USAGE !== "false";

    const result = await runCleanup({
      dryRun: Boolean(body.dryRun),
      includeLegacyUsage,
    });

    log(`Firebase retention cleanup completed: ${JSON.stringify(result)}`);
    return json(res, 200, result);
  } catch (err) {
    error(err.stack || err.message || String(err));
    return json(res, 500, {
      success: false,
      message: err.message || "Retention cleanup failed.",
    });
  }
};

module.exports.runCleanup = runCleanup;
