// Records server-enforced fresh auth from JWT session iat (no client prefs).
const url = Deno.env.get("SUPABASE_URL");
const anon = Deno.env.get("SUPABASE_ANON_KEY");
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
const MAX_AGE_SEC = 900;

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

function jwtPayload(authHeader: string): { sub?: string; iat?: number } | null {
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  const parts = token.split(".");
  if (parts.length < 2) return null;
  try {
    return JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/")));
  } catch {
    return null;
  }
}

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
    const iat = payload?.iat;
    if (typeof iat !== "number") return json({ error: "invalid_session" }, 403);
    const age = Math.floor(Date.now() / 1000) - iat;
    if (age < 0 || age > MAX_AGE_SEC) return json({ error: "stale_session" }, 403);
    if (payload?.sub !== id) return json({ error: "account_mismatch" }, 403);

    const body = await request.json().catch(() => ({}));
    if (body?.operation === "check_only") {
      return json({ fresh: true, max_age_sec: MAX_AGE_SEC });
    }

    const reply = await rpc("tharmesh_auth_fresh_record_svc", {
      p_account: id,
      p_session_iat: iat,
    });
    return reply.ok ? json({ fresh: true }) : json({ error: "record_unavailable" }, 409);
  } catch {
    return json({ error: "request_failed" }, 400);
  }
});
