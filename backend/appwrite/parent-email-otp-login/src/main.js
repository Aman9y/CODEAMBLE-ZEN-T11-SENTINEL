const crypto = require("crypto");
const admin = require("firebase-admin");
const nodemailer = require("nodemailer");
const privacy = require("./privacy");

const OTP_LENGTH = 6;
const OTP_TTL_MS = 5 * 60 * 1000;
const MAX_ATTEMPTS = 5;
const SEND_COOLDOWN_MS = 60 * 1000;
const SEND_WINDOW_MS = 60 * 60 * 1000;
const MAX_SENDS_PER_EMAIL = 5;
const MAX_SENDS_PER_IP = 20;

function json(res, status, data) {
  return res.json(data, status);
}

function normalizeEmail(email) {
  return String(email || "").trim().toLowerCase();
}

function otpKeyForEmail(email) {
  return crypto.createHash("sha256").update(normalizeEmail(email)).digest("hex");
}

function rateLimitKey(value) {
  return crypto.createHash("sha256").update(String(value || "unknown")).digest("hex");
}

function getClientIp(req) {
  const headers = (req && req.headers) || {};
  return (
    headers["x-appwrite-client-ip"] ||
    headers["x-forwarded-for"] ||
    headers["x-real-ip"] ||
    "unknown"
  ).split(",")[0].trim();
}

async function consumeSendLimit(scope, identifier, maxSends, cooldownMs) {
  const now = Date.now();
  const ref = admin.database().ref(`otp_send_rate_limits/${scope}/${rateLimitKey(identifier)}`);
  let rejection = null;

  const result = await ref.transaction((current) => {
    const record = current || {};
    const windowStartedAt = Number(record.windowStartedAt || 0);
    const lastSentAt = Number(record.lastSentAt || 0);
    const count = Number(record.count || 0);

    if (windowStartedAt && now - windowStartedAt < SEND_WINDOW_MS) {
      if (cooldownMs > 0 && lastSentAt && now - lastSentAt < cooldownMs) {
        const retryAfterSeconds = Math.ceil((cooldownMs - (now - lastSentAt)) / 1000);
        rejection = {
          retryAfterSeconds,
          message: `Please wait ${retryAfterSeconds} seconds before requesting another OTP.`,
        };
        return;
      }

      if (count >= maxSends) {
        const retryAfterSeconds = Math.ceil((SEND_WINDOW_MS - (now - windowStartedAt)) / 1000);
        rejection = {
          retryAfterSeconds,
          message: `Too many OTP requests. Try again in ${Math.ceil(retryAfterSeconds / 60)} minutes.`,
        };
        return;
      }

      return { windowStartedAt, lastSentAt: now, count: count + 1 };
    }

    return { windowStartedAt: now, lastSentAt: now, count: 1 };
  });

  if (!result.committed) {
    return {
      allowed: false,
      ...(rejection || {
        retryAfterSeconds: 60,
        message: "Please wait before requesting another OTP.",
      }),
    };
  }

  return { allowed: true };
}

async function enforceSendRateLimit(email, clientIp) {
  const emailLimit = await consumeSendLimit(
    "email",
    normalizeEmail(email),
    MAX_SENDS_PER_EMAIL,
    SEND_COOLDOWN_MS
  );
  if (!emailLimit.allowed) return emailLimit;
  if (!clientIp || clientIp === "unknown") return { allowed: true };
  return consumeSendLimit("ip", clientIp, MAX_SENDS_PER_IP, 0);
}

function hashOtp(email, otp, salt) {
  return crypto
    .createHash("sha256")
    .update(`${normalizeEmail(email)}:${otp}:${salt}`)
    .digest("hex");
}

function generateOtp() {
  return String(crypto.randomInt(0, 1000000)).padStart(OTP_LENGTH, "0");
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
  const rawJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  const base64Json = process.env.FIREBASE_SERVICE_ACCOUNT_BASE64;

  if (rawJson) {
    return JSON.parse(rawJson);
  }

  if (base64Json) {
    return JSON.parse(Buffer.from(base64Json, "base64").toString("utf8"));
  }

  throw new Error("Missing FIREBASE_SERVICE_ACCOUNT_JSON or FIREBASE_SERVICE_ACCOUNT_BASE64");
}

function initializeFirebase() {
  if (admin.apps.length > 0) return;

  admin.initializeApp({
    credential: admin.credential.cert(loadServiceAccount()),
    databaseURL:
      process.env.FIREBASE_DATABASE_URL ||
      "https://master2-dbbc1-default-rtdb.firebaseio.com",
  });
}

async function findParentByEmail(email) {
  const emailLower = normalizeEmail(email);
  const parentAccounts = admin.database().ref("parent_accounts");

  const lowerSnapshot = await parentAccounts
    .orderByChild("emailLower")
    .equalTo(emailLower)
    .limitToFirst(1)
    .once("value");

  if (lowerSnapshot.exists()) {
    const uid = Object.keys(lowerSnapshot.val())[0];
    return { uid, profile: lowerSnapshot.val()[uid] || {} };
  }

  const exactSnapshot = await parentAccounts
    .orderByChild("email")
    .equalTo(email)
    .limitToFirst(1)
    .once("value");

  if (exactSnapshot.exists()) {
    const uid = Object.keys(exactSnapshot.val())[0];
    return { uid, profile: exactSnapshot.val()[uid] || {} };
  }

  return null;
}

async function sendOtpEmail(email, otp) {
  const smtpUser = process.env.EMAIL_USER;
  const smtpPass = process.env.EMAIL_PASS;

  if (smtpUser && smtpPass) {
    const transporter = nodemailer.createTransport({
      service: "gmail",
      auth: {
        user: smtpUser,
        pass: smtpPass,
      },
    });

    await transporter.sendMail({
      from: {
        name: "Sentinel App",
        address: process.env.EMAIL_FROM || smtpUser,
      },
      to: email,
      subject: "Your OTP Code - Sentinel",
      text: `Your Sentinel OTP is ${otp}. This code expires in 5 minutes.`,
      html: `
        <!doctype html>
        <html lang="en">
        <body style="margin:0;padding:24px;background:#ffffff;color:#202124;font-family:Arial,sans-serif;line-height:1.5;">
        <div style="max-width:600px;margin:0 auto;padding:28px 32px;">
          <div style="text-align:center;margin-bottom:28px;">
            <div style="color:#2196f3;font-size:25px;font-weight:700;">&#128272; Sentinel</div>
            <div style="color:#2196f3;font-size:19px;font-weight:700;margin-top:10px;">Email Verification</div>
          </div>
          <p>Hello,</p>
          <p>You have requested an OTP code for parent account verification. Please use the following code:</p>
          <div style="margin:20px 0;padding:20px;text-align:center;background:#f8f9fa;border-radius:8px;color:#4caf50;font-size:30px;font-weight:700;letter-spacing:7px;">${otp}</div>
          <p><strong>Important:</strong></p>
          <ul style="padding-left:38px;">
            <li>This OTP is valid for <strong>5 minutes</strong> only</li>
            <li>Do not share this code with anyone</li>
            <li>If you didn't request this code, please ignore this email</li>
          </ul>
          <p>Enter this code in your Sentinel app to complete the verification process.</p>
          <div style="margin-top:28px;padding-top:20px;border-top:1px solid #eeeeee;text-align:center;color:#9aa0a6;font-size:12px;">
            <p>This email was sent automatically by Sentinel App</p>
            <p>Time: ${new Date().toLocaleString("en-US")}</p>
            <p>Function: Fixed Version v1.1</p>
          </div>
        </div>
        </body>
        </html>
      `,
    });
    return;
  }

  const endpoint = process.env.APPWRITE_ENDPOINT || "https://nyc.cloud.appwrite.io/v1";
  const projectId = process.env.APPWRITE_PROJECT_ID || "6954c478002421753c93";
  const functionId = process.env.APPWRITE_EMAIL_FUNCTION_ID || "6954c61d0039f7129141";
  const apiKey = process.env.APPWRITE_API_KEY || process.env.APPWRITE_FUNCTION_API_KEY;

  const response = await fetch(`${endpoint}/functions/${functionId}/executions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Appwrite-Project": projectId,
      ...(apiKey ? { "X-Appwrite-Key": apiKey } : {}),
    },
    body: JSON.stringify({
      async: false,
      path: "/",
      method: "POST",
      headers: {},
      body: JSON.stringify({
        to: email,
        subject: "Your OTP Code - Sentinel",
        otp,
        userType: "parent",
        expirationMinutes: OTP_TTL_MS / 60000,
      }),
    }),
  });

  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new Error(`Email function failed: ${response.status} ${body}`);
  }
}

async function sendLoginOtp(email, clientIp) {
  const rateLimit = await enforceSendRateLimit(email, clientIp);
  if (!rateLimit.allowed) {
    return { success: false, rateLimited: true, ...rateLimit };
  }

  const parent = await findParentByEmail(email);
  if (!parent) {
    return {
      success: false,
      message: "No parent account found for this email.",
    };
  }

  const otp = generateOtp();
  const salt = crypto.randomBytes(16).toString("hex");
  const now = Date.now();

  await admin.database().ref(`parent_email_login_otps/${otpKeyForEmail(email)}`).set({
    uid: parent.uid,
    email: normalizeEmail(email),
    otpHash: hashOtp(email, otp, salt),
    salt,
    attempts: 0,
    used: false,
    createdAt: now,
    expiresAt: now + OTP_TTL_MS,
  });

  await sendOtpEmail(email, otp);

  return {
    success: true,
    message: "Verification code sent.",
    expiresInSeconds: OTP_TTL_MS / 1000,
  };
}

async function sendSignupOtp(email, otp, userType, clientIp) {
  if (!/^\d{6}$/.test(String(otp || "").trim())) {
    return { success: false, message: "Invalid OTP payload." };
  }

  const rateLimit = await enforceSendRateLimit(email, clientIp);
  if (!rateLimit.allowed) {
    return { success: false, rateLimited: true, ...rateLimit };
  }

  await sendOtpEmail(email, String(otp).trim(), userType || "parent");
  return {
    success: true,
    message: "Verification code sent.",
    expiresInSeconds: OTP_TTL_MS / 1000,
  };
}

async function verifyLoginOtp(email, otp) {
  if (!/^\d{6}$/.test(String(otp || "").trim())) {
    return {
      success: false,
      message: "Enter a valid 6-digit code.",
    };
  }

  const ref = admin.database().ref(`parent_email_login_otps/${otpKeyForEmail(email)}`);
  const snapshot = await ref.once("value");
  const record = snapshot.val();

  if (!record || record.used || Date.now() > Number(record.expiresAt || 0)) {
    return {
      success: false,
      message: "Code expired. Request a new code.",
    };
  }

  const attempts = Number(record.attempts || 0);
  if (attempts >= MAX_ATTEMPTS) {
    return {
      success: false,
      message: "Too many attempts. Request a new code.",
    };
  }

  const expectedHash = hashOtp(email, String(otp).trim(), record.salt);
  if (expectedHash !== record.otpHash) {
    await ref.child("attempts").set(attempts + 1);
    return {
      success: false,
      message: "Invalid code. Please try again.",
    };
  }

  await ref.update({
    used: true,
    verifiedAt: Date.now(),
  });

  const customToken = await admin.auth().createCustomToken(record.uid, {
    provider: "email_otp",
    role: "parent",
  });

  return {
    success: true,
    message: "OTP verified.",
    customToken,
    uid: record.uid,
  };
}

module.exports = async ({ req, res, log, error }) => {
  try {
    initializeFirebase();

    const body = parseBody(req);
    const action = String(body.action || "").trim();
    const email = normalizeEmail(body.email);
    const clientIp = getClientIp(req);

    if (action === "recordConsent") {
      return json(res, 200, await privacy.recordConsent(body));
    }

    if (action === "deleteAccount") {
      return json(res, 200, await privacy.deleteAccount(body));
    }

    const trigger = String((req.headers || {})["x-appwrite-trigger"] || "").toLowerCase();
    if (!action && trigger.includes("schedule")) {
      const results = await privacy.enforceRetention();
      log(`Retention completed: ${JSON.stringify(results)}`);
      return json(res, 200, {success: true, results});
    }

    if (!email || !email.includes("@")) {
      return json(res, 400, {
        success: false,
        message: "Enter a valid email address.",
      });
    }

    if (action === "send") {
      const result = await sendLoginOtp(email, clientIp);
      return json(res, result.success ? 200 : result.rateLimited ? 429 : 404, result);
    }

    if (action === "send-signup") {
      const result = await sendSignupOtp(email, body.otp, body.userType, clientIp);
      return json(res, result.success ? 200 : result.rateLimited ? 429 : 400, result);
    }

    if (action === "verify") {
      const result = await verifyLoginOtp(email, body.otp);
      return json(res, result.success ? 200 : 400, result);
    }

    return json(res, 400, {
      success: false,
      message: "Unsupported OTP action.",
    });
  } catch (err) {
    error(err.stack || err.message || String(err));
    const message = err.message || "Service failed. Please try again later.";
    const authFailure = /Authentication|required|token/i.test(message);
    return json(res, authFailure ? 401 : 500, {
      success: false,
      message,
    });
  }
};
