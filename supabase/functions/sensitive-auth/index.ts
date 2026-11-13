// Server-enforced fresh auth from JWT amr method timestamps (not access-token iat refresh).
const url = Deno.env.get("SUPABASE_URL");
const anon = Deno.env.get("SUPABASE_ANON_KEY");
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
const MAX_AGE_SEC = 900;

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

import { authEventTimestamp, jwtPayload } from "../_shared/amr.ts";
import { sessionId } from "../_shared/session.ts";

const ALLOWED_ACTIONS = new Set(["recovery_rotate", "device_revoke"]);

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
    const userRes = await fetch(`${url}/auth/v1/user`, {
      headers: { apikey: anon, Authorization: authorization },
    });
    if (!userRes.ok) return json({ error: "sign_in_required" }, 401);
    const { id, email_confirmed_at } = await userRes.json();
    if (typeof id !== "string" || !email_confirmed_at) return json({ error: "sign_in_required" }, 401);

    const payload = jwtPayload(authorization);
    if (!payload || payload.sub !== id) return json({ error: "account_mismatch" }, 403);
    const tokenIat = payload.iat;
    const amrTs = authEventTimestamp(payload);
    if (typeof tokenIat !== "number" || amrTs === null) return json({ error: "invalid_session" }, 403);

    const now = Math.floor(Date.now() / 1000);
    const authAge = now - amrTs;
    if (authAge < 0 || authAge > MAX_AGE_SEC) return json({ error: "stale_auth_event" }, 403);
    if (tokenIat < amrTs) return json({ error: "invalid_session" }, 403);
    if (tokenIat - amrTs > 120) return json({ error: "refresh_without_reauth" }, 403);

    const body = await request.json().catch(() => ({}));
    const scope = typeof body?.scope === "string" ? body.scope : "sensitive";
    if (body?.operation === "check_only") {
      return json({ fresh: true, scope, max_age_sec: MAX_AGE_SEC, auth_amr_ts: amrTs });
    }

    const action = typeof body?.action === "string" ? body.action.trim() : "";
    const sid = sessionId(payload);
    if (sid === null) return json({ error: "invalid_session" }, 403);
    if (!ALLOWED_ACTIONS.has(action)) {
      const legacy = await rpc("tharmesh_auth_fresh_record_svc", {
        p_account: id,
        p_auth_amr_ts: amrTs,
        p_token_iat: tokenIat,
      });
      return legacy.ok ? json({ fresh: true, scope, session_bound: false }) : json({ error: "record_unavailable" }, 409);
    }
    const reply = await rpc("tharmesh_sensitive_grant_issue_svc", {
      p_account: id,
      p_action: action,
      p_session_id: sid,
      p_auth_amr_ts: amrTs,
      p_token_iat: tokenIat,
      p_ttl_seconds: MAX_AGE_SEC,
    });
    if (!reply.ok) {
      return json({ error: "record_unavailable" }, 409);
    }
    const grantId = await reply.json();
    const gid = Array.isArray(grantId) ? grantId[0] : grantId;
    return json({ fresh: true, scope, action, grant_id: gid });
  } catch {
    return json({ error: "request_failed" }, 400);
  }
});
