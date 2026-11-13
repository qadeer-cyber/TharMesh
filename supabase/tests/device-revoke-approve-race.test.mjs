/**
 * Revoke vs enrollment_approve_svc on separate connections under device lock.
 */
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import { issueGrant, revokeViaSvc } from './pg-test-helpers.mjs';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_revoke_approve';
const KEY122 = 'A'.repeat(122);
const userA = 'f1000000-0000-4000-8000-0000000000a1';

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: { ...process.env, THARMESH_PG_USER: 'tharmesh_runner', THARMESH_PG_PASSWORD: 'pgtest_local' },
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

function b64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

test('revoke races enrollment_approve_svc', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const admin = await pool.connect();
  try {
    await admin.query('reset role');
    await admin.query('update tharmesh_private.identity_admission set enabled = true');
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userA]);
    const ch = (
      await admin.query('select public.tharmesh_identity_challenge($1::uuid, $2)', [userA, KEY122])
    ).rows[0].tharmesh_identity_challenge;
    await admin.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [
      userA,
      ch,
      KEY122,
    ]);
    await admin.query('set role service_role');
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'t1',$2,$2,'c'), ($1,'t2',$2,$2,'c')`,
      [userA, KEY122]
    );
    const nonce = b64url(randomBytes(12));
    const expires = new Date(Date.now() + 600_000).toISOString();
    await admin.query(
      'select public.tharmesh_enrollment_begin_svc($1::uuid,$2,$3,$4,$5,$6::timestamptz)',
      [userA, 'new-d', KEY122, nonce, 'sig-placeholder-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', expires]
    );
    const sid = 'sess-revoke-appr';
    const grant = await issueGrant(admin, userA, 'device_revoke', sid);
    admin.release();

    const approve = async () => {
      const c = await pool.connect();
      try {
        await c.query('begin');
        await c.query('set role service_role');
        await c.query('select public.tharmesh_enrollment_approve_svc($1::uuid,$2,$3,$4)', [
          userA,
          nonce,
          't1',
          'sig-placeholder-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxyy',
        ]);
        await c.query('commit');
        return { ok: true };
      } catch (e) {
        await c.query('rollback');
        return { code: e.code, message: e.message };
      } finally {
        c.release();
      }
    };

    const revoke = async () => {
      const c = await pool.connect();
      try {
        await c.query('begin');
        await revokeViaSvc(c, userA, 't2', grant, sid, KEY122);
        await c.query('commit');
        return { ok: true };
      } catch (e) {
        await c.query('rollback');
        return { code: e.code, message: e.message };
      } finally {
        c.release();
      }
    };

    const [ra, rb] = await Promise.all([approve(), revoke()]);
    assert.ok(ra.ok || rb.ok, `approve=${JSON.stringify(ra)} revoke=${JSON.stringify(rb)}`);
    const admin2 = await pool.connect();
    const pending = (
      await admin2.query(
        'select approval_sig_b64 from tharmesh_private.enrollment_pending where account_id = $1 and nonce_b64 = $2',
        [userA, nonce]
      )
    ).rows[0];
    const active = (
      await admin2.query(
        'select device_id, revoked_at from public.devices where account_id = $1 order by device_id',
        [userA]
      )
    ).rows;
    const revokedCount = active.filter((r) => r.revoked_at != null).length;
    assert.ok(revokedCount <= 1);
    assert.ok(pending.approval_sig_b64 != null || revokedCount === 1);
    admin2.release();
  } finally {
    await pool.end();
  }
});
