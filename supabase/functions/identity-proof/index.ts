// The user JWT is verified by Supabase Auth. The service credential stays here.
const url = Deno.env.get("SUPABASE_URL");
const anon = Deno.env.get("SUPABASE_ANON_KEY");
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

function decode(value: string, size: number): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length !== Math.ceil(size * 8 / 6)) {
    throw new Error("Invalid encoding");
  }
  const binary = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  const canonical = btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  if (binary.length !== size || canonical !== value) throw new Error("Invalid encoding");
  return Uint8Array.from(binary, (byte) => byte.charCodeAt(0));
}

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
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
    const user = await fetch(`${url}/auth/v1/user`, {
      headers: { apikey: anon, Authorization: authorization },
    });
    if (!user.ok) return json({ error: "sign_in_required" }, 401);
    const { id, email_confirmed_at } = await user.json();
    if (typeof id !== "string" || !email_confirmed_at) return json({ error: "sign_in_required" }, 401);

    const { operation, key, nonce, signature } = await request.json();
    if (typeof key !== "string") return json({ error: "invalid_key" }, 400);
    let publicKey: CryptoKey;
    try {
      publicKey = await crypto.subtle.importKey(
        "spki", decode(key, 91), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"],
      );
    } catch {
      return json({ error: "invalid_key" }, 400);
    }
    if (operation === "challenge") {
      const reply = await rpc("tharmesh_identity_challenge", { p_account: id, p_key: key });
      return reply.ok ? json({ nonce: await reply.json() }) : json({ error: "challenge_unavailable" }, 409);
    }
    if (operation !== "finish" || typeof nonce !== "string" ||
        !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(nonce) ||
        typeof signature !== "string") {
      return json({ error: "invalid_request" }, 400);
    }
    const message = new TextEncoder().encode(`tharmesh:identity:register:v1\n${id}\n${nonce}\n${key}`);
    let verified = false;
    try {
      verified = await crypto.subtle.verify(
        { name: "ECDSA", hash: "SHA-256" }, publicKey, decode(signature, 64), message,
      );
    } catch {
      return json({ error: "invalid_proof" }, 400);
    }
    if (!verified) return json({ error: "invalid_proof" }, 403);
    const reply = await rpc("tharmesh_identity_finish", { p_account: id, p_nonce: nonce, p_key: key });
    return reply.ok ? json(await reply.json()) : json({ error: "registration_unavailable" }, 409);
  } catch {
    return json({ error: "request_failed" }, 400);
  }
});
