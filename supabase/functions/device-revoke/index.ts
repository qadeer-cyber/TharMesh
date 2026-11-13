// Device revoke with session-bound device_revoke grant and active-device proof.
const url = Deno.env.get("SUPABASE_URL");
const anon = Deno.env.get("SUPABASE_ANON_KEY");
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
const MAX_AGE_SEC = 900;

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

import { authEventTimestamp, jwtPayload } from "../_shared/amr.ts";
import { sessionId } from "../_shared/session.ts";

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!url || !anon || !service) return json({ error: "unavailable" }, 503);
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return json({ error: "sign_in_required" }, 401);
  try {
    const body = await request.json().catch(() => ({}));
    const deviceId = body?.device_id;
    const grantId = body?.grant_id;
    const activePub = body?.active_device_pubkey;
    if (typeof deviceId !== "string" || !deviceId.trim()) return json({ error: "bad_device" }, 400);
    if (typeof grantId !== "string" || grantId.length < 8) return json({ error: "grant_required" }, 403);
    if (typeof activePub !== "string" || activePub.length < 40) return json({ error: "device_proof_required" }, 400);

    const userRes = await fetch(`${url}/auth/v1/user`, {
      headers: { apikey: anon, Authorization: authorization },
    });
    if (!userRes.ok) return json({ error: "sign_in_required" }, 401);
    const { id, email_confirmed_at } = await userRes.json();
    if (typeof id !== "string" || !email_confirmed_at) return json({ error: "sign_in_required" }, 401);

    const payload = jwtPayload(authorization);
    if (!payload || payload.sub !== id) return json({ error: "account_mismatch" }, 403);
    const amrTs = authEventTimestamp(payload);
    const sid = sessionId(payload);
    if (amrTs === null || sid === null) return json({ error: "invalid_session" }, 403);

    const now = Math.floor(Date.now() / 1000);
    if (now - amrTs > MAX_AGE_SEC) return json({ error: "stale_auth_event" }, 403);

    const reply = await fetch(`${url}/rest/v1/rpc/tharmesh_device_revoke`, {
      method: "POST",
      headers: {
        apikey: anon!,
        Authorization: authorization,
        "Content-Type": "application/json",
        "Prefer": "return=minimal",
      },
      body: JSON.stringify({
        p_device_id: deviceId.trim(),
        p_grant_id: grantId,
        p_active_device_pubkey: activePub,
      }),
    });
    if (!reply.ok) {
      const err = await reply.text();
      if (err.includes("Grant")) return json({ error: "grant_invalid" }, 403);
      return json({ error: "revoke_denied", detail: err.slice(0, 120) }, reply.status === 401 ? 401 : 403);
    }
    return json({ ok: true });
  } catch {
    return json({ error: "request_failed" }, 400);
  }
});
