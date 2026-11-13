/**
 * Category 3: real postgres-migrate-runner tracking (not hand-inserted rows in the test).
 */
import assert from 'node:assert/strict';
import { mkdtempSync, cpSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const migDir = join(root, 'migrations');

function pgCreds() {
  return {
    user: process.env.THARMESH_PG_USER ?? process.env.PGUSER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? process.env.PGPASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? process.env.PGHOST ?? '127.0.0.1',
  };
}

function run(cmd, env = {}) {
  const r = spawnSync('bash', cmd, {
    encoding: 'utf8',
    env: { ...process.env, ...env },
  });
  return r;
}

async function countMigrations(db) {
  const { user, password, host } = pgCreds();
  const pool = new Pool({
    database: db,
    user,
    password,
    host,
  });
  const client = await pool.connect();
  try {
    const rows = (await client.query('select version from tharmesh_private.schema_migrations order by version')).rows;
    return rows;
  } finally {
    client.release();
    await pool.end();
  }
}

test('runner fresh + reapply is no-op', async () => {
  const db = 'tharmesh_pgtest_runner_reapply';
  const fresh = run([join(here, 'postgres-migrate-runner.sh'), 'fresh', db]);
  assert.equal(fresh.status, 0, fresh.stderr || fresh.stdout);
  const afterFresh = await countMigrations(db);
  assert.equal(afterFresh.length, 13);
  assert.equal(afterFresh[0].version, '202611120001');
  const reapply = run([join(here, 'postgres-migrate-runner.sh'), 'reapply', db]);
  assert.equal(reapply.status, 0, reapply.stderr || reapply.stdout);
  assert.match(reapply.stdout, /skipped=13/);
  const afterReapply = await countMigrations(db);
  assert.deepEqual(afterFresh.map((r) => r.version), afterReapply.map((r) => r.version));
});

test('duplicate version in temp dir fails runner', () => {
  const tmp = mkdtempSync(join(tmpdir(), 'tharmesh-mig-dup-'));
  cpSync(migDir, tmp, { recursive: true });
  writeFileSync(
    join(tmp, '202611120001_duplicate_identity.sql'),
    '-- intentional duplicate version prefix\nselect 1;\n'
  );
  const r = run([join(here, 'postgres-migrate-runner.sh'), 'fresh', 'tharmesh_pgtest_runner_dup'], {
    MIGRATIONS_DIR: tmp,
  });
  assert.notEqual(r.status, 0);
  assert.match(r.stderr + r.stdout, /duplicate version/i);
});

test('gap in version sequence is rejected', () => {
  const tmp = mkdtempSync(join(tmpdir(), 'tharmesh-mig-gap-'));
  for (const name of [
    '202611120001_identity_provisioning.sql',
    '202611120002_identity_proof.sql',
    '202611120004_trusted_device_enrollment.sql',
  ]) {
    cpSync(join(migDir, name), join(tmp, name));
  }
  const r = run([join(here, 'postgres-migrate-runner.sh'), 'fresh', 'tharmesh_pgtest_runner_gap'], {
    MIGRATIONS_DIR: tmp,
  });
  assert.notEqual(r.status, 0);
  assert.match(r.stderr + r.stdout, /missing version gap|expected 13/i);
});
