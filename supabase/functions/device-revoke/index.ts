// Device revoke: server challenge + DB lookup of signing pubkey; then tharmesh_device_revoke_svc.
const url = Deno.env.get("SUPABASE_URL");
const anon = Deno.env.get("SUPABASE_ANON_KEY");
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
const AUTH_MAX_AGE_SEC = 900;

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

import { authEventTimestamp, jwtPayload } from "../_shared/amr.ts";
import { sessionId } from "../_shared/session.ts";
import { deviceRevokeChallengeMessage, verifyDerSpki } from "../_shared/p256.ts";

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

type ChallengeRow = {
  account_id: string;
  session_id: string;
  requesting_device_id: string;
  target_device_id: string;
  grant_id: string;
  expires_at: string;
  consumed_at: string | null;
};

async function loadChallenge(challengeId: string): Promise<ChallengeRow | null> {
  const reply = await rpc("tharmesh_device_revoke_challenge_get_svc", { p_challenge_id: challengeId });
  if (!reply.ok) return null;
  const rows = await reply.json();
  const row = Array.isArray(rows) ? rows[0] : rows;
  if (!row?.account_id) return null;
  return row as ChallengeRow;
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!url || !anon || !service) return json({ error: "unavailable" }, 503);
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) return json({ error: "sign_in_required" }, 401);
  try {
    const body = await request.json().catch(() => ({}));
    const operation = typeof body?.operation === "string" ? body.operation.trim() : "commit";

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

    if (operation === "challenge") {
      const targetDeviceId = body?.target_device_id;
      const grantId = body?.grant_id;
      const requestingDeviceId = body?.requesting_device_id;
      if (typeof targetDeviceId !== "string" || !targetDeviceId.trim()) return json({ error: "bad_target" }, 400);
      if (typeof grantId !== "string" || grantId.length < 8) return json({ error: "grant_required" }, 403);
      if (typeof requestingDeviceId !== "string" || !requestingDeviceId.trim()) {
        return json({ error: "requesting_device_required" }, 400);
      }
      const reply = await rpc("tharmesh_device_revoke_challenge_issue_svc", {
        p_account: id,
        p_session_id: sid,
        p_requesting_device_id: requestingDeviceId.trim(),
        p_target_device_id: targetDeviceId.trim(),
        p_grant_id: grantId,
      });
      if (!reply.ok) {
        const err = await reply.text();
        return json({ error: "challenge_denied", detail: err.slice(0, 160) }, 403);
      }
      const challengeId = await reply.json();
      const cid = Array.isArray(challengeId) ? challengeId[0] : challengeId;
      return json({
        challenge_id: cid,
        bound: {
          account_id: id,
          session_id: sid,
          requesting_device_id: requestingDeviceId.trim(),
          target_device_id: targetDeviceId.trim(),
          grant_id: grantId,
        },
      });
    }

    const challengeId = body?.challenge_id;
    const proofSig = body?.proof_sig_b64;
  const targetDeviceId = body?.target_device_id;
    if (typeof challengeId !== "string" || challengeId.length < 8) return json({ error: "challenge_required" }, 400);
    if (typeof proofSig !== "string" || proofSig.length < 40) return json({ error: "proof_sig_required" }, 400);

    const ch = await loadChallenge(challengeId);
    if (!ch) return json({ error: "challenge_not_found" }, 403);
    if (ch.account_id !== id) return json({ error: "account_mismatch" }, 403);
    if (ch.session_id !== sid) return json({ error: "session_mismatch" }, 403);
    if (ch.consumed_at) return json({ error: "challenge_consumed" }, 403);
    if (new Date(ch.expires_at).getTime() <= Date.now()) return json({ error: "challenge_expired" }, 403);
    if (typeof targetDeviceId === "string" && targetDeviceId.trim() !== ch.target_device_id) {
      return json({ error: "target_mismatch" }, 403);
    }

    const pubReply = await rpc("tharmesh_device_pubkey_lookup_svc", {
      p_account: id,
      p_device_id: ch.requesting_device_id,
    });
    if (!pubReply.ok) return json({ error: "signing_device_unknown" }, 403);
    const pubRaw = await pubReply.json();
    const signingPub = Array.isArray(pubRaw) ? pubRaw[0] : pubRaw;
    if (typeof signingPub !== "string" || signingPub.length < 40) {
      return json({ error: "signing_device_unknown" }, 403);
    }

    const msg = deviceRevokeChallengeMessage(
      id,
      sid,
      ch.requesting_device_id,
      ch.target_device_id,
      ch.grant_id,
      challengeId,
    );
    const ok = await verifyDerSpki(signingPub, msg, proofSig);
    if (!ok) return json({ error: "invalid_proof_sig" }, 403);

    const reply = await rpc("tharmesh_device_revoke_svc", {
      p_account: id,
      p_challenge_id: challengeId,
    });
    if (!reply.ok) {
      const err = await reply.text();
      if (err.includes("not active")) return json({ error: "signing_device_inactive" }, 403);
      if (err.includes("Challenge")) return json({ error: "challenge_invalid" }, 403);
      if (err.includes("Grant")) return json({ error: "grant_invalid" }, 403);
      return json({ error: "revoke_denied", detail: err.slice(0, 160) }, 403);
    }
    return json({ ok: true });
  } catch {
    return json({ error: "request_failed" }, 400);
  }
});
