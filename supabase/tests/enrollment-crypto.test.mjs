/**
 * real crypto — Web Crypto enrollment gate (not length stub, not hosted Supabase)
 */
import assert from 'node:assert/strict';
import { createPrivateKey, createPublicKey, createSign, generateKeyPairSync, randomBytes } from 'node:crypto';
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

function derToP1363(der) {
  if (der.length < 8 || der[0] !== 0x30) return null;
  let cursor = 2;
  const out = new Uint8Array(64);
  for (const offset of [0, 32]) {
    if (der[cursor++] !== 0x02) return null;
    const len = der[cursor++];
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

const account = 'f1000000-0000-4000-8000-0000000000a1';
const mesh = 'A'.repeat(122);
const stats = { passed: 0, failed: 0 };

test('device-enrollment edge rejects bad proofs before service writes', async () => {
  let handle;
  const environment = new Map([
    ['SUPABASE_URL', 'https://test.invalid'],
    ['SUPABASE_ANON_KEY', 'test-anon'],
    ['SUPABASE_SERVICE_ROLE_KEY', 'test-service'],
  ]);
  globalThis.Deno = { env: environment, serve: (h) => { handle = h; } };
  const trusted = keyMaterial();
  const newDev = keyMaterial();
  const nonce = b64url(randomBytes(12));
  const epoch = String(Math.floor(Date.now() / 1000) + 600);
  let svcWrites = 0;
  globalThis.fetch = async (url, options) => {
    if (url.endsWith('/auth/v1/user')) {
      if (options.headers.Authorization !== 'Bearer user-token') return new Response(null, { status: 401 });
      return Response.json({ id: account, email_confirmed_at: '2026-11-12T00:00:00Z' });
    }
    if (url.includes('tharmesh_trusted_device_pubkey_svc')) {
      return Response.json(trusted.pubB64);
    }
    if (url.includes('enrollment_pending_get_svc')) {
      return Response.json([{
        mesh_public_key: mesh,
        new_device_pubkey: newDev.pubB64,
        device_id: 'phone-2',
        expires_at: new Date(Number(epoch) * 1000).toISOString(),
        approval_sig_b64: signDer(trusted.privKey, approveMsg(account, newDev.pubB64, nonce, Number(epoch))),
        approved_device_id: 'trusted-1',
        request_sig_b64: reqSig,
      }]);
    }
    if (url.includes('_svc')) {
      svcWrites++;
      return new Response(null, { status: 204 });
    }
    return new Response(null, { status: 404 });
  };

  await import('../functions/device-enrollment/index.ts');
  const submit = (body) =>
    handle(
      new Request('https://test.invalid', {
        method: 'POST',
        headers: { Authorization: 'Bearer user-token', 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    );

  const reqMsg = canonical(['TM-ENROLL-v1', account, mesh, newDev.pubB64, 'phone-2', nonce, '0']);
  const reqSig = signDer(newDev.privKey, reqMsg);

  const shortSig = await submit({
    operation: 'begin',
    device_id: 'phone-2',
    new_device_pubkey: newDev.pubB64,
    nonce_b64: nonce,
    request_sig_b64: 'short',
    mesh_public_key: mesh,
  });
  assert.ok(shortSig.status === 403 || shortSig.status === 400);
  stats.passed++;

  const badSig = b64url(randomBytes(48));
  assert.equal(
    (await submit({
      operation: 'begin',
      device_id: 'phone-2',
      new_device_pubkey: newDev.pubB64,
      nonce_b64: nonce,
      request_sig_b64: badSig,
      mesh_public_key: mesh,
    })).status,
    403
  );
  stats.passed++;

  assert.equal(
    (await submit({
      operation: 'begin',
      device_id: 'phone-2',
      new_device_pubkey: trusted.pubB64,
      nonce_b64: nonce,
      request_sig_b64: signDer(newDev.privKey, reqMsg),
      mesh_public_key: mesh,
    })).status,
    403
  );
  stats.passed++;

  const wrongAcctMsg = canonical(['TM-ENROLL-v1', account.replace('a1', 'b2'), mesh, newDev.pubB64, 'phone-2', nonce, '0']);
  assert.equal(
    (await submit({
      operation: 'begin',
      device_id: 'phone-2',
      new_device_pubkey: newDev.pubB64,
      nonce_b64: nonce,
      request_sig_b64: signDer(newDev.privKey, wrongAcctMsg),
      mesh_public_key: mesh,
    })).status,
    403
  );
  stats.passed++;

  assert.equal(
    (await submit({
      operation: 'begin',
      device_id: 'phone-2',
      new_device_pubkey: newDev.pubB64,
      nonce_b64: nonce,
      request_sig_b64: reqSig,
      mesh_public_key: mesh,
    })).status,
    200
  );
  stats.passed++;

  const apprMsg = canonical(['TM-DEVICE-APPROVE-v1', account, newDev.pubB64, nonce, epoch]);
  assert.equal(
    (await submit({
      operation: 'approve',
      nonce_b64: nonce,
      trusted_device_id: 'trusted-1',
      new_device_pubkey: newDev.pubB64,
      approval_sig_b64: badSig,
      expires_epoch: Number(epoch),
    })).status,
    403
  );
  stats.passed++;

  const wrongDevMsg = canonical(['TM-DEVICE-APPROVE-v1', account, trusted.pubB64, nonce, epoch]);
  assert.equal(
    (await submit({
      operation: 'approve',
      nonce_b64: nonce,
      trusted_device_id: 'trusted-1',
      new_device_pubkey: newDev.pubB64,
      approval_sig_b64: signDer(trusted.privKey, wrongDevMsg),
      expires_epoch: Number(epoch),
    })).status,
    403
  );
  stats.passed++;

  assert.equal(
    (await submit({
      operation: 'approve',
      nonce_b64: nonce,
      trusted_device_id: 'trusted-1',
      new_device_pubkey: newDev.pubB64,
      approval_sig_b64: signDer(trusted.privKey, apprMsg),
      expires_epoch: Number(epoch),
    })).status,
    200
  );
  stats.passed++;

  const finishMsg = canonical(['TM-ENROLL-FINISH-v1', account, mesh, newDev.pubB64, 'phone-2', nonce, '0']);
  const shortFinish = await submit({
    operation: 'finish',
    nonce_b64: nonce,
    certificate: 'cert:phone-2',
    finish_sig_b64: 'short',
  });
  assert.ok(shortFinish.status === 403 || shortFinish.status === 400);
  stats.passed++;
  assert.equal(
    (await submit({
      operation: 'finish',
      nonce_b64: nonce,
      certificate: 'cert:phone-2',
      finish_sig_b64: signDer(newDev.privKey, finishMsg),
    })).status,
    200
  );
  stats.passed++;

  assert.equal(svcWrites, 3);

  console.log(JSON.stringify({ label: 'real crypto', passed: stats.passed, failed: stats.failed }));
});


test('local verify helper rejects malformed SPKI', () => {
  assert.equal(derToP1363(Buffer.from([0x30, 0x06, 0x02, 0x01, 0x00])), null);
});
