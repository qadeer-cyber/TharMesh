import assert from 'node:assert/strict';
import test from 'node:test';

function b64urlJson(obj) {
  return Buffer.from(JSON.stringify(obj)).toString('base64url');
}

function bearer(payload) {
  return `Bearer hdr.${b64urlJson(payload)}.sig`;
}

test('sensitive-auth rejects refresh-only JWT iat without fresh amr', async () => {
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
  const now = Math.floor(Date.now() / 1000);
  let recorded = 0;
  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/v1/user')) {
      return Response.json({ id: account, email_confirmed_at: '2026-11-12' });
    }
    if (url.includes('auth_fresh_record_svc')) {
      recorded++;
      return new Response(null, { status: 204 });
    }
    return new Response(null, { status: 404 });
  };
  await import('../functions/sensitive-auth/index.ts');
  const submit = (token) =>
    handle(new Request('https://x', {
      method: 'POST',
      headers: { Authorization: token, 'Content-Type': 'application/json' },
      body: '{}',
    }));

  const staleAmr = bearer({
    sub: account,
    iat: now,
    amr: [{ method: 'password', timestamp: now - 3600 }],
  });
  assert.equal((await submit(staleAmr)).status, 403);

  const refreshBypass = bearer({
    sub: account,
    iat: now,
    amr: [{ method: 'password', timestamp: now - 400 }, { method: 'refresh', timestamp: now - 10 }],
  });
  assert.equal((await submit(refreshBypass)).status, 403);
  assert.equal(recorded, 0);

  const fresh = bearer({
    sub: account,
    iat: now,
    amr: [{ method: 'otp', timestamp: now - 30 }],
  });
  assert.equal((await submit(fresh)).status, 200);
  assert.equal(recorded, 1);
});
