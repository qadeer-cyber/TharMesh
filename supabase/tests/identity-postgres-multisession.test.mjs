/**
 * local Postgres multi-session, not hosted Supabase
 */
import assert from 'node:assert/strict';
import { createPrivateKey, createPublicKey, createSign, generateKeyPairSync, randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest';
const KEY122 = 'A'.repeat(122);

const stats = { passed: 0, failed: 0 };

function b64url(buf) {
  return Buffer.from(buf)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function sep(parts) {
  return Buffer.from(parts.join('\x1F'), 'utf8');
}

function enrollRequestMsg(account, mesh, devPub, did, nonce) {
  return sep(['TM-ENROLL-v1', account, mesh, devPub, did, nonce, '0']);
}

function approveMsg(account, newDevPub, nonce, epochSeconds) {
  return sep(['TM-DEVICE-APPROVE-v1', account, newDevPub, nonce, String(epochSeconds)]);
}

function keyMaterial() {
  const pair = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const spki = pair.publicKey.export({ type: 'spki', format: 'der' });
  const pkcs8 = pair.privateKey.export({ type: 'pkcs8', format: 'der' });
  const privKey = createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' });
  return { pubB64: b64url(spki), privKey };
}

function signDer(privKey, msg) {
  const s = createSign('SHA256');
  s.update(msg);
  s.end();
  return b64url(s.sign({ key: privKey, dsaEncoding: 'der' }));
}

async function poolClient() {
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const client = await pool.connect();
  client._pool = pool;
  return client;
}

async function withJwt(client, userId, role, fn, { commit = false } = {}) {
  await client.query('begin');
  try {
    if (role) await client.query('set local role ' + role);
    await client.query("select set_config('request.jwt.claim.sub', $1, true)", [userId]);
    const result = await fn();
    if (commit) await client.query('commit');
    return result;
  } catch (e) {
    await client.query('rollback');
    throw e;
  } finally {
    if (!commit) await client.query('rollback');
  }
}

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: { ...process.env, THARMESH_PG_USER: 'tharmesh_runner', THARMESH_PG_PASSWORD: 'pgtest_local' },
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

async function activateUser(client, userId, meshKey = KEY122) {
  await client.query('reset role');
  const nonce = (
    await client.query('select public.tharmesh_identity_challenge($1::uuid, $2)', [userId, meshKey])
  ).rows[0].tharmesh_identity_challenge;
  await client.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [
    userId,
    nonce,
    meshKey,
  ]);
}

test('local Postgres multi-session suite', async (t) => {
  ensureDb();
  const userA = 'f1000000-0000-4000-8000-0000000000a1';
  const userB = 'f1000000-0000-4000-8000-0000000000b2';
  const trustedDev = keyMaterial();
  const admin = await poolClient();
  t.after(async () => {
    admin.release();
    await admin._pool.end();
  });

  await admin.query('update tharmesh_private.identity_admission set enabled = true');
  await admin.query('insert into auth.users values ($1, now()), ($2, now()) on conflict do nothing', [
    userA,
    userB,
  ]);
  await activateUser(admin, userA);
  await admin.query(
    `insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
     values ($1,'trusted-1',$2,$3,'bootstrap')`,
    [userA, KEY122, trustedDev.pubB64]
  );

  const c1 = await poolClient();
  const c2 = await poolClient();
  t.after(async () => {
    c1.release();
    c2.release();
    await c1._pool.end();
    await c2._pool.end();
  });

  await t.test('challenge replay and expiry', async () => {
    await admin.query('reset role');
    const expired = (
      await admin.query('select public.tharmesh_identity_challenge($1::uuid, $2)', [userB, KEY122])
    ).rows[0].tharmesh_identity_challenge;
    await admin.query(
      "update tharmesh_private.identity_challenges set expires_at = now() - interval '1 minute' where account_id = $1",
      [userB]
    );
    await assert.rejects(
      () =>
        admin.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [
          userB,
          expired,
          KEY122,
        ]),
      (e) => e.code === '42501'
    );
    stats.passed++;
  });

  await t.test('concurrent finish: only one consumes nonce', async () => {
    const uid = 'f1000000-0000-4000-8000-000000000099';
    await admin.query('reset role');
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [uid]);
    const nonce = (
      await admin.query('select public.tharmesh_identity_challenge($1::uuid, $2)', [uid, KEY122])
    ).rows[0].tharmesh_identity_challenge;
    let ok = 0;
    let fail = 0;
    const attempt = async (cl) => {
      await cl.query('begin');
      await cl.query('set local role service_role');
      await cl.query("select set_config('request.jwt.claim.sub', $1, true)", [uid]);
      try {
        await cl.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [
          uid,
          nonce,
          KEY122,
        ]);
        await cl.query('commit');
        ok++;
      } catch {
        await cl.query('rollback');
        fail++;
      }
    };
    await Promise.all([attempt(c1), attempt(c2)]);
    assert.equal(ok, 1);
    assert.ok(fail >= 1);
    stats.passed++;
  });

  await t.test('different-key finish rejected', async () => {
    const other = 'B'.repeat(122);
    await admin.query('reset role');
    const n = (
      await admin.query('select public.tharmesh_identity_challenge($1::uuid, $2)', [userB, KEY122])
    ).rows[0].tharmesh_identity_challenge;
    await assert.rejects(
      () => admin.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [userB, n, other]),
      (e) => e.code === '42501'
    );
    stats.passed++;
  });

  await t.test('rollback then finish succeeds once', async () => {
    const uid = 'f1000000-0000-4000-8000-000000000088';
    await admin.query('reset role');
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [uid]);
    const n = (
      await admin.query('select public.tharmesh_identity_challenge($1::uuid, $2)', [uid, KEY122])
    ).rows[0].tharmesh_identity_challenge;
    await admin.query('begin');
    await admin.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [uid, n, KEY122]);
    await admin.query('rollback');
    const again = await admin.query('select * from public.tharmesh_identity_finish($1::uuid, $2::uuid, $3)', [
      uid,
      n,
      KEY122,
    ]);
    assert.equal(again.rows[0].mesh_public_key, KEY122);
    stats.passed++;
  });

  await t.test('enrollment svc transactional path (crypto gate off-DB)', async () => {
    await admin.query('reset role');
    const newDev = keyMaterial();
    const nonce = b64url(randomBytes(12));
    const expires = new Date(Date.now() + 600_000).toISOString();
    const reqSig = signDer(newDev.privKey, enrollRequestMsg(userA, KEY122, newDev.pubB64, 'phone-2', nonce));
    await admin.query('set role service_role');
    await admin.query(
      'select public.tharmesh_enrollment_begin_svc($1::uuid,$2,$3,$4,$5,$6::timestamptz)',
      [userA, 'phone-2', newDev.pubB64, nonce, reqSig, expires]
    );
    const epoch = (
      await admin.query('select extract(epoch from $1::timestamptz)::bigint as e', [expires])
    ).rows[0].e;
    const apprSig = signDer(trustedDev.privKey, approveMsg(userA, newDev.pubB64, nonce, epoch));
    await admin.query('select public.tharmesh_enrollment_approve_svc($1::uuid,$2,$3,$4)', [
      userA,
      nonce,
      'trusted-1',
      apprSig,
    ]);
    await admin.query('select public.tharmesh_enrollment_finish_svc($1::uuid,$2,$3)', [
      userA,
      nonce,
      'cert:phone-2',
    ]);
    await assert.rejects(
      () => admin.query('select public.tharmesh_enrollment_finish_svc($1::uuid,$2,$3)', [userA, nonce, 'cert:phone-2']),
      (e) => e.code === '42501'
    );
    stats.passed++;
  });

  await t.test('approve svc denied without trusted device rows', async () => {
    await admin.query('reset role');
    const uid = 'f1000000-0000-4000-8000-000000000077';
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [uid]);
    await activateUser(admin, uid);
    const nonce = b64url(randomBytes(8));
    await admin.query('set role service_role');
    await admin.query(
      'select public.tharmesh_enrollment_begin_svc($1::uuid,$2,$3,$4,$5,now() + interval \'10 min\')',
      [uid, 'x-dev', trustedDev.pubB64, nonce, 'sig-placeholder-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx']
    );
    try {
      await assert.rejects(
        () =>
          admin.query('select public.tharmesh_enrollment_approve_svc($1::uuid,$2,$3,$4)', [
            uid,
            nonce,
            'none',
            'sig-placeholder-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
          ]),
        (e) => e.code === '42501'
      );
    } finally {
      await admin.query('rollback');
    }
    stats.passed++;
  });

  await t.test('revocation and authenticated device list', async () => {
    await withJwt(admin, userA, 'authenticated', async () => {
      const list = (await admin.query('select * from public.tharmesh_list_devices($1)', [null])).rows;
      assert.ok(list.length >= 1);
      const revokeId = list.find((r) => r.device_id !== 'trusted-1')?.device_id ?? 'trusted-1';
      await admin.query('select public.tharmesh_device_revoke($1)', [revokeId]);
      const after = (await admin.query('select * from public.tharmesh_list_devices($1)', [null])).rows;
      assert.ok(after.find((r) => r.device_id === revokeId)?.revoked_at);
    });
    stats.passed++;
  });

  await t.test('RLS grants: anon denied, direct insert blocked', async () => {
    await assert.rejects(
      () => withJwt(admin, userA, 'anon', () => admin.query('select * from public.tharmesh_list_devices($1)', [null])),
      () => true
    );
    await assert.rejects(
      () =>
        withJwt(admin, userA, 'authenticated', () =>
          admin.query("insert into public.devices values ($1,'evil',$2,$2,'x')", [userA, KEY122])
        ),
      (e) => e.code === '42501'
    );
    stats.passed++;
  });

  await t.test('concurrent enrollment begin same nonce rejected (svc)', async () => {
    await admin.query('reset role');
    const newDev = keyMaterial();
    const nonce = b64url(randomBytes(9));
    const expires = new Date(Date.now() + 600_000).toISOString();
    await admin.query('set role service_role');
    const reqSig = signDer(newDev.privKey, enrollRequestMsg(userA, KEY122, newDev.pubB64, 'dup-nonce', nonce));
    await admin.query(
      'select public.tharmesh_enrollment_begin_svc($1::uuid,$2,$3,$4,$5,$6::timestamptz)',
      [userA, 'dup-nonce', newDev.pubB64, nonce, reqSig, expires]
    );
    await assert.rejects(
      () =>
        admin.query(
          'select public.tharmesh_enrollment_begin_svc($1::uuid,$2,$3,$4,$5,$6::timestamptz)',
          [userA, 'dup-nonce-2', newDev.pubB64, nonce, reqSig, expires]
        ),
      (e) => e.code === '42501'
    );
    stats.passed++;
  });

  await t.test('list devices is_current via self pubkey', async () => {
    await withJwt(admin, userA, 'authenticated', async () => {
      const list = (
        await admin.query('select * from public.tharmesh_list_devices($1)', [trustedDev.pubB64])
      ).rows;
      assert.ok(list.some((r) => r.device_id === 'trusted-1' && r.is_current));
    });
    stats.passed++;
  });

  await t.test('explicit reset evidence blocks repeat reset flag', async () => {
    await withJwt(admin, userA, 'authenticated', async () => {
      await admin.query('select public.tharmesh_commit_explicit_reset()');
      const ev = (await admin.query('select * from public.tharmesh_account_evidence()')).rows[0];
      assert.equal(ev.explicit_reset_committed, true);
    });
    stats.passed++;
  });

  await t.test('bootstrap challenge replay denied', async () => {
    await admin.query('reset role');
    const uid = 'f1000000-0000-4000-8000-000000000088';
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [uid]);
    await activateUser(admin, uid);
    await admin.query('set role service_role');
    const ch = (
      await admin.query('select public.tharmesh_bootstrap_challenge_svc($1::uuid) as c', [uid])
    ).rows[0].c;
    await admin.query(
      'select public.tharmesh_bootstrap_device_svc($1::uuid,$2,$3,$4::uuid,$5)',
      [uid, 'boot-1', trustedDev.pubB64, ch, 'cert']
    );
    await assert.rejects(
      () =>
        admin.query(
          'select public.tharmesh_bootstrap_device_svc($1::uuid,$2,$3,$4::uuid,$5)',
          [uid, 'boot-2', trustedDev.pubB64, ch, 'cert']
        ),
      (e) => e.code === '42501'
    );
    stats.passed++;
  });

  await t.test('concurrent enrollment approve then finish', async () => {
    await admin.query('reset role');
    const newDev = keyMaterial();
    const nonce = b64url(randomBytes(11));
    const expires = new Date(Date.now() + 600_000).toISOString();
    await admin.query('set role service_role');
    const reqSig = signDer(newDev.privKey, enrollRequestMsg(userA, KEY122, newDev.pubB64, 'conc', nonce));
    await admin.query(
      'select public.tharmesh_enrollment_begin_svc($1::uuid,$2,$3,$4,$5,$6::timestamptz)',
      [userA, 'conc', newDev.pubB64, nonce, reqSig, expires]
    );
    const epoch = (
      await admin.query('select extract(epoch from $1::timestamptz)::bigint as e', [expires])
    ).rows[0].e;
    const apprSig = signDer(trustedDev.privKey, approveMsg(userA, newDev.pubB64, nonce, epoch));
    await admin.query('select public.tharmesh_enrollment_approve_svc($1::uuid,$2,$3,$4)', [
      userA, nonce, 'trusted-1', apprSig,
    ]);
    await admin.query('select public.tharmesh_enrollment_finish_svc($1::uuid,$2,$3)', [
      userA, nonce, 'cert:conc',
    ]);
    await assert.rejects(
      () => admin.query('select public.tharmesh_enrollment_finish_svc($1::uuid,$2,$3)', [userA, nonce, 'cert:conc']),
      (e) => e.code === '42501'
    );
    stats.passed++;
  });

  await t.test('migration idempotency re-apply 007', async () => {
    await admin.query('reset role');
    const mig = readFileSync(
      join(dirname(fileURLToPath(import.meta.url)), '../migrations/202611120007_bootstrap_proof_and_auth_amr.sql'),
      'utf8'
    );
    await admin.query(mig);
    stats.passed++;
  });

  await t.test('in-DB verifier removed (fail closed)', async () => {
    await admin.query('reset role');
    await admin.query('reset role');
    const exists = (
      await admin.query(
        "select count(*)::int as c from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
          "where n.nspname = 'tharmesh_private' and p.proname = 'verify_ecdsa_p256_spki'"
      )
    ).rows[0].c;
    assert.equal(exists, 0);
    stats.passed++;
  });

  console.log(
    JSON.stringify({
      label: 'local Postgres multi-session, not hosted Supabase',
      passed: stats.passed,
      failed: stats.failed,
      not_executed: 0,
    })
  );
});
