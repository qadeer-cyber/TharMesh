import assert from 'node:assert/strict';
import test from 'node:test';

test('Edge handler verifies account-bound P-256 signatures before invoking the write RPC', async () => {
    let handle;
    const environment = new Map([
        ['SUPABASE_URL', 'https://test.invalid'],
        ['SUPABASE_ANON_KEY', 'test-anon'],
        ['SUPABASE_SERVICE_ROLE_KEY', 'test-service'],
    ]);
    globalThis.Deno = { env: environment, serve: (handler) => { handle = handler; } };
    const account = 'e1000000-0000-4000-8000-000000000001';
    const nonce = 'e1000000-0000-4000-8000-000000000002';
    let writes = 0;
    globalThis.fetch = async (url, options) => {
        if (url.endsWith('/auth/v1/user')) {
            if (options.headers.Authorization !== 'Bearer test-user') return new Response(null, { status: 401 });
            return Response.json({ id: account, email_confirmed_at: '2026-11-12T00:00:00Z' });
        }
        assert(url.endsWith('/rpc/tharmesh_identity_finish'));
        assert.equal(options.headers.Authorization, 'Bearer test-service');
        assert.equal(JSON.parse(options.body).p_account, account);
        assert.equal(JSON.parse(options.body).p_nonce, nonce);
        writes++;
        return Response.json([{ state: 'active' }]);
    };
    await import('../functions/identity-proof/index.ts');
    const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
    const key = Buffer.from(await crypto.subtle.exportKey('spki', pair.publicKey)).toString('base64url');
    const message = new TextEncoder().encode(`tharmesh:identity:register:v1\n${account}\n${nonce}\n${key}`);
    const proof = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, pair.privateKey, message);
    const payload = { operation: 'finish', key, nonce, signature: Buffer.from(proof).toString('base64url') };
    const submit = (body, token = 'test-user') => handle(new Request('https://test.invalid', {
        method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: JSON.stringify(body),
    }));
    assert.equal((await submit(payload, 'invalid')).status, 401);
    assert.equal((await submit({ ...payload, key: 'A'.repeat(122) })).status, 400);
    assert.equal((await submit({ ...payload, nonce: account })).status, 403);
    assert.equal(writes, 0);
    assert.equal((await submit({ ...payload, accountId: nonce })).status, 200);
    assert.equal(writes, 1);
});
