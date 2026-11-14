/**
 * device-revoke edge: DB pubkey lookup; challenge-bound signatures.
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

function challengeMsg(account, sessionId, reqId, targetId, grantId, challengeId) {
  return canonical([
    'TM-DEVICE-REVOKE-CHALLENGE-v1',
    account,
    sessionId,
    reqId,
    targetId,
    grantId,
    challengeId,
  ]);
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
const sessionId = 'sess-revoke-cr1';
const grantId = 'a1000000-0000-4000-8000-0000000000g1';
const target = 'device-target';
const requester = 'device-requester';
const challengeId = 'c1000000-0000-4000-8000-0000000000h1';

function bearer(nowSec) {
  const pay = Buffer.from(
    JSON.stringify({
      sub: account,
      session_id: sessionId,
      iat: nowSec,
      amr: [{ method: 'otp', timestamp: nowSec - 30 }],
    })
  ).toString('base64url');
  return `Bearer e30.${pay}.sig`;
}

test('device-revoke edge rejects bad challenge proofs', async () => {
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
  const now = Math.floor(Date.now() / 1000);
  let svcCalls = 0;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/v1/user')) {
      return Response.json({ id: account, email_confirmed_at: '2026-11-12' });
    }
    if (url.includes('tharmesh_device_revoke_challenge_get_svc')) {
      return Response.json([
        {
          account_id: account,
          session_id: sessionId,
          requesting_device_id: requester,
          target_device_id: target,
          grant_id: grantId,
          expires_at: new Date(Date.now() + 120_000).toISOString(),
          consumed_at: null,
        },
      ]);
    }
    if (url.includes('tharmesh_device_pubkey_lookup_svc')) {
      return new Response(JSON.stringify(active.pubB64), { status: 200 });
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

  const commit = (sig, targetId = target) =>
    post({ operation: 'commit', challenge_id: challengeId, target_device_id: targetId, proof_sig_b64: sig });

  const forgedSig = signDer(other.privKey, challengeMsg(account, sessionId, requester, target, grantId, challengeId));
  assert.equal((await commit(forgedSig)).status, 403);

  const wrongTargetSig = signDer(
    active.privKey,
    challengeMsg(account, sessionId, requester, 'other-target', grantId, challengeId)
  );
  assert.equal((await commit(wrongTargetSig, target)).status, 403);

  const wrongSessionSig = signDer(
    active.privKey,
    challengeMsg(account, 'wrong-session-xx', requester, target, grantId, challengeId)
  );
  assert.equal((await commit(wrongSessionSig)).status, 403);

  const goodSig = signDer(
    active.privKey,
    challengeMsg(account, sessionId, requester, target, grantId, challengeId)
  );
  assert.equal((await commit(goodSig)).status, 200);
  assert.equal(svcCalls, 1);

  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/v1/user')) {
      return Response.json({ id: account, email_confirmed_at: '2026-11-12' });
    }
    if (url.includes('tharmesh_device_revoke_challenge_get_svc')) {
      return Response.json([
        {
          account_id: account,
          session_id: sessionId,
          requesting_device_id: requester,
          target_device_id: target,
          grant_id: grantId,
          expires_at: new Date(Date.now() - 1000).toISOString(),
          consumed_at: null,
        },
      ]);
    }
    if (url.includes('tharmesh_device_pubkey_lookup_svc')) {
      return new Response(JSON.stringify(active.pubB64), { status: 200 });
    }
    return new Response(null, { status: 404 });
  };
  assert.equal((await commit(goodSig)).status, 403);

  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/v1/user')) {
      return Response.json({ id: 'f1000000-0000-4000-8000-0000000000b2', email_confirmed_at: '2026-11-12' });
    }
    if (url.includes('tharmesh_device_revoke_challenge_get_svc')) {
      return Response.json([
        {
          account_id: account,
          session_id: sessionId,
          requesting_device_id: requester,
          target_device_id: target,
          grant_id: grantId,
          expires_at: new Date(Date.now() + 120_000).toISOString(),
          consumed_at: null,
        },
      ]);
    }
    return new Response(null, { status: 404 });
  };
  assert.equal((await commit(goodSig)).status, 403);
});
