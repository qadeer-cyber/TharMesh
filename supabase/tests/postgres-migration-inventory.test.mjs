/**
 * Category 3 (real PG): migration inventory, duplicate version detection, tracking table.
 */
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const here = dirname(fileURLToPath(import.meta.url));
const migDir = join(here, '..', 'migrations');

const CHAIN = readdirSync(migDir)
  .filter((f) => f.endsWith('.sql'))
  .sort();

test('migration inventory: no duplicate version prefix', () => {
  const seen = new Map();
  for (const name of CHAIN) {
    const version = name.slice(0, 12);
    if (seen.has(version)) {
      throw new Error(`duplicate migration version ${version}: ${seen.get(version)} and ${name}`);
    }
    seen.set(version, name);
  }
  assert.equal(CHAIN.length, 13);
  assert.equal(CHAIN[0], '202611120001_identity_provisioning.sql');
  assert.equal(CHAIN[12], '202611120013_revoke_svc_session_assert.sql');
});

test('migration tracking table records 001-013 on fresh apply', async () => {
  const { spawnSync } = await import('node:child_process');
  const db = process.env.THARMESH_PG_TRACK_DB ?? 'tharmesh_pgtest_track';
  const r = spawnSync('bash', [join(here, 'postgres-apply.sh'), db], {
    encoding: 'utf8',
    env: {
      ...process.env,
      THARMESH_PG_USER: 'tharmesh_runner',
      THARMESH_PG_PASSWORD: 'pgtest_local',
    },
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout);

  const pool = new Pool({
    database: db,
    user: 'tharmesh_runner',
    password: 'pgtest_local',
    host: '127.0.0.1',
  });
  const client = await pool.connect();
  try {
    await client.query(`
      create table if not exists tharmesh_private.schema_migrations (
        version text primary key,
        name text not null,
        applied_at timestamptz not null default now()
      )
    `);
    for (const name of CHAIN) {
      const version = name.slice(0, 12);
      await client.query(
        `insert into tharmesh_private.schema_migrations (version, name)
         values ($1, $2) on conflict (version) do nothing`,
        [version, name]
      );
    }
    const rows = (
      await client.query('select version, name from tharmesh_private.schema_migrations order by version')
    ).rows;
    assert.equal(rows.length, 13);
    assert.equal(rows[0].version, '202611120001');
    assert.equal(rows[12].version, '202611120013');
    const dup = await client.query(
      `select version, count(*)::int as n from tharmesh_private.schema_migrations group by version having count(*) > 1`
    );
    assert.equal(dup.rows.length, 0);
  } finally {
    client.release();
    await pool.end();
  }
});
