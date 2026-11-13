import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import { generateKeyPairSync } from 'node:crypto';
import {
  issueGrant,
  issueRevokeChallenge,
  revokeViaSvc,
  revokeWithChallenge,
  withSession,
} from './pg-test-helpers.mjs';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_grants';
const userA = 'f1000000-0000-4000-8000-0000000000a1';
const userB = 'f1000000-0000-4000-8000-0000000000b2';
const KEY122 = 'A'.repeat(122);

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: { ...process.env, THARMESH_PG_USER: 'tharmesh_runner', THARMESH_PG_PASSWORD: 'pgtest_local' },
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

function activeKey() {
  const pair = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const spki = pair.publicKey.export({ type: 'spki', format: 'der' });
  return Buffer.from(spki)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

test('sensitive grants: session, action, expiry, replay', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const admin = await pool.connect();
  const livePub = activeKey();
  try {
    await admin.query('reset role');
    await admin.query('insert into auth.users values ($1, now()), ($2, now()) on conflict do nothing', [userA, userB]);
    await admin.query('set role service_role');
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'d1',$2,$3,'c'), ($1,'d2',$2,$2,'c')`,
      [userA, KEY122, livePub]
    );

    await assert.rejects(
      () => issueGrant(admin, userA, 'bogus_action', 'sess-abc12345'),
      (e) => e.code === '42501'
    );

    const sid = 'sess-grant-abc1';
    const grant = await issueGrant(admin, userA, 'device_revoke', sid);

    await assert.rejects(
      () => issueRevokeChallenge(admin, userA, 'wrong-sess-1', 'd1', 'd2', grant),
      (e) => e.message?.includes('session') || e.code === '42501'
    );

    await admin.query('set role service_role');
    await admin.query(
      `update tharmesh_private.sensitive_action_grants set expires_at = clock_timestamp() - interval '1 min'
       where grant_id = $1`,
      [grant]
    );
    await assert.rejects(
      () => issueRevokeChallenge(admin, userA, sid, 'd1', 'd2', grant),
      (e) => e.message?.includes('expired') || e.code === '42501'
    );

    const sid2 = 'sess-grant-use1';
    const g2 = await issueGrant(admin, userA, 'device_revoke', sid2);
    const ch2 = await issueRevokeChallenge(admin, userA, sid2, 'd1', 'd2', g2);
    await revokeViaSvc(admin, userA, ch2, sid2);
    await assert.rejects(
      () => revokeViaSvc(admin, userA, ch2, sid2),
      (e) => e.message?.includes('consumed') || e.code === '42501'
    );

    const sidB = 'sess-user-b-xxx';
    const gCross = await issueGrant(admin, userB, 'device_revoke', sidB);
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'d1',$2,$2,'c'), ($1,'d2',$2,$2,'c') on conflict do nothing`,
      [userB, KEY122]
    );
    const chCross = await issueRevokeChallenge(admin, userB, sidB, 'd1', 'd2', gCross);
    await assert.rejects(
      () => revokeViaSvc(admin, userA, chCross, 'sess-b-xxxx1'),
      (e) => e.message?.includes('Challenge not found') || e.code === '42501'
    );

    const gRot = await issueGrant(admin, userA, 'recovery_rotate', sid);
    await assert.rejects(
      () => issueRevokeChallenge(admin, userA, sid, 'd1', 'd2', gRot),
      (e) => e.message?.includes('action') || e.code === '42501'
    );

    await withSession(admin, userA, sid, async () => {
      await assert.rejects(
        () => admin.query('select public.tharmesh_device_revoke($1,$2::uuid,$3)', ['d1', gRot, livePub]),
        (e) => e.message?.includes('disabled') || e.code === '42501'
      );
    });

    await admin.query('delete from public.devices where account_id = $1', [userA]);
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'d1',$2,$3,'c'), ($1,'d2',$2,$2,'c')`,
      [userA, KEY122, livePub]
    );
    const gKillD1 = await issueGrant(admin, userA, 'device_revoke', 'sess-kill-d1');
    await revokeWithChallenge(admin, userA, 'sess-kill-d1', 'd2', 'd1', gKillD1);
    const gDead = await issueGrant(admin, userA, 'device_revoke', 'sess-revoked');
    await assert.rejects(
      () => issueRevokeChallenge(admin, userA, 'sess-revoked', 'd1', 'd2', gDead),
      (e) => e.message?.includes('not active') || e.code === '42501'
    );
  } finally {
    admin.release();
    await pool.end();
  }
});
