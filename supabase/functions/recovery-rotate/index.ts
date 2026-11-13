// Recovery upload with server-enforced fresh auth and generation compare-and-swap.
const url = Deno.env.get("SUPABASE_URL");
const anon = Deno.env.get("SUPABASE_ANON_KEY");
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
const MAX_AGE_SEC = 900;

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

type AmrEntry = { method?: string; timestamp?: number };

function jwtPayload(authHeader: string): { sub?: string; iat?: number; amr?: AmrEntry[] } | null {
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  const parts = token.split(".");
  if (parts.length < 2) return null;
  try {
    return JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/")));
  } catch {
    return null;
  }
}

function authEventTimestamp(payload: { amr?: AmrEntry[] }): number | null {
  const amr = payload.amr;
  if (!Array.isArray(amr) || amr.length === 0) return null;
  let max = 0;
  for (const entry of amr) {
    const ts = entry?.timestamp;
    if (typeof ts !== "number" || ts < 1) continue;
    const method = entry?.method ?? "";
    if (method === "refresh" || method === "token_refresh") continue;
    if (ts > max) max = ts;
  }
  return max > 0 ? max : null;
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
    if (!payload || payload.sub !== id) return json({ error: "account_mismatch" }, 403);
    const tokenIat = payload.iat;
    const amrTs = authEventTimestamp(payload);
    if (typeof tokenIat !== "number" || amrTs === null) return json({ error: "invalid_session" }, 403);

    const now = Math.floor(Date.now() / 1000);
    const authAge = now - amrTs;
    if (authAge < 0 || authAge > MAX_AGE_SEC) return json({ error: "stale_auth_event" }, 403);
    if (tokenIat < amrTs) return json({ error: "invalid_session" }, 403);
    if (tokenIat - amrTs > 120) return json({ error: "refresh_without_reauth" }, 403);

    const freshProbe = await fetch(`${url}/rest/v1/rpc/tharmesh_auth_fresh_required`, {
      method: "POST",
      headers: {
        apikey: anon,
        Authorization: authorization,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ p_max_age_seconds: MAX_AGE_SEC }),
    });
    const freshText = await freshProbe.text();
    if (freshText.trim() !== "true") {
      return json({ error: "fresh_auth_required" }, 403);
    }

    const body = await request.json().catch(() => ({}));
    const expected = body?.expected_generation;
    const kdf = body?.kdf;
    const n = body?.n;
    const r = body?.r ?? 8;
    const p = body?.p ?? 1;
    const salt = body?.salt_b64;
    const iv = body?.iv_b64;
    const ciphertext = body?.ciphertext_b64;
    if (typeof expected !== "number" || expected < 0) return json({ error: "bad_generation" }, 400);
    if (kdf !== "scrypt" || typeof n !== "number" || n < 16384) return json({ error: "bad_blob" }, 400);
    if (typeof salt !== "string" || typeof iv !== "string" || typeof ciphertext !== "string") {
      return json({ error: "bad_blob" }, 400);
    }

    const reply = await rpc("tharmesh_recovery_put_svc", {
      p_account: id,
      p_expected_generation: expected,
      p_kdf: kdf,
      p_n: n,
      p_r: r,
      p_p: p,
      p_salt_b64: salt,
      p_iv_b64: iv,
      p_ciphertext_b64: ciphertext,
    });
    if (!reply.ok) {
      const err = await reply.text();
      if (err.includes("23505") || err.includes("generation conflict")) {
        return json({ error: "generation_conflict" }, 409);
      }
      return json({ error: "upload_failed" }, 502);
    }
    const nextGen = await reply.json();
    return json({ ok: true, generation: Array.isArray(nextGen) ? nextGen[0] : nextGen });
  } catch {
    return json({ error: "request_failed" }, 400);
  }
});
