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
  let grantIssue = 0;
  let lastGrantBody = null;
  globalThis.fetch = async (url, init) => {
    if (url.endsWith('/auth/v1/user')) {
      return Response.json({ id: account, email_confirmed_at: '2026-11-12' });
    }
  const body = init?.body ? JSON.parse(init.body) : {};
    if (url.includes('sensitive_grant_issue_svc')) {
      grantIssue++;
      lastGrantBody = body;
      return new Response(JSON.stringify('grant-action'), { status: 200 });
    }
    if (url.includes('auth_fresh_record_svc')) {
      recorded++;
      return new Response(JSON.stringify('grant-uuid-test'), { status: 200 });
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
    session_id: 'sess-amr-stale1',
    iat: now,
    amr: [{ method: 'password', timestamp: now - 3600 }],
  });
  assert.equal((await submit(staleAmr)).status, 403);

  const refreshBypass = bearer({
    sub: account,
    session_id: 'sess-amr-refr1',
    iat: now,
    amr: [{ method: 'password', timestamp: now - 400 }, { method: 'refresh', timestamp: now - 10 }],
  });
  assert.equal((await submit(refreshBypass)).status, 403);
  assert.equal(recorded, 0);

  const fresh = bearer({
    sub: account,
    session_id: 'sess-amr-fresh1',
    iat: now,
    amr: [{ method: 'otp', timestamp: now - 30 }],
  });
  assert.equal((await submit(fresh)).status, 200);
  assert.equal(recorded, 1);

  const freshAction = bearer({
    sub: account,
    session_id: 'sess-amr-act01',
    iat: now,
    amr: [{ method: 'otp', timestamp: now - 20 }],
  });
  const actionRes = await handle(
    new Request('https://x', {
      method: 'POST',
      headers: { Authorization: freshAction, 'Content-Type': 'application/json' },
      body: JSON.stringify({ action: 'recovery_rotate' }),
    })
  );
  assert.equal(actionRes.status, 200);
  assert.equal(grantIssue, 1);
  assert.equal(lastGrantBody.p_session_id, 'sess-amr-act01');
  assert.equal(lastGrantBody.p_action, 'recovery_rotate');

  const anonOnly = bearer({
    sub: account,
    session_id: 'sess-amr-anon1',
    iat: now,
    amr: [{ method: 'anonymous', timestamp: now - 5 }],
  });
  assert.equal((await submit(anonOnly)).status, 403);
});
