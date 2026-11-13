/**
 * Concurrent enrollment_approve_svc (trusted A) vs revoke of device A.
 */
import assert from 'node:assert/strict';
import { createPrivateKey, createSign, generateKeyPairSync, randomBytes } from 'node:crypto';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import { createTestPool, issueGrant, issueRevokeChallenge, releaseClient, revokeViaSvc } from './pg-test-helpers.mjs';

const require = createRequire(import.meta.url);
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_revoke_approve';
const DB_TWO = `${DB}_two`;
const KEY122 = 'A'.repeat(122);
const userA = 'f1000000-0000-4000-8000-0000000000a1';

function ensureDb(name) {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, name], {
    encoding: 'utf8',
    env: process.env,
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
  return { pubB64: b64url(spki), privKey: createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' }) };
}

async function raceScenario(pool, { onlyTwoDevices }) {
  const devA = keyMaterial();
  const devB = keyMaterial();
  const admin = await pool.connect();
  await admin.query('reset role');
  await admin.query('update tharmesh_private.identity_admission set enabled = true');
  await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userA]);
  const ch = (
    await admin.query('select public.tharmesh_identity_challenge($1::uuid, $2)', [userA, KEY122])
  ).rows[0].tharmesh_identity_challenge;
  await admin.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [userA, ch, KEY122]);
  await admin.query('set role service_role');
  if (onlyTwoDevices) {
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'devA',$2,$3,'c'), ($1,'devB',$2,$4,'c')`,
      [userA, KEY122, devA.pubB64, devB.pubB64]
    );
  } else {
    await admin.query(
      `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
       values ($1,'devA',$2,$3,'c'), ($1,'devB',$2,$4,'c'), ($1,'devC',$2,$2,'c')`,
      [userA, KEY122, devA.pubB64, devB.pubB64]
    );
  }
  const nonce = b64url(randomBytes(12));
  const expires = new Date(Date.now() + 600_000).toISOString();
  await admin.query(
    'select public.tharmesh_enrollment_begin_svc($1::uuid,$2,$3,$4,$5,$6::timestamptz)',
    [userA, 'new-dev', devB.pubB64, nonce, 'sig-placeholder-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx', expires]
  );
  const sid = onlyTwoDevices ? 'sess-appr-2dev' : 'sess-appr-3dev';
  const grant = await issueGrant(admin, userA, 'device_revoke', sid);
  const challengeId = await issueRevokeChallenge(admin, userA, sid, 'devB', 'devA', grant);
  await releaseClient(admin);

  const approve = async () => {
    const c = await pool.connect();
    try {
      await c.query('begin');
      await c.query('set role service_role');
      await c.query('select public.tharmesh_enrollment_approve_svc($1::uuid,$2,$3,$4)', [
        userA,
        nonce,
        'devA',
        'sig-placeholder-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxyy',
      ]);
      await c.query('commit');
      return { ok: true };
    } catch (e) {
      await c.query('rollback');
      return { code: e.code, message: e.message };
    } finally {
      c.release();
    }
  };

  const revoke = async () => {
    const c = await pool.connect();
    try {
      await c.query('begin');
      await revokeViaSvc(c, userA, challengeId, sid);
      await c.query('commit');
      return { ok: true };
    } catch (e) {
      await c.query('rollback');
      return { code: e.code, message: e.message };
    } finally {
      c.release();
    }
  };

  const [ra, rb] = await Promise.all([approve(), revoke()]);
  const admin2 = await pool.connect();
  const pending = (
    await admin2.query(
      `select approval_sig_b64, approved_device_id from tharmesh_private.enrollment_pending
       where account_id = $1 and nonce_b64 = $2`,
      [userA, nonce]
    )
  ).rows[0];
  const devices = (
    await admin2.query('select device_id, revoked_at from public.devices where account_id = $1 order by device_id', [
      userA,
    ])
  ).rows;
  const active = devices.filter((d) => d.revoked_at == null);
  await releaseClient(admin2);

  if (ra.ok && rb.ok) {
    assert.ok(pending.approval_sig_b64);
    assert.equal(pending.approved_device_id, 'devA');
    const devA = devices.find((d) => d.device_id === 'devA');
    assert.ok(devA?.revoked_at, 'revoke commits after approval under lock serialization');
  } else if (ra.ok && !rb.ok) {
    assert.ok(pending.approval_sig_b64);
    assert.equal(pending.approved_device_id, 'devA');
    assert.ok(active.some((d) => d.device_id === 'devA'));
  } else if (rb.ok && !ra.ok) {
    assert.match(ra.message ?? '', /Trusted device|not found|42501/i);
    const devA = devices.find((d) => d.device_id === 'devA');
    assert.ok(devA?.revoked_at);
    assert.equal(pending.approval_sig_b64, null);
  } else {
    assert.fail(`unexpected race outcome approve=${JSON.stringify(ra)} revoke=${JSON.stringify(rb)}`);
  }
  assert.ok(active.length >= 1, 'at least one active device remains');
  if (pending.approval_sig_b64 && ra.ok && !rb.ok) {
    const approver = devices.find((d) => d.device_id === pending.approved_device_id);
    assert.ok(approver && approver.revoked_at == null, 'approval committed without following revoke');
  }
}

test('approve vs revoke device A (three active devices)', async () => {
  ensureDb(DB);
  const pool = createTestPool({ database: DB });
  try {
    await raceScenario(pool, { onlyTwoDevices: false });
  } finally {
    await pool.end();
  }
});

test('approve vs revoke device A with only two active devices', async () => {
  ensureDb(DB_TWO);
  const pool = createTestPool({ database: DB_TWO });
  try {
    await raceScenario(pool, { onlyTwoDevices: true });
  } finally {
    await pool.end();
  }
});
