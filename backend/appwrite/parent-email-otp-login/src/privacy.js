const crypto = require("crypto");
const admin = require("firebase-admin");

const DAY_MS = 24 * 60 * 60 * 1000;
const CONSENT_VERSION = "guardian-monitoring-v1";
const POLICY_VERSION = "privacy-2026-06-12";

const CHILD_SCOPED_ROOTS = [
  "active_timers", "app_events", "app_timers", "app_usage_limits",
  "backup_parent_connections", "block_commands", "blocked_apps", "child_devices",
  "child_location", "child_permission_events", "children", "connected_devices",
  "daily_usage_limits", "device_apps", "device_connections", "device_health",
  "device_registry", "device_removal_markers", "device_sessions", "device_status",
  "emergency_connections", "enhanced_usage_data", "failed_connections", "focus_mode",
  "installed_apps", "location_history", "location_request", "logout_commands",
  "monitoring_relationships", "permission_events", "removed_devices", "smart_timers",
  "susage_data", "susage_stats", "susage_update_requests", "upload_triggers",
  "usage_7day", "usage_limiters", "usage_refresh_commands", "usage_snapshots"
];

const PARENT_SCOPED_ROOTS = [
  "legal_acceptances", "parent_accounts", "parent_consent_events", "parent_consents", "parent_devices",
  "parent_presets", "parent_timers", "parents", "user_devices", "users"
];

function json(res, status, data) {
  return res.json(data, status);
}

function parseBody(req) {
  if (!req.body) return {};
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
    return JSON.parse(Buffer.from(process.env.FIREBASE_SERVICE_ACCOUNT_BASE64, "base64").toString("utf8"));
  }
  throw new Error("Missing FIREBASE_SERVICE_ACCOUNT_JSON or FIREBASE_SERVICE_ACCOUNT_BASE64");
}

function initializeFirebase() {
  if (admin.apps.length) return;
  admin.initializeApp({
    credential: admin.credential.cert(loadServiceAccount()),
    databaseURL: process.env.FIREBASE_DATABASE_URL ||
      "https://master2-dbbc1-default-rtdb.firebaseio.com"
  });
}

function addDelete(updates, path) {
  if (path) updates[path] = null;
}

function childIdsFrom(snapshot, result) {
  if (!snapshot.exists()) return;
  const candidates = [
    snapshot.child("connectedChildDevices"),
    snapshot.child("connected_devices"),
    snapshot.child("children")
  ];
  for (const candidate of candidates) candidate.forEach((child) => result.add(child.key));
}

async function authenticate(body) {
  const token = String(body.firebaseIdToken || "");
  if (!token) throw new Error("Authentication required.");
  return admin.auth().verifyIdToken(token, true);
}

async function recordConsent(body) {
  const decoded = await authenticate(body);
  const eventId = String(body.eventId || "");
  const parentDeviceId = String(body.parentDeviceId || "");
  if (!/^[a-f0-9-]{36}$/i.test(eventId) || !parentDeviceId || parentDeviceId.length > 160) {
    throw new Error("Invalid pairing consent request.");
  }

  const now = Date.now();
  await admin.database().ref(`parent_consent_events/${decoded.uid}/${eventId}`).set({
    parentUid: decoded.uid,
    parentDeviceId,
    consentVersion: CONSENT_VERSION,
    policyVersion: POLICY_VERSION,
    affirmedAt: admin.database.ServerValue.TIMESTAMP,
    expiresAt: now + 2 * 60 * 1000,
    status: "pending_pairing"
  });
  return {success: true, message: "Guardian consent recorded."};
}

async function finalizePendingConsents() {
  const db = admin.database();
  const events = await db.ref("parent_consent_events").once("value");
  const updates = {};
  let finalized = 0;

  const parentEventSnapshots = [];
  events.forEach((snapshot) => parentEventSnapshots.push(snapshot));
  for (const parentEvents of parentEventSnapshots) {
    const parentUid = parentEvents.key;
    const parentSnapshot = await db.ref(`parents/${parentUid}/connectedChildDevices`).once("value");
    const eventSnapshots = [];
    parentEvents.forEach((snapshot) => eventSnapshots.push(snapshot));
    for (const event of eventSnapshots) {
      const value = event.val() || {};
      const affirmedAt = Number(value.affirmedAt || 0);
      const expiresAt = Number(value.expiresAt || 0);
      if (!affirmedAt || !expiresAt) continue;

      parentSnapshot.forEach((child) => {
        const childValue = child.val() || {};
        const connectedAt = Number(childValue.connectedAt || childValue.lastConnected || 0);
        if (connectedAt >= affirmedAt && connectedAt <= expiresAt + 5 * 60 * 1000) {
          updates[`parent_consents/${parentUid}/${child.key}`] = {
            parentUid,
            childDeviceId: child.key,
            parentDeviceId: value.parentDeviceId || null,
            consentVersion: value.consentVersion,
            policyVersion: value.policyVersion,
            affirmedAt,
            recordedAt: admin.database.ServerValue.TIMESTAMP,
            source: "guardian_pairing_confirmation",
            status: "active"
          };
          updates[`parent_consent_events/${parentUid}/${event.key}/completedChildDevices/${child.key}`] = true;
          finalized += 1;
        }
      });
    }
  }

  if (Object.keys(updates).length) await db.ref().update(updates);
  return finalized;
}

async function deleteAccount(body) {
  const decoded = await authenticate(body);
  const authTime = Number(decoded.auth_time || 0) * 1000;
  if (!authTime || Date.now() - authTime > 10 * 60 * 1000) {
    throw new Error("A recent sign-in is required for account deletion.");
  }

  const db = admin.database();
  const uid = decoded.uid;
  const [account, parentByUid, user] = await Promise.all([
    db.ref(`parent_accounts/${uid}`).once("value"),
    db.ref(`parents/${uid}`).once("value"),
    db.ref(`users/${uid}`).once("value")
  ]);

  const parentDeviceIds = new Set([uid]);
  const childIds = new Set();
  for (const snapshot of [account, parentByUid, user]) {
    const deviceId = snapshot.child("deviceId").val();
    if (deviceId) parentDeviceIds.add(deviceId);
    childIdsFrom(snapshot, childIds);
  }

  for (const parentDeviceId of parentDeviceIds) {
    childIdsFrom(await db.ref(`parents/${parentDeviceId}`).once("value"), childIds);
    childIdsFrom(await db.ref(`parent_devices/${parentDeviceId}`).once("value"), childIds);
  }

  const connections = await db.ref("device_connections").once("value");
  connections.forEach((connection) => {
    const value = connection.val() || {};
    if (value.parentUserId === uid || parentDeviceIds.has(value.parentDeviceId)) childIds.add(connection.key);
  });

  const updates = {};
  for (const root of PARENT_SCOPED_ROOTS) addDelete(updates, `${root}/${uid}`);
  for (const parentDeviceId of parentDeviceIds) {
    for (const root of ["parents", "parent_devices", "parent_timers", "parent_presets", "user_devices"]) {
      addDelete(updates, `${root}/${parentDeviceId}`);
    }
  }
  for (const childId of childIds) {
    for (const root of CHILD_SCOPED_ROOTS) addDelete(updates, `${root}/${childId}`);
    addDelete(updates, `device_blacklist/${uid}/${childId}`);
    addDelete(updates, `blocked_devices/${uid}/${childId}`);
  }

  const normalizedPhone = account.child("phoneNormalized").val();
  if (normalizedPhone) addDelete(updates, `phone_login_index/${normalizedPhone}`);
  const email = String(account.child("email").val() || decoded.email || "").trim().toLowerCase();
  if (email) {
    const otpKey = crypto.createHash("sha256").update(email).digest("hex");
    addDelete(updates, `parent_email_login_otps/${otpKey}`);
    addDelete(updates, `otp_send_rate_limits/email/${otpKey}`);
  }

  for (const root of ["qr_sessions", "qr_shares", "qr_share_codes"]) {
    const records = await db.ref(root).once("value");
    records.forEach((record) => {
      const value = record.val() || {};
      if (value.parentUid === uid || parentDeviceIds.has(value.parentDeviceId)) {
        addDelete(updates, `${root}/${record.key}`);
      }
    });
  }

  await db.ref().update(updates);
  await admin.firestore().collection("users").doc(uid).delete();
  await admin.auth().deleteUser(uid);
  return {success: true, message: "Account and associated data deleted."};
}

async function deleteByTimestamp(root, fields, cutoff) {
  const db = admin.database();
  const snapshot = await db.ref(root).once("value");
  const updates = {};
  snapshot.forEach((record) => {
    const value = record.val() || {};
    const timestamp = fields.map((field) => Number(value[field] || 0)).find((candidate) => candidate > 0);
    if (timestamp && timestamp < cutoff) addDelete(updates, `${root}/${record.key}`);
  });
  if (Object.keys(updates).length) await db.ref().update(updates);
  return Object.keys(updates).length;
}

function parseDateKey(key) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(key)) return 0;
  const parsed = Date.parse(`${key}T00:00:00Z`);
  return Number.isNaN(parsed) ? 0 : parsed;
}

async function pruneDatedChildren(root, cutoff) {
  const db = admin.database();
  const snapshot = await db.ref(root).once("value");
  const updates = {};
  snapshot.forEach((device) => {
    device.forEach((record) => {
      const value = record.val() || {};
      const timestamp = parseDateKey(record.key) || Number(value.expiresAt || value.timestamp ||
        value.createdAt || value.affirmedAt || value.windowStartedAt || value.lastSeen ||
        value.dateTimestamp || 0);
      if (timestamp && timestamp < cutoff) addDelete(updates, `${root}/${device.key}/${record.key}`);
    });
  });
  if (Object.keys(updates).length) await db.ref().update(updates);
  return Object.keys(updates).length;
}

async function pruneOrphanedDevices(cutoff) {
  const db = admin.database();
  const [parents, statuses] = await Promise.all([
    db.ref("parents").once("value"),
    db.ref("device_status").once("value")
  ]);
  const linked = new Set();
  parents.forEach((parent) => childIdsFrom(parent, linked));

  const orphanIds = [];
  statuses.forEach((status) => {
    const value = status.val() || {};
    const lastSeen = Number(value.lastSeen || value.timestamp || 0);
    if (!linked.has(status.key) && lastSeen && lastSeen < cutoff) orphanIds.push(status.key);
  });

  const updates = {};
  for (const childId of orphanIds) {
    for (const root of CHILD_SCOPED_ROOTS) addDelete(updates, `${root}/${childId}`);
  }
  if (Object.keys(updates).length) await db.ref().update(updates);
  return orphanIds.length;
}

async function enforceRetention() {
  const now = Date.now();
  const results = {};
  results.finalizedConsents = await finalizePendingConsents();
  results.qrSessions = await deleteByTimestamp("qr_sessions", ["expiresAt", "createdAt"], now);
  results.qrShares = await deleteByTimestamp("qr_shares", ["expiresAt", "timestamp", "createdAt"], now - DAY_MS);
  results.qrShareCodes = await deleteByTimestamp("qr_share_codes", ["expiresAt", "timestamp"], now - DAY_MS);
  results.consentEvents = await pruneDatedChildren("parent_consent_events", now - 30 * DAY_MS);
  results.otpRecords = await deleteByTimestamp("otp_records", ["expiresAt", "createdAt"], now);
  results.parentLoginOtps = await deleteByTimestamp("parent_email_login_otps", ["expiresAt", "createdAt"], now);
  results.otpRateLimits = await pruneDatedChildren("otp_send_rate_limits", now - DAY_MS);
  results.appEvents = await pruneDatedChildren("app_events", now - 30 * DAY_MS);
  results.permissionEvents = await pruneDatedChildren("permission_events", now - 90 * DAY_MS);
  results.childPermissionEvents = await pruneDatedChildren("child_permission_events", now - 90 * DAY_MS);
  results.locationHistory = await pruneDatedChildren("location_history", now - 30 * DAY_MS);
  results.usage = await pruneDatedChildren("susage_data", now - 30 * DAY_MS);
  results.usageStats = await pruneDatedChildren("susage_stats", now - 30 * DAY_MS);
  results.usage7Day = await pruneDatedChildren("usage_7day", now - 8 * DAY_MS);
  results.usageSnapshots = await pruneDatedChildren("usage_snapshots", now - 30 * DAY_MS);
  results.diagnostics = await pruneDatedChildren("device_health", now - 30 * DAY_MS);
  results.orphanedDevices = await pruneOrphanedDevices(now - 90 * DAY_MS);
  return results;
}

module.exports = {recordConsent, deleteAccount, enforceRetention};
