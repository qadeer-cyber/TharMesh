/**
 * Device mutation races: separate connections, exact error codes, final state.
 */
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import { issueGrant, issueRevokeChallenge, revokeViaSvc, revokeWithChallenge } from './pg-test-helpers.mjs';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_race';
const KEY122 = 'A'.repeat(122);
const userA = 'f1000000-0000-4000-8000-0000000000a1';
const userB = 'f1000000-0000-4000-8000-0000000000b2';

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: { ...process.env, THARMESH_PG_USER: 'tharmesh_runner', THARMESH_PG_PASSWORD: 'pgtest_local' },
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

async function activeCount(pool, account = userA) {
  const c = await pool.connect();
  try {
    return (
      await c.query(
        'select count(*)::int as c from public.devices where account_id = $1 and revoked_at is null',
        [account]
      )
    ).rows[0].c;
  } finally {
    c.release();
  }
}

async function revokeWithGrant(pool, requestingId, targetId, grantId, sessionId) {
  const c = await pool.connect();
  try {
    await c.query('begin');
    await revokeWithChallenge(c, userA, sessionId, requestingId, targetId, grantId);
    await c.query('commit');
    return { ok: true };
  } catch (e) {
    await c.query('rollback');
    return { code: e.code, message: e.message };
  } finally {
    c.release();
  }
}

async function seedDevices(pool, count) {
  const admin = await pool.connect();
  try {
    await admin.query('reset role');
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userA]);
    await admin.query('delete from public.devices where account_id = $1', [userA]);
    await admin.query('set role service_role');
    const ids = [];
    for (let i = 1; i <= count; i++) {
      const id = `d${i}`;
      ids.push(id);
      await admin.query(
        `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
         values ($1,$2,$3,$3,'c')`,
        [userA, id, KEY122]
      );
    }
    return ids;
  } finally {
    admin.release();
  }
}

test('two devices, two simultaneous revokes: exactly one succeeds', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const ids = await seedDevices(pool, 2);
  const sid1 = 'sess-race-2a';
  const sid2 = 'sess-race-2b';
  const admin = await pool.connect();
  const g1 = await issueGrant(admin, userA, 'device_revoke', sid1);
  const g2 = await issueGrant(admin, userA, 'device_revoke', sid2);
  admin.release();
  const [r1, r2] = await Promise.all([
    revokeWithGrant(pool, ids[0], ids[1], g1, sid1),
    revokeWithGrant(pool, ids[1], ids[0], g2, sid2),
  ]);
  const ok = [r1, r2].filter((r) => r.ok).length;
  assert.equal(ok, 1);
  assert.equal(await activeCount(pool), 1);
  await pool.end();
});

test('three devices, three simultaneous revokes: two succeed', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const ids = await seedDevices(pool, 3);
  const admin = await pool.connect();
  const grants = await Promise.all(
    ids.map((_, i) => issueGrant(admin, userA, 'device_revoke', `sess-race-3-${i}`))
  );
  admin.release();
  const results = await Promise.all(
    ids.map((id, i) => {
      const other = ids[(i + 1) % ids.length];
      return revokeWithGrant(pool, id, other, grants[i], `sess-race-3-${i}`);
    })
  );
  const ok = results.filter((r) => r.ok).length;
  assert.equal(ok, 2);
  assert.equal(await activeCount(pool), 1);
  await pool.end();
});

test('same device revoked twice concurrently', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  await seedDevices(pool, 2);
  const admin = await pool.connect();
  const g1 = await issueGrant(admin, userA, 'device_revoke', 'sess-dup-1');
  const g2 = await issueGrant(admin, userA, 'device_revoke', 'sess-dup-2');
  admin.release();
  const [r1, r2] = await Promise.all([
    revokeWithGrant(pool, 'd2', 'd1', g1, 'sess-dup-1'),
    revokeWithGrant(pool, 'd2', 'd1', g2, 'sess-dup-2'),
  ]);
  assert.equal([r1, r2].filter((r) => r.ok).length, 1);
  const loser = r1.ok ? r2 : r1;
  assert.equal(loser.code, '42501');
  assert.equal(await activeCount(pool), 1);
  await pool.end();
});

test('already-revoked device returns 42501', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  await seedDevices(pool, 2);
  const admin = await pool.connect();
  const g1 = await issueGrant(admin, userA, 'device_revoke', 'sess-already');
  admin.release();
  await revokeWithGrant(pool, 'd2', 'd1', g1, 'sess-already');
  const admin2 = await pool.connect();
  const g2 = await issueGrant(admin2, userA, 'device_revoke', 'sess-already-2');
  admin2.release();
  const again = await revokeWithGrant(pool, 'd2', 'd1', g2, 'sess-already-2');
  assert.equal(again.code, '42501');
  await pool.end();
});

test('cross-account revoke denied', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  await seedDevices(pool, 2);
  const admin = await pool.connect();
  await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userB]);
  await admin.query('set role service_role');
  await admin.query(
    `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
     values ($1,'d1',$2,$2,'c'), ($1,'d2',$2,$2,'c')`,
    [userB, KEY122]
  );
  const g = await issueGrant(admin, userB, 'device_revoke', 'sess-cross-b');
  const ch = await issueRevokeChallenge(admin, userB, 'sess-cross-b', 'd1', 'd2', g);
  admin.release();
  await assert.rejects(() => revokeViaSvc(admin, userA, ch), (e) => e.code === '42501');
  await pool.end();
});
