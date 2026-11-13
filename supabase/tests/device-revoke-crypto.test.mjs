/**
 * device-revoke edge: possession proof required (Web Crypto), not pubkey-only.
 */
import assert from 'node:assert/strict';
import { createPrivateKey, createSign, generateKeyPairSync } from 'node:crypto';
import test from 'node:test';

function b64url(buf) {
  return Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function canonical(parts) {
  return Buffer.from(parts.join('\x1F'), 'utf8');
}

function revokeMsg(account, target, grantId, callerPub, ts) {
  return canonical(['TM-DEVICE-REVOKE-v1', account, target, grantId, callerPub, String(ts)]);
}

function keyMaterial() {
  const pair = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const spki = pair.publicKey.export({ type: 'spki', format: 'der' });
  const pkcs8 = pair.privateKey.export({ type: 'pkcs8', format: 'der' });
  return { pubB64: b64url(spki), privKey: createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' }) };
}

function signDer(privKey, msg) {
  const s = createSign('SHA256');
  s.update(msg);
  s.end();
  return b64url(s.sign({ key: privKey, dsaEncoding: 'der' }));
}

const account = 'f1000000-0000-4000-8000-0000000000a1';
const grantId = 'a1000000-0000-4000-8000-0000000000g1';
const target = 'device-target';

function bearer(nowSec) {
  const hdr = Buffer.from('{}').toString('base64url');
  const pay = Buffer.from(
    JSON.stringify({
      sub: account,
      session_id: 'sess-revoke-cr1',
      iat: nowSec,
      amr: [{ method: 'otp', timestamp: nowSec - 30 }],
    })
  ).toString('base64url');
  return `Bearer ${hdr}.${pay}.sig`;
}

test('device-revoke edge rejects bad possession proofs', async () => {
  let handle;
  globalThis.Deno = {
    env: new Map([
      ['SUPABASE_URL', 'https://test.invalid'],
      ['SUPABASE_ANON_KEY', 'test-anon'],
      ['SUPABASE_SERVICE_ROLE_KEY', 'test-service'],
    ]),
    serve: (h) => {
      handle = h;
    },
  };
  const active = keyMaterial();
  const other = keyMaterial();
  const revoked = keyMaterial();
  const now = Math.floor(Date.now() / 1000);
  let svcCalls = 0;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/v1/user')) {
      return Response.json({ id: account, email_confirmed_at: '2026-11-12' });
    }
    if (url.includes('tharmesh_device_revoke_svc')) {
      svcCalls++;
      return new Response(null, { status: 204 });
    }
    return new Response(null, { status: 404 });
  };
  await import('../functions/device-revoke/index.ts');
  const post = (body) =>
    handle(
      new Request('https://x', {
        method: 'POST',
        headers: { Authorization: bearer(now), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    );

  const baseBody = {
    device_id: target,
    grant_id: grantId,
    caller_device_pubkey: active.pubB64,
    proof_ts: now,
  };

  const malformedSig = b64url(Buffer.alloc(72, 0xaa));
  assert.equal((await post({ ...baseBody, proof_sig_b64: malformedSig })).status, 403);

  const forgedSig = signDer(other.privKey, revokeMsg(account, target, grantId, active.pubB64, now));
  assert.equal((await post({ ...baseBody, proof_sig_b64: forgedSig })).status, 403);

  const wrongTargetSig = signDer(active.privKey, revokeMsg(account, 'other-device', grantId, active.pubB64, now));
  assert.equal((await post({ ...baseBody, proof_sig_b64: wrongTargetSig })).status, 403);

  const goodSig = signDer(active.privKey, revokeMsg(account, target, grantId, active.pubB64, now));
  assert.equal((await post({ ...baseBody, proof_sig_b64: goodSig })).status, 200);
  assert.equal(svcCalls, 1);

  assert.equal((await post({ ...baseBody, proof_sig_b64: goodSig })).status, 200);
  assert.equal(svcCalls, 2);

  const stale = { ...baseBody, proof_ts: now - 600, proof_sig_b64: signDer(active.privKey, revokeMsg(account, target, grantId, active.pubB64, now - 600)) };
  assert.equal((await post(stale)).status, 403);
  void revoked;
});
