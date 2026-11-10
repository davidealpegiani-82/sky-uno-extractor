export interface Env {
  DB: D1Database;
  SKY_ADMIN_KEY: string;
}

type Channel = {
  id?: string;
  name: string;
  url: string;
  logo?: string;
  category?: string;
  headers?: Record<string, string>;
  expiry?: string | null;
};

type PublishedConfig = {
  schemaVersion: number;
  updateId: string;
  publishedAt?: string;
  channels: Channel[];
};

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store, max-age=0",
};

function response(data: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...JSON_HEADERS, ...extra } });
}

function cors(request: Request): HeadersInit {
  return {
    "access-control-allow-origin": request.headers.get("origin") || "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "authorization,content-type,x-sky-admin-key",
    "access-control-max-age": "86400",
    "vary": "Origin",
  };
}

function withCors(request: Request, result: Response): Response {
  const headers = new Headers(result.headers);
  for (const [key, value] of Object.entries(cors(request))) headers.set(key, value);
  return new Response(result.body, { status: result.status, statusText: result.statusText, headers });
}

function equalSecret(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let mismatch = 0;
  for (let i = 0; i < a.length; i++) mismatch |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return mismatch === 0;
}

async function sha256(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((v) => v.toString(16).padStart(2, "0")).join("");
}

function bearer(request: Request): string {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : "";
}

async function readJson(request: Request, maxBytes = 1_000_000): Promise<any> {
  const raw = await request.text();
  if (new TextEncoder().encode(raw).byteLength > maxBytes) throw new Error("PAYLOAD_TOO_LARGE");
  try { return JSON.parse(raw); } catch { throw new Error("INVALID_JSON"); }
}

function validChannel(channel: any): channel is Channel {
  if (!channel || typeof channel !== "object") return false;
  if (typeof channel.name !== "string" || !channel.name.trim() || channel.name.length > 160) return false;
  if (typeof channel.url !== "string" || channel.url.length > 8192) return false;
  try {
    const url = new URL(channel.url);
    if (url.protocol !== "http:" && url.protocol !== "https:") return false;
  } catch { return false; }
  if (channel.logo != null && (typeof channel.logo !== "string" || channel.logo.length > 4096)) return false;
  if (channel.headers != null && (typeof channel.headers !== "object" || Array.isArray(channel.headers))) return false;
  return true;
}

async function requireAdmin(request: Request, env: Env): Promise<boolean> {
  const supplied = request.headers.get("x-sky-admin-key") || "";
  return supplied.length > 0 && equalSecret(supplied, env.SKY_ADMIN_KEY);
}

async function requireDevice(request: Request, env: Env): Promise<{ id: string } | null> {
  const token = bearer(request);
  if (!token || token.length < 32 || token.length > 256) return null;
  const tokenHash = await sha256(token);
  const row = await env.DB.prepare(
    "SELECT device_id FROM devices WHERE token_hash = ? AND status = 'approved' LIMIT 1"
  ).bind(tokenHash).first<{ device_id: string }>();
  return row ? { id: row.device_id } : null;
}

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";

  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(request) });
  if (request.method === "GET" && path === "/health") return response({ ok: true, service: "sky-cloud-sync", apiVersion: 1 });

  if (request.method === "POST" && path === "/v1/pair/start") {
    let body: any;
    try { body = await readJson(request, 16_384); } catch (e) {
      return response({ error: String(e) === "Error: PAYLOAD_TOO_LARGE" ? "payload_too_large" : "invalid_json" }, 400);
    }
    const secret = typeof body.deviceSecret === "string" ? body.deviceSecret : "";
    const installId = typeof body.installId === "string" ? body.installId.slice(0, 80) : "";
    const deviceName = typeof body.deviceName === "string" ? body.deviceName.trim().slice(0, 80) : "";
    const platform = typeof body.platform === "string" ? body.platform.slice(0, 40) : "unknown";
    if (secret.length < 32 || secret.length > 256 || !installId || !deviceName) {
      return response({ error: "invalid_pairing_request" }, 400);
    }

    // Limita l'abuso dell'associazione: massimo 10 richieste per IP in un'ora.
    const clientIp = request.headers.get("CF-Connecting-IP") || "unknown";
    const ipHash = await sha256(clientIp);
    const since = Date.now() - 60 * 60 * 1000;
    await env.DB.prepare("DELETE FROM pair_attempts WHERE created_at < ?").bind(Date.now() - 24 * 60 * 60 * 1000).run();
    const attempts = await env.DB.prepare(
      "SELECT COUNT(*) AS total FROM pair_attempts WHERE ip_hash = ? AND created_at > ?"
    ).bind(ipHash, since).first<{ total: number }>();
    if ((attempts?.total || 0) >= 10) return response({ error: "pairing_rate_limited" }, 429);
    await env.DB.prepare("INSERT INTO pair_attempts (ip_hash, created_at) VALUES (?, ?)").bind(ipHash, Date.now()).run();

    const pairingId = crypto.randomUUID();
    const tokenHash = await sha256(secret);
    // Una nuova associazione dalla stessa installazione invalida l'eventuale richiesta precedente.
    await env.DB.prepare(
      "UPDATE devices SET status = 'expired', pairing_code = NULL WHERE install_id = ? AND status = 'pending'"
    ).bind(installId).run();
    let code = "";
    for (let attempt = 0; attempt < 8; attempt++) {
      const random = new Uint32Array(1);
      crypto.getRandomValues(random);
      code = String(random[0] % 1_000_000).padStart(6, "0");
      const existing = await env.DB.prepare(
        "SELECT device_id FROM devices WHERE pairing_code = ? AND status = 'pending' AND expires_at > ? LIMIT 1"
      ).bind(code, Date.now()).first();
      if (!existing) break;
      code = "";
    }
    if (!code) return response({ error: "pairing_unavailable" }, 503);

    const now = Date.now();
    const expiresAt = now + 10 * 60 * 1000;
    await env.DB.prepare(
      "INSERT INTO devices (device_id, install_id, device_name, platform, token_hash, status, pairing_code, created_at, expires_at) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?)"
    ).bind(pairingId, installId, deviceName, platform, tokenHash, code, now, expiresAt).run();
    return response({ pairingId, code, expiresAt, pollAfterSeconds: 3 }, 201);
  }

  if (request.method === "GET" && path === "/v1/pair/status") {
    const pairingId = url.searchParams.get("pairingId") || "";
    const token = bearer(request);
    if (!pairingId || !token) return response({ error: "missing_pairing_credentials" }, 400);
    const tokenHash = await sha256(token);
    const row = await env.DB.prepare(
      "SELECT status, expires_at FROM devices WHERE device_id = ? AND token_hash = ? LIMIT 1"
    ).bind(pairingId, tokenHash).first<{ status: string; expires_at: number }>();
    if (!row) return response({ error: "pairing_not_found" }, 404);
    if (row.status === "pending" && row.expires_at <= Date.now()) {
      await env.DB.prepare("UPDATE devices SET status = 'expired', pairing_code = NULL WHERE device_id = ?").bind(pairingId).run();
      return response({ status: "expired" });
    }
    return response({ status: row.status });
  }

  if (request.method === "POST" && path === "/v1/admin/pair/approve") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: any;
    try { body = await readJson(request, 8_192); } catch { return response({ error: "invalid_json" }, 400); }
    const code = typeof body.code === "string" ? body.code : "";
    if (!/^\d{6}$/.test(code)) return response({ error: "invalid_code" }, 400);
    const row = await env.DB.prepare(
      "SELECT device_id, device_name, platform FROM devices WHERE pairing_code = ? AND status = 'pending' AND expires_at > ? LIMIT 1"
    ).bind(code, Date.now()).first<{ device_id: string; device_name: string; platform: string }>();
    if (!row) return response({ error: "code_not_found_or_expired" }, 404);
    await env.DB.prepare(
      "UPDATE devices SET status = 'approved', pairing_code = NULL, approved_at = ? WHERE device_id = ? AND status = 'pending'"
    ).bind(Date.now(), row.device_id).run();
    return response({ approved: true, deviceId: row.device_id, deviceName: row.device_name, platform: row.platform });
  }

  if (request.method === "GET" && path === "/v1/config") {
    const device = await requireDevice(request, env);
    if (!device) return response({ error: "device_not_authorized" }, 403);
    const row = await env.DB.prepare(
      "SELECT version, update_id, payload, checksum, published_at FROM config WHERE config_id = 1"
    ).first<{ version: number; update_id: string; payload: string; checksum: string; published_at: number }>();
    await env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE device_id = ?").bind(Date.now(), device.id).run();
    if (!row) return response({ error: "no_configuration_published" }, 404);
    const sinceVersion = Number(url.searchParams.get("sinceVersion") || "0");
    const metadata = {
      schemaVersion: 1,
      version: row.version,
      updateId: row.update_id,
      checksum: row.checksum,
      publishedAt: row.published_at,
    };
    // Il Player controlla ad ogni avvio, ma scarica la lista solo se la versione è cambiata.
    if (Number.isSafeInteger(sinceVersion) && sinceVersion >= row.version) {
      return response({ ...metadata, unchanged: true });
    }
    return response({ ...metadata, unchanged: false, config: JSON.parse(row.payload) });
  }

  if (request.method === "POST" && path === "/v1/admin/config/publish") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: PublishedConfig;
    try { body = await readJson(request); } catch (e) {
      return response({ error: String(e) === "Error: PAYLOAD_TOO_LARGE" ? "payload_too_large" : "invalid_json" }, 400);
    }
    if (!body || body.schemaVersion !== 1 || !Array.isArray(body.channels) || body.channels.length > 1000 ||
        typeof body.updateId !== "string" || body.updateId.length < 8 || body.updateId.length > 100 ||
        !body.channels.every(validChannel)) {
      return response({ error: "invalid_configuration" }, 400);
    }
    const payload = JSON.stringify({ schemaVersion: 1, updateId: body.updateId, channels: body.channels });
    const checksum = await sha256(payload);
    const current = await env.DB.prepare("SELECT version FROM config WHERE config_id = 1").first<{ version: number }>();
    const version = (current?.version || 0) + 1;
    const publishedAt = Date.now();
    await env.DB.prepare(
      "INSERT INTO config (config_id, version, update_id, payload, checksum, published_at) VALUES (1, ?, ?, ?, ?, ?) ON CONFLICT(config_id) DO UPDATE SET version = excluded.version, update_id = excluded.update_id, payload = excluded.payload, checksum = excluded.checksum, published_at = excluded.published_at"
    ).bind(version, body.updateId, payload, checksum, publishedAt).run();
    return response({ published: true, version, updateId: body.updateId, checksum, publishedAt, channelCount: body.channels.length });
  }

  if (request.method === "GET" && path === "/v1/admin/devices") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    const result = await env.DB.prepare(
      "SELECT device_id AS deviceId, device_name AS deviceName, platform, status, created_at AS createdAt, approved_at AS approvedAt, last_seen_at AS lastSeenAt FROM devices ORDER BY created_at DESC LIMIT 200"
    ).all();
    return response({ devices: result.results || [] });
  }

  if (request.method === "POST" && path === "/v1/admin/devices/revoke") {
    if (!(await requireAdmin(request, env))) return response({ error: "unauthorized" }, 401);
    let body: any;
    try { body = await readJson(request, 8_192); } catch { return response({ error: "invalid_json" }, 400); }
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : "";
    if (!deviceId) return response({ error: "invalid_device_id" }, 400);
    const result = await env.DB.prepare(
      "UPDATE devices SET status = 'revoked', pairing_code = NULL WHERE device_id = ? AND status = 'approved'"
    ).bind(deviceId).run();
    if (!result.meta.changes) return response({ error: "device_not_found" }, 404);
    return response({ revoked: true, deviceId });
  }

  return response({ error: "not_found" }, 404);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return withCors(request, await route(request, env));
    } catch (error) {
      console.error("Sky Cloud Sync request failed:", error instanceof Error ? error.message : "unknown");
      return withCors(request, response({ error: "internal_error" }, 500));
    }
  },
};
