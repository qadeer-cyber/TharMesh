/**
 * Cat 3: authenticated EXECUTE allowlist matches docs/authorization-surface-audit.md;
 * every *_svc and tharmesh_private function denied; smoke calls are not "permission denied".
 */
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createTestPool, withSession } from './pg-test-helpers.mjs';

const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_auth_surface';
const userA = 'f1000000-0000-4000-8000-0000000000a1';

/** Must match docs/authorization-surface-audit.md (authenticated EXECUTE). */
/** Product RPCs (must have authenticated EXECUTE). */
const ALLOWED_NAMES = new Set([
  'tharmesh_identity_state',
  'tharmesh_uses_canonical_identity',
  'tharmesh_list_devices',
  'tharmesh_commit_explicit_reset',
  'tharmesh_account_evidence',
  'tharmesh_auth_fresh_required',
  'tharmesh_recovery_generation',
]);

/** JWT claim helpers (EXECUTE ok; not *_svc). */
const JWT_HELPERS = new Set(['tharmesh_jwt_session_id']);

/** Must not be callable by authenticated (Edge path only). */
const REVOKED_CLIENT_RPCS = new Set(['tharmesh_device_revoke']);

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], { encoding: 'utf8', env: process.env });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

function denyMsg(e) {
  return String(e?.message ?? '').toLowerCase().includes('permission denied');
}

test('014 client surface: authenticated allowlist, svc/private denied, smoke RPCs', async () => {
  ensureDb();
  const pool = createTestPool({ database: DB });
  const c = await pool.connect();
  try {
    await c.query('reset role');
    await c.query('insert into auth.users values ($1, now()) on conflict do nothing', [userA]);

    const pub = await c.query(
      `select p.oid,
              p.proname,
              pg_get_function_identity_arguments(p.oid) as args,
              p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')' as sig,
              has_function_privilege('authenticated', p.oid, 'EXECUTE') as auth_exec
       from pg_proc p
       join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public' and p.proname like 'tharmesh_%' and p.prokind = 'f'
       order by p.proname, args`
    );

    const authGranted = new Set();
    for (const row of pub.rows) {
      if (row.proname.endsWith('_svc')) {
        assert.equal(row.auth_exec, false, `authenticated must not execute svc ${row.sig}`);
        continue;
      }
      if (REVOKED_CLIENT_RPCS.has(row.proname)) {
        assert.equal(row.auth_exec, false, `authenticated must not execute ${row.sig}`);
        continue;
      }
      if (row.auth_exec) {
        const ok = ALLOWED_NAMES.has(row.proname) || JWT_HELPERS.has(row.proname);
        assert.ok(ok, `unexpected authenticated EXECUTE on ${row.sig}`);
        authGranted.add(row.proname);
      }
    }
    for (const name of ALLOWED_NAMES) {
      assert.ok(authGranted.has(name), `missing authenticated EXECUTE for ${name}`);
    }
    for (const name of JWT_HELPERS) {
      assert.ok(authGranted.has(name), `missing authenticated EXECUTE for ${name}`);
    }

    const priv = await c.query(
      `select p.oid, p.proname, pg_get_function_identity_arguments(p.oid) as args
       from pg_proc p join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'tharmesh_private' and p.prokind = 'f'`
    );
    for (const row of priv.rows) {
      const ok = (
        await c.query(`select has_function_privilege('authenticated', $1::oid, 'EXECUTE') as v`, [row.oid])
      ).rows[0].v;
      assert.equal(ok, false, `tharmesh_private.${row.proname}(${row.args})`);
    }

    await withSession(c, userA, 'sess-audit-01', async () => {
      await c.query('select * from public.tharmesh_identity_state()');
      await c.query('select public.tharmesh_uses_canonical_identity()');
      await c.query('select * from public.tharmesh_list_devices($1)', [null]);
      await c.query('select * from public.tharmesh_account_evidence()');
      await c.query('select public.tharmesh_auth_fresh_required($1)', [900]);
      await c.query('select public.tharmesh_recovery_generation()');
    });

    await withSession(c, userA, 'sess-audit-02', async () => {
      await assert.rejects(
        () =>
          c.query('select public.tharmesh_device_revoke($1,$2::uuid,$3)', [
            'x',
            '00000000-0000-4000-8000-000000000001',
            'A'.repeat(80),
          ]),
        (e) => denyMsg(e) || e.code === '42501'
      );
    });

    await withSession(c, userA, 'sess-audit-03', async () => {
      await assert.rejects(
        () => c.query('select public.tharmesh_sensitive_grant_issue_svc($1::uuid,$2,$3,$4::bigint,$5::bigint,$6)', [
          userA,
          'device_revoke',
          'sess-audit-03',
          1,
          1,
          60,
        ]),
        (e) => denyMsg(e) || e.code === '42501'
      );
    });
  } finally {
    c.release();
    await pool.end();
  }
});
