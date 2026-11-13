/**
 * PG: revoke challenge single-use, expiry, TOCTOU after lock, final DB state.
 */
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import {
  issueGrant,
  issueRevokeChallenge,
  revokeViaSvc,
  revokeWithChallenge,
} from './pg-test-helpers.mjs';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_revoke_ch';
const userA = 'f1000000-0000-4000-8000-0000000000a1';
const userB = 'f1000000-0000-4000-8000-0000000000b2';
const KEY122 = 'A'.repeat(122);

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: process.env,
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

test('revoke challenge replay and expiry preserve grants and devices', async () => {
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
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userA]);
    await admin.query('set role service_role');
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'a',$2,$2,'c'), ($1,'b',$2,$2,'c')`,
      [userA, KEY122]
    );
    const sid = 'sess-ch-pg-01';
    const grant = await issueGrant(admin, userA, 'device_revoke', sid);
    const ch = await issueRevokeChallenge(admin, userA, sid, 'a', 'b', grant);
    await assert.rejects(
      () => revokeViaSvc(admin, userA, ch, 'wrong-sess-id'),
      (e) => e.message?.includes('session') && e.code === '42501'
    );
    await revokeViaSvc(admin, userA, ch, sid);
    await assert.rejects(
      () => revokeViaSvc(admin, userA, ch, sid),
      (e) => e.message?.includes('consumed') && e.code === '42501'
    );
    const consumed = (
      await admin.query('select consumed_at from tharmesh_private.device_revoke_challenges where challenge_id = $1', [
        ch,
      ])
    ).rows[0].consumed_at;
    assert.ok(consumed);
    const bRevoked = (
      await admin.query('select revoked_at from public.devices where account_id = $1 and device_id = $2', [userA, 'b'])
    ).rows[0].revoked_at;
    assert.ok(bRevoked);

    const grant2 = await issueGrant(admin, userA, 'device_revoke', sid);
    const ch2 = await issueRevokeChallenge(admin, userA, sid, 'a', 'b', grant2);
    await admin.query(
      `update tharmesh_private.device_revoke_challenges set expires_at = clock_timestamp() - interval '1 second'
       where challenge_id = $1`,
      [ch2]
    );
    await assert.rejects(
      () => revokeViaSvc(admin, userA, ch2, sid),
      (e) => e.message?.includes('expired') && e.code === '42501'
    );
    const grantStill = (
      await admin.query('select consumed_at from tharmesh_private.sensitive_action_grants where grant_id = $1', [grant2])
    ).rows[0].consumed_at;
    assert.equal(grantStill, null);

    const grant3 = await issueGrant(admin, userA, 'device_revoke', 'sess-ch-pg-02');
    const ch3 = await issueRevokeChallenge(admin, userA, 'sess-ch-pg-02', 'a', 'b', grant3);
    await admin.query('update public.devices set revoked_at = clock_timestamp() where account_id = $1 and device_id = $2', [
      userA,
      'a',
    ]);
    await assert.rejects(
      () => revokeViaSvc(admin, userA, ch3, 'sess-ch-pg-02'),
      (e) => e.message?.includes('not active') && e.code === '42501'
    );

    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userB]);
    const gB = await issueGrant(admin, userB, 'device_revoke', 'sess-b-xxxx1');
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'x',$2,$2,'c'), ($1,'y',$2,$2,'c')`,
      [userB, KEY122]
    );
    const chB = await issueRevokeChallenge(admin, userB, 'sess-b-xxxx1', 'x', 'y', gB);
    await assert.rejects(
      () => revokeViaSvc(admin, userA, chB, 'sess-b-xxxx1'),
      (e) => e.message?.includes('Challenge not found') && e.code === '42501'
    );
  } finally {
    admin.release();
    await pool.end();
  }
});

test('wrong challenge target not revocable via mismatched issue', async () => {
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
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userA]);
    await admin.query('set role service_role');
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'a',$2,$2,'c'), ($1,'b',$2,$2,'c'), ($1,'c',$2,$2,'c')`,
      [userA, KEY122]
    );
    const sid = 'sess-wrong-tgt';
    const grant = await issueGrant(admin, userA, 'device_revoke', sid);
    const ch = await issueRevokeChallenge(admin, userA, sid, 'a', 'b', grant);
    await revokeViaSvc(admin, userA, ch, sid);
    const cActive = (
      await admin.query(
        'select count(*)::int as n from public.devices where account_id = $1 and revoked_at is null',
        [userA]
      )
    ).rows[0].n;
    assert.equal(cActive, 2);
    const cRow = (
      await admin.query('select revoked_at from public.devices where account_id = $1 and device_id = $2', [userA, 'c'])
    ).rows[0].revoked_at;
    assert.equal(cRow, null);
  } finally {
    admin.release();
    await pool.end();
  }
});
