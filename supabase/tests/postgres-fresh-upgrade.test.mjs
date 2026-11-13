/**
 * (a) Fresh DB: schema.sql + migrations 001→013 in order.
 * (b) Upgrade: legacy seed at base schema, then 001→013; data preserved, privileges secure.
 * (c) Full-chain re-run: 001 fails on populated DB (documented non-idempotent); 010–013 re-apply ok.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
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

const CHAIN = [
  '202611120001_identity_provisioning.sql',
  '202611120002_identity_proof.sql',
  '202611120003_identity_device_guard.sql',
  '202611120004_trusted_device_enrollment.sql',
  '202611120005_device_current_explicit_reset.sql',
  '202611120006_enrollment_fail_closed_edge.sql',
  '202611120007_bootstrap_proof_and_auth_amr.sql',
  '202611120008_recovery_cas_revoke_fresh.sql',
  '202611120009_recovery_lock_device_serialize.sql',
  '202611120010_grant_session_atomic_recovery.sql',
  '202611120011_revoke_possession_jwt_claims.sql',
  '202611120012_revoke_challenge_possession.sql',
  '202611120013_revoke_svc_session_assert.sql',
];

const LEGACY_USER = 'a1000000-0000-4000-8000-000000000101';

function poolConfig(db) {
  return {
    database: db,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  };
}

function applyFile(client, path) {
  return client.query(readFileSync(path, 'utf8'));
}

function dropCreate(db) {
  const r = spawnSync(
    'bash',
    [join(here, 'postgres-apply.sh'), db],
    {
      encoding: 'utf8',
      env: { ...process.env, THARMESH_PG_USER: 'tharmesh_runner', THARMESH_PG_PASSWORD: 'pgtest_local' },
    }
  );
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

test('postgres fresh + upgrade + idempotency policy', async () => {
  const freshDb = process.env.THARMESH_PG_FRESH_DB ?? 'tharmesh_pgtest_fresh';
  const upgradeDb = process.env.THARMESH_PG_UPGRADE_DB ?? 'tharmesh_pgtest_upgrade';

  dropCreate(freshDb);
  const freshPool = new Pool(poolConfig(freshDb));
  const fresh = await freshPool.connect();
  try {
    const dev = await fresh.query('select count(*)::int as n from public.devices');
    assert.ok(dev.rows[0].n >= 0);
    const revokeSig = await fresh.query(
      `select pg_get_function_identity_arguments(p.oid) as args
       from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public' and p.proname = 'tharmesh_device_revoke_svc'`
    );
    assert.equal(revokeSig.rows[0].args, 'p_account uuid, p_challenge_id uuid, p_session_id text');
  } finally {
    fresh.release();
    await freshPool.end();
  }

  const pool = new Pool(poolConfig('postgres'));
  const admin = await pool.connect();
  try {
    await admin.query(`DROP DATABASE IF EXISTS ${upgradeDb}`);
    await admin.query(`CREATE DATABASE ${upgradeDb}`);
  } finally {
    admin.release();
  }

  const upPool = new Pool(poolConfig(upgradeDb));
  const up = await upPool.connect();
  try {
    await up.query('CREATE EXTENSION IF NOT EXISTS plpython3u');
    await applyFile(up, join(here, 'postgres-bootstrap.sql'));
    await applyFile(up, join(root, 'schema.sql'));
    await applyFile(up, join(here, 'fixtures/pre_0.6_legacy_seed.sql'));

    for (const name of CHAIN) {
      await applyFile(up, join(migDir, name));
    }

    const handle = (
      await up.query('select handle from public.profiles where account_id = $1::uuid', [LEGACY_USER])
    ).rows[0]?.handle;
    assert.equal(handle, 'legacy_user');

    const blob = (
      await up.query('select ciphertext_b64 from public.recovery_blobs where account_id = $1::uuid', [
        LEGACY_USER,
      ])
    ).rows[0]?.ciphertext_b64;
    assert.equal(blob, 'Y2lwaGVy');

    const device = (
      await up.query(
        'select device_id from public.devices where account_id = $1::uuid and revoked_at is null',
        [LEGACY_USER]
      )
    ).rows[0]?.device_id;
    assert.equal(device, 'phone-original');

    const insDenied = await up
      .query(
        `select has_table_privilege('authenticated', 'public.recovery_blobs', 'INSERT') as ok`
      )
      .then((r) => r.rows[0].ok);
    assert.equal(insDenied, false);

    await assert.rejects(
      () => applyFile(up, join(migDir, CHAIN[0])),
      (e) => String(e.message).includes('already exists') || e.code === '42P07'
    );
    await up.query('ROLLBACK');

    for (const name of CHAIN.slice(9)) {
      await applyFile(up, join(migDir, name));
    }
  } finally {
    up.release();
    await upPool.end();
  }
  await pool.end();
});
