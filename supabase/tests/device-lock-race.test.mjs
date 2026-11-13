/**
 * Account device mutation lock: revoke vs enrollment finish/approve.
 */
import assert from 'node:assert/strict';
import { createPrivateKey, createSign, generateKeyPairSync, randomBytes } from 'node:crypto';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import { issueGrant, revokeWithChallenge } from './pg-test-helpers.mjs';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_device_lock';
const KEY122 = 'A'.repeat(122);
const userA = 'f1000000-0000-4000-8000-0000000000aa';

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: { ...process.env, THARMESH_PG_USER: 'tharmesh_runner', THARMESH_PG_PASSWORD: 'pgtest_local' },
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

function b64url(buf) {
  return Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function keyMaterial() {
  const pair = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const spki = pair.publicKey.export({ type: 'spki', format: 'der' });
  const pkcs8 = pair.privateKey.export({ type: 'pkcs8', format: 'der' });
  const privKey = createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' });
  return { pubB64: b64url(spki), privKey };
}

test('revoke races enrollment_finish under account lock', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const trusted = keyMaterial();
  const admin = await pool.connect();
  try {
    await admin.query('reset role');
    await admin.query('update tharmesh_private.identity_admission set enabled = true');
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userA]);
    const ch = (
      await admin.query('select public.tharmesh_identity_challenge($1::uuid, $2)', [userA, KEY122])
    ).rows[0].tharmesh_identity_challenge;
    await admin.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [
      userA,
      ch,
      KEY122,
    ]);
    await admin.query('set role service_role');
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'t1',$2,$3,'c'), ($1,'t2',$2,$2,'c')`,
      [userA, KEY122, trusted.pubB64]
    );
    const nonce = b64url(randomBytes(12));
    const expires = new Date(Date.now() + 600_000).toISOString();
    await admin.query('select public.tharmesh_enrollment_begin_svc($1::uuid,$2,$3,$4,$5,$6::timestamptz)', [
      userA,
      'new-dev',
      trusted.pubB64,
      nonce,
      'sig-placeholder-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
      expires,
    ]);
    const gRevoke = await issueGrant(admin, userA, 'device_revoke', 'sess-lock-rev');
    const finish = async () => {
      const c = await pool.connect();
      try {
        await c.query('set role service_role');
        await c.query('select public.tharmesh_enrollment_finish_svc($1::uuid,$2,$3)', [
          userA,
          nonce,
          'cert:new',
        ]);
        return { ok: true };
      } catch (e) {
        return { code: e.code };
      } finally {
        c.release();
      }
    };
    const revoke = async () => {
      const c = await pool.connect();
      try {
        await c.query('begin');
        await revokeWithChallenge(c, userA, 'sess-lock-rev', 't1', 't2', gRevoke);
        await c.query('commit');
        return { ok: true };
      } catch (e) {
        await c.query('rollback');
        return { code: e.code, message: e.message };
      } finally {
        c.release();
      }
    };
    const [rf, rr] = await Promise.all([finish(), revoke()]);
    assert.ok(rf.ok || rr.ok);
    const devices = (
      await admin.query('select device_id, revoked_at from public.devices where account_id = $1 order by device_id', [
        userA,
      ])
    ).rows;
    assert.equal(devices.length, 2);
  } finally {
    admin.release();
    await pool.end();
  }
});
