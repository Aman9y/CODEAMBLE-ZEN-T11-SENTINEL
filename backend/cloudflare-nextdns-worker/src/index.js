const NEXTDNS_API_BASE = "https://api.nextdns.io";

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return json({ success: true }, 200);
    }
    if (request.method !== "POST") {
      return json({ success: false, message: "Method not allowed." }, 405);
    }
    if (!env.NEXTDNS_API_KEY || !env.SENTINEL_CLIENT_SECRET) {
      return json({ success: false, message: "Worker is not configured." }, 500);
    }
    if (request.headers.get("X-Sentinel-Client-Secret") !== env.SENTINEL_CLIENT_SECRET) {
      return json({ success: false, message: "Unauthorized request." }, 401);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ success: false, message: "Invalid JSON body." }, 400);
    }

    const action = String(body.action || "");
    const profileId = normalizeProfileId(body.profileId);
    const domain = normalizeDomain(body.domain);
    const firebaseIdToken = String(body.firebaseIdToken || "");

    if (!firebaseIdToken) {
      return json({ success: false, message: "Missing Firebase token." }, 401);
    }
    if (!profileId) {
      return json({ success: false, message: "Missing NextDNS profile id." }, 400);
    }
    if (!domain || !isValidDomain(domain)) {
      return json({ success: false, message: "Enter a valid domain." }, 400);
    }

    if (action === "addDenylistDomain") {
      return addDenylistDomain(env, profileId, domain);
    }
    if (action === "removeDenylistDomain") {
      return removeDenylistDomain(env, profileId, domain);
    }
    return json({ success: false, message: "Unsupported action." }, 400);
  },
};

async function addDenylistDomain(env, profileId, domain) {
  const response = await fetch(`${NEXTDNS_API_BASE}/profiles/${profileId}/denylist`, {
    method: "POST",
    headers: nextDnsHeaders(env),
    body: JSON.stringify({ id: domain, active: true }),
  });
  return nextDnsJson(response, `${domain} added to NextDNS denylist.`);
}

async function removeDenylistDomain(env, profileId, domain) {
  const response = await fetch(`${NEXTDNS_API_BASE}/profiles/${profileId}/denylist/${encodeURIComponent(domain)}`, {
    method: "DELETE",
    headers: nextDnsHeaders(env),
  });
  return nextDnsJson(response, `${domain} removed from NextDNS denylist.`);
}

function nextDnsHeaders(env) {
  return {
    "Content-Type": "application/json",
    "X-Api-Key": env.NEXTDNS_API_KEY,
  };
}

async function nextDnsJson(response, successMessage) {
  const text = await response.text();
  let payload = {};
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = { raw: text };
    }
  }
  if (!response.ok) {
    return json({
      success: false,
      message: firstNextDnsError(payload) || `NextDNS request failed with HTTP ${response.status}.`,
      nextDns: payload,
    }, response.status);
  }
  const error = firstNextDnsError(payload);
  if (error) {
    return json({ success: false, message: error, nextDns: payload }, 400);
  }
  return json({ success: true, message: successMessage, nextDns: payload }, 200);
}

function firstNextDnsError(payload) {
  if (!payload || !Array.isArray(payload.errors) || payload.errors.length === 0) {
    return "";
  }
  return payload.errors[0].detail || payload.errors[0].code || "NextDNS rejected the request.";
}

function normalizeProfileId(value) {
  return String(value || "").trim().toLowerCase().replace(/[^a-z0-9]/g, "");
}

function normalizeDomain(value) {
  let domain = String(value || "").trim().toLowerCase();
  domain = domain.replace(/^https?:\/\//, "");
  domain = domain.split("/")[0];
  return domain;
}

function isValidDomain(domain) {
  return /^(?!-)(?:[a-z0-9-]{1,63}\.)+[a-z]{2,63}$/.test(domain);
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type, X-Sentinel-Client-Secret",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
    },
  });
}
