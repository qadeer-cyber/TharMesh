const { PGlite } = require('@electric-sql/pglite');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');

test('canonical identity permissions, challenge lifecycle and legacy compatibility', async (t) => {
    const db = new PGlite();
    t.after(() => db.close());
    const exec = (sql) => db.exec(sql);
    const query = async (sql, params) => (await db.query(sql, params)).rows[0];
    const apply = (file) => exec(readFileSync(join(__dirname, '..', file), 'utf8'));
    await exec(`
        create role anon;
        create role authenticated;
        create role service_role bypassrls;
        create schema auth;
        create table auth.users (id uuid primary key, email_confirmed_at timestamptz);
        create function auth.uid() returns uuid language sql stable as
        $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
        grant usage on schema auth to anon, authenticated, service_role;
    `);
    await apply('schema.sql');
    for (const migration of [
        '202611120001_identity_provisioning.sql',
        '202611120002_identity_proof.sql',
        '202611120003_identity_device_guard.sql',
        '202611120004_trusted_device_enrollment.sql',
        '202611120005_device_current_explicit_reset.sql',
        '202611120006_enrollment_fail_closed_edge.sql',
    ]) await apply('migrations/' + migration);
    await exec('grant select, insert, update, delete on public.devices to authenticated');
    const historical = 'e1000000-0000-4000-8000-000000000001';
    const current = 'e1000000-0000-4000-8000-000000000002';
    const key = 'A'.repeat(122);
    const otherKey = 'B'.repeat(122);
    const subject = (id) => query("select set_config('request.jwt.claim.sub', $1, false)", [id]);
    const state = () => query('select * from public.tharmesh_identity_state()');
    const challenge = async (id, publicKey = key) =>
        (await query('select public.tharmesh_identity_challenge($1,$2) as nonce', [id, publicKey])).nonce;
    const finish = (id, nonce, publicKey = key) =>
        query('select * from public.tharmesh_identity_finish($1,$2,$3)', [id, nonce, publicKey]);
    const addDevice = (id) => query(
        "insert into public.devices values ($1, 'phone', $2, 'device-key', 'certificate')",
        [id, key]
    );
    await query('insert into auth.users values ($1, now())', [historical]);
    await exec('update tharmesh_private.identity_admission set enabled = true');
    await query('insert into auth.users values ($1, now())', [current]);

    await exec('set role anon');
    await assert.rejects(state, { code: '42501' });
    await exec('set role authenticated');
    await subject(historical);
    assert.equal((await state()).state, 'legacy_unknown');
    await addDevice(historical);
    await assert.rejects(() => addDevice(current), { code: '42501' });
    await assert.rejects(() => challenge(current), { code: '42501' });
    await subject(current);
    assert.equal((await state()).state, 'unprovisioned');
    await assert.rejects(() => addDevice(current), { code: '42501' });
    await assert.rejects(() => query('select * from public.tharmesh_provision_identity($1)', [key]), { code: '42501' });

    await exec('set role service_role');
    const expired = await challenge(current);
    await exec('reset role');
    await exec("update tharmesh_private.identity_challenges set expires_at = now() - interval '1 second'");
    await exec('set role service_role');
    await assert.rejects(() => finish(current, expired), { code: '42501' });
    const nonce = await challenge(current);
    await assert.rejects(() => finish(historical, nonce), { code: '42501' });
    await assert.rejects(() => finish(current, nonce, otherKey), { code: '42501' });
    await exec('begin');
    assert.equal((await finish(current, nonce)).mesh_public_key, key);
    await exec('rollback');
    assert.equal((await finish(current, nonce)).mesh_public_key, key);
    await assert.rejects(() => finish(current, nonce), { code: '42501' });
    assert.equal((await finish(current, await challenge(current))).mesh_public_key, key);
    await assert.rejects(() => challenge(current, otherKey), { code: '42501' });
    await exec('set role authenticated');
    await subject(current);
    assert.equal((await state()).generation, 1);
    await assert.rejects(() => addDevice(current), { code: '42501' });
    await subject(historical);
    assert.equal((await query('select count(*)::int as count from public.devices')).count, 1);

    const backedUp = 'e1000000-0000-4000-8000-000000000003';
    await exec('reset role');
    await query('insert into auth.users values ($1, now())', [backedUp]);
    await query(`insert into public.recovery_blobs
        (account_id, kdf, n, r, p, salt_b64, iv_b64, ciphertext_b64)
        values ($1, 'scrypt', 16384, 8, 1, 'salt', 'iv', 'ciphertext')`, [backedUp]);
    await exec('set role service_role');
    assert.equal((await finish(backedUp, await challenge(backedUp))).state, 'recovery_required');
});
