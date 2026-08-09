const admin = require("firebase-admin");

const APPLY = process.argv.includes("--apply");
const confirmArg = process.argv.find((value) =>
  value.startsWith("--confirm-project="));
const CONFIRMED_PROJECT = confirmArg
  ? confirmArg.substring("--confirm-project=".length)
  : "";
const BATCH_SIZE = 500;

function loadServiceAccount() {
  if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    return JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
  }
  if (process.env.FIREBASE_SERVICE_ACCOUNT_BASE64) {
    return JSON.parse(Buffer.from(
      process.env.FIREBASE_SERVICE_ACCOUNT_BASE64,
      "base64").toString("utf8"));
  }
  throw new Error("Missing Firebase service account environment variable");
}

function initialize() {
  const databaseURL = process.env.FIREBASE_DATABASE_URL;
  if (!databaseURL) throw new Error("Missing FIREBASE_DATABASE_URL");
  const credential = loadServiceAccount();
  const projectId = String(credential.project_id || "");
  if (APPLY && (!CONFIRMED_PROJECT || CONFIRMED_PROJECT !== projectId)) {
    throw new Error(
      "Apply requires --confirm-project=" + projectId);
  }
  admin.initializeApp({
    credential: admin.credential.cert(credential),
    databaseURL,
  });
  return {databaseURL, projectId};
}

function object(value) {
  return value && typeof value === "object" ? value : {};
}

function firstString(value, keys) {
  for (const key of keys) {
    const candidate = value && value[key];
    if (typeof candidate === "string" && candidate.trim()) {
      return candidate.trim();
    }
  }
  return "";
}

function firstNumber(value, keys) {
  for (const key of keys) {
    const candidate = Number(value && value[key]);
    if (Number.isFinite(candidate) && candidate > 0) return candidate;
  }
  return 0;
}

function valueAt(root, path) {
  let cursor = root;
  for (const segment of path.split("/")) {
    if (!cursor || typeof cursor !== "object"
        || !Object.prototype.hasOwnProperty.call(cursor, segment)) {
      return undefined;
    }
    cursor = cursor[segment];
  }
  return cursor;
}

function sanitizeKey(value) {
  return String(value || "unknown").replace(/[.#$\[\]/]/g, "_");
}

function createPlanner(v2) {
  const updates = {};
  const counters = {};
  function put(path, value, counter) {
    if (value === undefined || value === null) return false;
    const relative = path.replace(/^v2\//, "");
    if (valueAt(v2, relative) !== undefined
        || Object.prototype.hasOwnProperty.call(updates, path)) {
      return false;
    }
    updates[path] = value;
    counters[counter] = (counters[counter] || 0) + 1;
    return true;
  }
  return {updates, counters, put};
}

async function readLegacy(db) {
  const paths = [
    "v2", "users", "parents", "parent_accounts",
    "device_connections", "child_devices", "device_apps",
    "installed_apps", "child_location", "device_status",
    "device_health", "susage_data", "block_commands", "app_timers",
    "child_permission_events", "permission_events",
  ];
  const snapshots = await Promise.all(paths.map((path) =>
    db.ref(path).once("value")));
  const result = {};
  paths.forEach((path, index) => {
    result[path] = snapshots[index].val() || {};
  });
  return result;
}

function migrateParents(data, planner) {
  const sources = [object(data.users), object(data.parent_accounts)];
  for (const source of sources) {
    for (const [key, raw] of Object.entries(source)) {
      const record = object(raw);
      const uid = firstString(record,
        ["uid", "authUid", "userId", "parentUid"]) || key;
      if (!uid) continue;
      const role = firstString(record, ["role", "userType"]);
      if (role && role !== "parent") continue;
      planner.put(`v2/users/${uid}`, {
        authUid: uid,
        role: "parent",
        status: firstString(record, ["status"]) || "active",
        createdAt: firstNumber(record, ["createdAt", "registeredAt"]),
        updatedAt: Date.now(),
      }, "users");
      planner.put(`v2/parent_profiles/${uid}`, {
        displayName: firstString(record,
          ["displayName", "name", "parentName", "userName"]) || "Parent",
        email: firstString(record, ["email"]),
        phone: firstString(record, ["phone", "phoneNumber"]),
        primaryDeviceId: firstString(record,
          ["primaryDeviceId", "deviceId"]),
        updatedAt: Date.now(),
      }, "parentProfiles");
    }
  }
}

function migrateRelationships(data, planner, report) {
  const connections = object(data.device_connections);
  for (const [deviceId, raw] of Object.entries(connections)) {
    const record = object(raw);
    const parentUid = firstString(record,
      ["parentUid", "parentUserId", "userId", "ownerParentUid"]);
    const childAuthUid = firstString(record,
      ["childAuthUid", "firebaseUid", "authUid", "childUid"]);
    const connectionId = firstString(record, ["connectionId", "sessionId"])
      || `migrated_${sanitizeKey(deviceId)}`;
    const linkedAt = firstNumber(record,
      ["linkedAt", "connectedAt", "timestamp", "createdAt"])
      || Date.now();
    if (!parentUid || !childAuthUid) {
      report.unresolvedOwnership.push({
        deviceId,
        reason: !parentUid ? "missing_parent_uid" : "missing_child_auth_uid",
      });
      continue;
    }
    const existingOwner = valueAt(data.v2, `device_owners/${deviceId}`);
    if (existingOwner && existingOwner.parentUid
        && existingOwner.parentUid !== parentUid) {
      report.ownershipConflicts.push({
        deviceId,
        legacyParentUid: parentUid,
        v2ParentUid: existingOwner.parentUid,
      });
      continue;
    }
    const rawName = firstString(record,
      ["childName", "deviceName", "userName"]);
    const name = rawName && rawName !== deviceId ? rawName : "Child Device";
    const owner = {
      parentUid,
      childAuthUid,
      connectionId,
      linkedAt,
      lastSeenAt: firstNumber(record,
        ["lastSeenAt", "lastSeen", "updatedAt"]) || linkedAt,
      status: "active",
      migrationSource: "device_connections",
    };
    planner.put(`v2/device_owners/${deviceId}`, owner, "deviceOwners");
    planner.put(`v2/devices/${deviceId}`, {
      deviceId,
      deviceName: name,
      childName: name,
      deviceModel: firstString(record, ["deviceModel", "model"]),
      deviceType: "child",
      platform: "android",
      childAuthUid,
      ownerParentUid: parentUid,
      connectionId,
      linkedAt,
      status: "active",
      migrationSource: "device_connections",
    }, "devices");
    planner.put(`v2/parent_device_links/${parentUid}/${deviceId}`, {
      deviceId,
      deviceName: name,
      childName: name,
      childAuthUid,
      connectionId,
      linkedAt,
      status: "active",
      migrationSource: "device_connections",
    }, "parentDeviceLinks");
  }
}

function migrateDevicePayloads(data, planner) {
  const inventories = [object(data.device_apps), object(data.installed_apps)];
  for (const source of inventories) {
    for (const [deviceId, raw] of Object.entries(source)) {
      const record = object(raw);
      const apps = object(record.apps);
      planner.put(`v2/device_installs/${deviceId}`,
        Object.keys(apps).length ? record : {
          apps: record,
          appCount: Object.keys(record).length,
          revisionId: `migrated_${Date.now()}`,
          lastUpdated: Date.now(),
        }, "deviceInstalls");
    }
  }
  for (const [deviceId, value] of Object.entries(object(data.child_location))) {
    planner.put(`v2/locations/${deviceId}`, value, "locations");
  }
  for (const root of ["device_status", "device_health"]) {
    for (const [deviceId, value] of Object.entries(object(data[root]))) {
      planner.put(`v2/${root}/${deviceId}`, value, root);
    }
  }
  for (const [deviceId, raw] of Object.entries(object(data.susage_data))) {
    const weekly = object(object(raw).weeklyData);
    for (const [dateKey, value] of Object.entries(weekly)) {
      if (/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) {
        planner.put(`v2/usage_daily/${deviceId}/${dateKey}`,
          value, "usageDays");
      }
    }
  }
}

function migratePoliciesAndEvents(data, planner) {
  for (const [deviceId, timers] of Object.entries(object(data.app_timers))) {
    planner.put(`v2/device_policies/${deviceId}/app_timers`,
      timers, "timerPolicies");
  }
  for (const [deviceId, commands] of Object.entries(object(data.block_commands))) {
    for (const [commandId, raw] of Object.entries(object(commands))) {
      const command = object(raw);
      const packageName = firstString(command,
        ["packageName", "package", "appPackage"]);
      if (!packageName) continue;
      const blocked = command.blocked === true
        || command.shouldBlock === true
        || String(command.action || "").toLowerCase() === "block";
      const appKey = sanitizeKey(packageName);
      planner.put(`v2/device_policies/${deviceId}/blocked_apps/${appKey}`, {
        packageName,
        blocked,
        desiredBlocked: blocked,
        policyId: `migrated_${sanitizeKey(commandId)}`,
        updatedAt: firstNumber(command,
          ["updatedAt", "timestamp", "createdAt"]) || Date.now(),
        migrationSource: "block_commands",
      }, "blockPolicies");
    }
  }
  for (const [deviceId, events] of
    Object.entries(object(data.child_permission_events))) {
    for (const [eventId, value] of Object.entries(object(events))) {
      planner.put(`v2/permission_logs/${deviceId}/${eventId}`,
        value, "permissionEvents");
    }
  }
  for (const devices of Object.values(object(data.permission_events))) {
    for (const [deviceId, events] of Object.entries(object(devices))) {
      for (const [eventId, value] of Object.entries(object(events))) {
        planner.put(`v2/permission_logs/${deviceId}/${eventId}`,
          value, "permissionEvents");
      }
    }
  }
}

async function applyBatches(db, updates) {
  const entries = Object.entries(updates);
  for (let index = 0; index < entries.length; index += BATCH_SIZE) {
    const batch = Object.fromEntries(entries.slice(index, index + BATCH_SIZE));
    await db.ref().update(batch);
  }
}

async function main() {
  const environment = initialize();
  const db = admin.database();
  const data = await readLegacy(db);
  const planner = createPlanner(object(data.v2));
  const report = {
    mode: APPLY ? "apply" : "dry-run",
    projectId: environment.projectId,
    databaseURL: environment.databaseURL,
    ownershipConflicts: [],
    unresolvedOwnership: [],
  };

  migrateParents(data, planner);
  migrateRelationships(data, planner, report);
  migrateDevicePayloads(data, planner);
  migratePoliciesAndEvents(data, planner);

  report.plannedWrites = Object.keys(planner.updates).length;
  report.counts = planner.counters;
  console.log(JSON.stringify(report, null, 2));
  if (!APPLY) {
    console.log("Dry run only. No Firebase data was changed.");
    return;
  }
  if (report.ownershipConflicts.length > 0) {
    throw new Error("Resolve ownership conflicts before applying migration");
  }
  await applyBatches(db, planner.updates);
  console.log(`Applied ${report.plannedWrites} idempotent v2 writes.`);
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await Promise.all(admin.apps.map((app) => app.delete()));
  });
