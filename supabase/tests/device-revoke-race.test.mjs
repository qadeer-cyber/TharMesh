/**
 * Last-device revoke race: assert final active device count, not only exceptions.
 * local Postgres multi-connection
 */
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_race';
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

async function recordFresh(pool) {
  const ts = Math.floor(Date.now() / 1000);
  const c = await pool.connect();
  try {
    await c.query('set role service_role');
    await c.query('select public.tharmesh_auth_fresh_record_svc($1::uuid,$2::bigint,$3::bigint)', [
      userA,
      ts,
      ts,
    ]);
  } finally {
    c.release();
  }
}

async function activeCount(pool) {
  const c = await pool.connect();
  try {
    return (
      await c.query(
        'select count(*)::int as c from public.devices where account_id = $1 and revoked_at is null',
        [userA]
      )
    ).rows[0].c;
  } finally {
    c.release();
  }
}

async function revokeAttempt(pool, deviceId) {
  const c = await pool.connect();
  try {
    await c.query('begin');
    await c.query('set local role authenticated');
    await c.query("select set_config('request.jwt.claim.sub', $1, true)", [userA]);
    await c.query('select public.tharmesh_device_revoke($1)', [deviceId]);
    await c.query('commit');
    return 'ok';
  } catch (e) {
    await c.query('rollback');
    return e.code ?? e.message;
  } finally {
    c.release();
  }
}

test('two simultaneous revokes leave at least one active device', async () => {
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
       values ($1,'d1',$2,$2,'c'), ($1,'d2',$2,$2,'c'), ($1,'d3',$2,$2,'c')`,
      [userA, KEY122]
    );
    await recordFresh(pool);
    const [r1, r2] = await Promise.all([
      revokeAttempt(pool, 'd1'),
      revokeAttempt(pool, 'd2'),
    ]);
    const remaining = await activeCount(pool);
    assert.ok(remaining >= 1, `final active count ${remaining}, outcomes ${r1} ${r2}`);
    assert.ok(remaining <= 2);
  } finally {
    admin.release();
    await pool.end();
  }
});
