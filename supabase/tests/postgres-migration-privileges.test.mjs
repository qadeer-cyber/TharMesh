/**
 * Category 3: final privileges after schema.sql + platform defaults + migrations 001-013.
 */
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createTestPool } from './pg-test-helpers.mjs';
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_privileges';

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], { encoding: 'utf8', env: process.env });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

async function priv(c, obj, kind, priv) {
  const col = kind === 'table' ? 'has_table_privilege' : 'has_function_privilege';
  const row = (await c.query(`select ${col}('authenticated', $1::text, $2) as v`, [obj, priv])).rows[0];
  return row.v;
}

test('migrations 001-013: authenticated privilege surface matches staging intent', async () => {
  ensureDb();
  const pool = createTestPool({ database: DB });
  const c = await pool.connect();
  try {
    for (const p of ['INSERT', 'UPDATE', 'DELETE']) {
      assert.equal(await priv(c, 'public.recovery_blobs', 'table', p), false, `recovery_blobs ${p}`);
    }
    assert.equal(await priv(c, 'public.recovery_blobs', 'table', 'SELECT'), true);

    for (const tbl of [
      'tharmesh_private.canonical_identities',
      'tharmesh_private.identity_challenges',
      'tharmesh_private.enrollment_pending',
    ]) {
      assert.equal(await priv(c, tbl, 'table', 'SELECT'), false, `${tbl} SELECT`);
      assert.equal(await priv(c, tbl, 'table', 'INSERT'), false, `${tbl} INSERT`);
    }

    const svc = await c.query(
      `select p.oid, p.proname, pg_get_function_identity_arguments(p.oid) as args
       from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public' and p.proname like '%\\_svc' escape '\\'`
    );
    assert.ok(svc.rows.length >= 5, 'expected svc RPCs');
    for (const row of svc.rows) {
      const ok = (
        await c.query(`select has_function_privilege('authenticated', $1::oid, 'EXECUTE') as v`, [row.oid])
      ).rows[0].v;
      assert.equal(ok, false, `public.${row.proname}(${row.args})`);
    }

    const privateFns = await c.query(
      `select p.oid, p.proname, pg_get_function_identity_arguments(p.oid) as args
       from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'tharmesh_private' and p.prokind = 'f'`
    );
    for (const row of privateFns.rows) {
      const ok = (
        await c.query(`select has_function_privilege('authenticated', $1::oid, 'EXECUTE') as v`, [row.oid])
      ).rows[0].v;
      assert.equal(ok, false, `tharmesh_private.${row.proname}(${row.args})`);
    }

  } finally {
    c.release();
    await pool.end();
  }
});
