/**
 * real crypto — bootstrap dual-proof gate
 */
import assert from 'node:assert/strict';
import { createPrivateKey, createPublicKey, createSign, generateKeyPairSync, randomBytes } from 'node:crypto';
import test from 'node:test';

function b64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function signDer(privKey, msg) {
  const s = createSign('SHA256');
  s.update(msg);
  s.end();
  return b64url(s.sign({ key: privKey, dsaEncoding: 'der' }));
}

function bootstrapMessage(account, challenge, mesh, deviceId, devicePub) {
  return Buffer.from(
    `tharmesh:device:bootstrap:v1\n${account}\n${challenge}\n${mesh}\n${deviceId}\n${devicePub}`,
    'utf8',
  );
}

function meshKeyMaterial() {
  const pair = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const meshB64 = b64url(pair.publicKey.export({ type: 'spki', format: 'der' }));
  const pkcs8 = pair.privateKey.export({ type: 'pkcs8', format: 'der' });
  return { meshB64, privKey: createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' }) };
}

function signMeshRaw(privKey, msg) {
  const s = createSign('SHA256');
  s.update(msg);
  s.end();
  return b64url(s.sign({ key: privKey, dsaEncoding: 'ieee-p1363' }));
}

test('bootstrap_commit requires mesh and device proofs', async () => {
  let handle;
  globalThis.Deno = {
    env: new Map([
      ['SUPABASE_URL', 'https://test.invalid'],
      ['SUPABASE_ANON_KEY', 'test-anon'],
      ['SUPABASE_SERVICE_ROLE_KEY', 'test-service'],
    ]),
    serve: (h) => { handle = h; },
  };
  const account = 'f1000000-0000-4000-8000-0000000000a1';
  const challenge = 'e1000000-0000-4000-8000-000000000099';
  const mesh = meshKeyMaterial();
  const device = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const devPub = b64url(device.publicKey.export({ type: 'spki', format: 'der' }));
  const devPriv = createPrivateKey({ key: device.privateKey.export({ type: 'pkcs8', format: 'der' }), format: 'der', type: 'pkcs8' });
  let commits = 0;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/v1/user')) {
      return Response.json({ id: account, email_confirmed_at: '2026-11-12T00:00:00Z' });
    }
    if (url.includes('bootstrap_challenge_svc')) return Response.json(challenge);
    if (url.includes('bootstrap_device_svc')) {
      commits++;
      return new Response(null, { status: 204 });
    }
    return new Response(null, { status: 404 });
  };
  await import('../functions/device-enrollment/index.ts');
  const msg = bootstrapMessage(account, challenge, mesh.meshB64, 'dev-1', devPub);
  const submit = (body) =>
    handle(new Request('https://x', {
      method: 'POST',
      headers: { Authorization: 'Bearer user-token' },
      body: JSON.stringify(body),
    }));

  assert.equal((await submit({
    operation: 'bootstrap_commit',
    device_id: 'dev-1',
    device_pubkey: devPub,
    mesh_public_key: mesh.meshB64,
    challenge,
    mesh_sig_b64: 'bad',
    device_sig_b64: 'bad',
  })).status, 403);

  assert.equal(commits, 0);
  assert.equal(
    (await submit({
      operation: 'bootstrap_commit',
      device_id: 'dev-1',
      device_pubkey: devPub,
      mesh_public_key: mesh.meshB64,
      challenge,
      mesh_sig_b64: signMeshRaw(mesh.privKey, msg),
      device_sig_b64: signDer(devPriv, msg),
    })).status,
    200,
  );
  assert.equal(commits, 1);
});
