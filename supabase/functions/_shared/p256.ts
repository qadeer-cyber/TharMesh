export function decodeB64Url(value: string, minBytes: number): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length < 4) throw new Error("Invalid encoding");
  const binary = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
  if (binary.length < minBytes) throw new Error("Invalid encoding");
  return Uint8Array.from(binary, (byte) => byte.charCodeAt(0));
}

export function canonical(parts: string[]): Uint8Array {
  return new TextEncoder().encode(parts.join("\x1F"));
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
    if (len > 0 && der[start] === 0x00) pad = 1;
    const width = len - pad;
    if (width > 32) return null;
    out.set(der.subarray(start + pad, start + len), offset + 32 - width);
    cursor += len;
  }
  return cursor === der.length ? out : null;
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

export async function verifyDerSpki(spkiB64: string, message: Uint8Array, derSigB64: string): Promise<boolean> {
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

export function deviceRevokeMessage(
  account: string,
  targetDeviceId: string,
  grantId: string,
  callerDevicePub: string,
  proofTsSec: number,
): Uint8Array {
  return canonical([
    "TM-DEVICE-REVOKE-v1",
    account,
    targetDeviceId.trim(),
    grantId,
    callerDevicePub,
    String(proofTsSec),
  ]);
}
