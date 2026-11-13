// Verified user JWT + Web Crypto P-256; writes via service-role RPCs only.
const url = Deno.env.get("SUPABASE_URL");
const anon = Deno.env.get("SUPABASE_ANON_KEY");
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");

function decodeB64Url(value: string, minBytes: number): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length < 4) throw new Error("Invalid encoding");
  const binary = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  if (binary.length < minBytes) throw new Error("Invalid encoding");
  return Uint8Array.from(binary, (byte) => byte.charCodeAt(0));
}

function canonical(parts: string[]): Uint8Array {
  return new TextEncoder().encode(parts.join("\x1F"));
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

async function importSpki(spkiB64: string): Promise<CryptoKey> {
  return await crypto.subtle.importKey(
    "spki",
    decodeB64Url(spkiB64, 80),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
}

function derToP1363(der: Uint8Array): Uint8Array | null {
  if (der.length < 8 || der[0] !== 0x30) return null;
  let cursor = 2;
  const out = new Uint8Array(64);
  for (const offset of [0, 32]) {
    if (der[cursor++] !== 0x02) return null;
    const len = der[cursor++];
    if (cursor + len > der.length) return null;
    let start = cursor;
    let pad = 0;
    if (len > 0 && der[start] === 0x00) {
      pad = 1;
    }
    const width = len - pad;
    if (width > 32) return null;
    out.set(der.subarray(start + pad, start + len), offset + 32 - width);
    cursor += len;
  }
  return cursor === der.length ? out : null;
}

async function verifyDer(spkiB64: string, message: Uint8Array, derSigB64: string): Promise<boolean> {
  const key = await importSpki(spkiB64);
  const der = decodeB64Url(derSigB64, 40);
  const raw = derToP1363(der);
  if (!raw) return false;
  try {
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, raw, message);
  } catch {
    return false;
  }
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

    const body = await request.json();
    const operation = body?.operation;
    if (operation === "begin") {
      const {
        device_id: deviceId,
        new_device_pubkey: newPub,
        nonce_b64: nonceB64,
        request_sig_b64: reqSig,
        mesh_public_key: meshKey,
        expires_at: expiresAt,
      } = body;
      if (
        typeof deviceId !== "string" || typeof newPub !== "string" || typeof nonceB64 !== "string" ||
        typeof reqSig !== "string" || typeof meshKey !== "string"
      ) {
        return json({ error: "invalid_request" }, 400);
      }
      const msg = canonical([
        "TM-ENROLL-v1",
        id,
        meshKey,
        newPub,
        deviceId,
        nonceB64,
        "0",
      ]);
      if (!(await verifyDer(newPub, msg, reqSig))) return json({ error: "invalid_proof" }, 403);
      const reply = await rpc("tharmesh_enrollment_begin_svc", {
        p_account: id,
        p_device_id: deviceId,
        p_new_device_pubkey: newPub,
        p_nonce_b64: nonceB64,
        p_request_sig_b64: reqSig,
        p_expires_at: expiresAt ?? null,
      });
      return reply.ok ? json({ ok: true }) : json({ error: "enrollment_unavailable" }, 409);
    }

    if (operation === "approve") {
      const {
        nonce_b64: nonceB64,
        trusted_device_id: trustedId,
        approval_sig_b64: apprSig,
        new_device_pubkey: newDevicePub,
        expires_epoch: expiresEpoch,
      } = body;
      if (
        typeof nonceB64 !== "string" || typeof trustedId !== "string" || typeof apprSig !== "string" ||
        typeof newDevicePub !== "string"
      ) {
        return json({ error: "invalid_request" }, 400);
      }
      const pubkeyRes = await rpc("tharmesh_trusted_device_pubkey_svc", {
        p_account: id,
        p_trusted_device_id: trustedId,
      });
      if (!pubkeyRes.ok) return json({ error: "trusted_device_unavailable" }, 403);
      const trustedPub = await pubkeyRes.json();
      if (typeof trustedPub !== "string") return json({ error: "trusted_device_unavailable" }, 403);
      const epoch = typeof expiresEpoch === "number" ? String(expiresEpoch) : String(body.expires_epoch);
      const msg = canonical(["TM-DEVICE-APPROVE-v1", id, newDevicePub, nonceB64, epoch]);
      if (!(await verifyDer(trustedPub, msg, apprSig))) {
        return json({ error: "invalid_proof" }, 403);
      }
      const reply = await rpc("tharmesh_enrollment_approve_svc", {
        p_account: id,
        p_nonce_b64: nonceB64,
        p_trusted_device_id: trustedId,
        p_approval_sig_b64: apprSig,
      });
      return reply.ok ? json({ ok: true }) : json({ error: "approval_unavailable" }, 409);
    }

    if (operation === "finish") {
      const { nonce_b64: nonceB64, certificate } = body;
      if (typeof nonceB64 !== "string" || typeof certificate !== "string") {
        return json({ error: "invalid_request" }, 400);
      }
      const reply = await rpc("tharmesh_enrollment_finish_svc", {
        p_account: id,
        p_nonce_b64: nonceB64,
        p_certificate: certificate,
      });
      return reply.ok ? json({ ok: true }) : json({ error: "finish_unavailable" }, 409);
    }

    if (operation === "bootstrap_device") {
      const { device_id: deviceId, device_pubkey: devicePub, certificate } = body;
      if (typeof deviceId !== "string" || typeof devicePub !== "string") {
        return json({ error: "invalid_request" }, 400);
      }
      const reply = await rpc("tharmesh_bootstrap_device_svc", {
        p_account: id,
        p_device_id: deviceId,
        p_device_pubkey: devicePub,
        p_certificate: certificate ?? null,
      });
      return reply.ok ? json({ ok: true }) : json({ error: "bootstrap_unavailable" }, 409);
    }

    return json({ error: "invalid_operation" }, 400);
  } catch {
    return json({ error: "request_failed" }, 400);
  }
});
