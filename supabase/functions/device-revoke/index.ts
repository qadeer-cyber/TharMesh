// Device revoke: Web Crypto possession proof, then service-role tharmesh_device_revoke_svc only.
const url = Deno.env.get("SUPABASE_URL");
const anon = Deno.env.get("SUPABASE_ANON_KEY");
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
const AUTH_MAX_AGE_SEC = 900;
const PROOF_MAX_SKEW_SEC = 120;

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

import { authEventTimestamp, jwtPayload } from "../_shared/amr.ts";
import { sessionId } from "../_shared/session.ts";
import { deviceRevokeMessage, verifyDerSpki } from "../_shared/p256.ts";

async function rpc(name: string, payload: object): Promise<Response> {
  return await fetch(`${url}/rest/v1/rpc/${name}`, {
    method: "POST",
    headers: {
      apikey: service!,
      Authorization: `Bearer ${service}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!url || !anon || !service) return json({ error: "unavailable" }, 503);
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return json({ error: "sign_in_required" }, 401);
  try {
    const body = await request.json().catch(() => ({}));
    const deviceId = body?.device_id;
    const grantId = body?.grant_id;
    const callerPub = body?.caller_device_pubkey;
    const proofSig = body?.proof_sig_b64;
    const proofTs = body?.proof_ts;
    if (typeof deviceId !== "string" || !deviceId.trim()) return json({ error: "bad_device" }, 400);
    if (typeof grantId !== "string" || grantId.length < 8) return json({ error: "grant_required" }, 403);
    if (typeof callerPub !== "string" || callerPub.length < 40) return json({ error: "device_proof_required" }, 400);
    if (typeof proofSig !== "string" || proofSig.length < 40) return json({ error: "proof_sig_required" }, 400);
    if (typeof proofTs !== "number" || !Number.isFinite(proofTs) || proofTs < 1) {
      return json({ error: "proof_ts_required" }, 400);
    }

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
    if (now - amrTs > AUTH_MAX_AGE_SEC) return json({ error: "stale_auth_event" }, 403);
    if (Math.abs(now - Math.floor(proofTs)) > PROOF_MAX_SKEW_SEC) {
      return json({ error: "proof_ts_stale" }, 403);
    }

    const msg = deviceRevokeMessage(id, deviceId, grantId, callerPub, Math.floor(proofTs));
    const ok = await verifyDerSpki(callerPub, msg, proofSig);
    if (!ok) return json({ error: "invalid_proof_sig" }, 403);

    const reply = await rpc("tharmesh_device_revoke_svc", {
      p_account: id,
      p_device_id: deviceId.trim(),
      p_grant_id: grantId,
      p_session_id: sid,
      p_caller_device_pubkey: callerPub,
      p_proof_ts: Math.floor(proofTs),
    });
    if (!reply.ok) {
      const err = await reply.text();
      if (err.includes("Grant")) return json({ error: "grant_invalid" }, 403);
      if (err.includes("not active")) return json({ error: "caller_device_inactive" }, 403);
      return json({ error: "revoke_denied", detail: err.slice(0, 160) }, 403);
    }
    return json({ ok: true });
  } catch {
    return json({ error: "request_failed" }, 400);
  }
});
