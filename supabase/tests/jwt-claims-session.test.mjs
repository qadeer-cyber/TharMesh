import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createTestPool } from './pg-test-helpers.mjs';
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_jwt_claims';
const userA = 'f1000000-0000-4000-8000-0000000000a1';

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: process.env,
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

test('tharmesh_jwt_session_id reads session_id from request.jwt.claims only', async () => {
  ensureDb();
  const pool = createTestPool({ database: DB });
  const c = await pool.connect();
  try {
    await c.query('begin');
    await c.query('set local role authenticated');
    await c.query("select set_config('request.jwt.claim.sub', $1, true)", [userA]);
    await c.query("select set_config('request.jwt.claims', $1, true)", [
      JSON.stringify({ sub: userA, session_id: 'claims-only-sess1', role: 'authenticated' }),
    ]);
    const sid = (await c.query('select public.tharmesh_jwt_session_id() as s')).rows[0].s;
    assert.equal(sid, 'claims-only-sess1');
    await c.query('rollback');
  } finally {
    c.release();
    await pool.end();
  }
});
