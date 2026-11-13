import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_schema_privs';

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: { ...process.env, THARMESH_PG_USER: 'tharmesh_runner', THARMESH_PG_PASSWORD: 'pgtest_local' },
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

test('fresh schema+migrations: recovery_blobs has no authenticated INSERT/UPDATE', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const c = await pool.connect();
  try {
    const ins = (
      await c.query(
        "select has_table_privilege('authenticated', 'public.recovery_blobs', 'INSERT') as v"
      )
    ).rows[0].v;
    const upd = (
      await c.query(
        "select has_table_privilege('authenticated', 'public.recovery_blobs', 'UPDATE') as v"
      )
    ).rows[0].v;
    assert.equal(ins, false);
    assert.equal(upd, false);
    const policies = (
      await c.query(
        `select policyname, cmd from pg_policies
         where schemaname = 'public' and tablename = 'recovery_blobs'
           and cmd in ('INSERT', 'UPDATE', 'ALL')`
      )
    ).rows;
    assert.equal(policies.length, 0);
  } finally {
    c.release();
    await pool.end();
  }
});
