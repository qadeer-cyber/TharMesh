/**
 * Atomic recovery_rotate commit: validation, idempotency, grant consumption.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import { blobFields, issueGrant, withSession } from './pg-test-helpers.mjs';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_atomic_recovery';
const userA = 'f1000000-0000-4000-8000-0000000000a1';
const SID = 'sess-recovery-a1';

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: { ...process.env, THARMESH_PG_USER: 'tharmesh_runner', THARMESH_PG_PASSWORD: 'pgtest_local' },
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

function sha256Hex(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

async function grantConsumed(pool, grantId) {
  const c = await pool.connect();
  try {
    await c.query('set role service_role');
    const row = (
      await c.query('select consumed_at from tharmesh_private.sensitive_action_grants where grant_id = $1', [
        grantId,
      ])
    ).rows[0];
    return row?.consumed_at != null;
  } finally {
    c.release();
  }
}

async function commitRotate(client, account, grantId, sessionId, fields) {
  const digest = sha256Hex(fields.cipher);
  const args = [
    account,
    grantId,
    sessionId,
    fields.requestId ?? 'req-id-00000001',
    digest,
    fields.gen,
    'scrypt',
    16384,
    8,
    1,
    fields.salt,
    fields.iv,
    fields.cipher,
  ];
  return client.query(
    'select public.tharmesh_recovery_rotate_commit_svc($1::uuid,$2::uuid,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) as g',
    args
  );
}

test('recovery grant atomic and idempotent', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const admin = await pool.connect();
  try {
    await admin.query('reset role');
    await admin.query('insert into auth.users values ($1, now()) on conflict do nothing', [userA]);
    const grant = await issueGrant(admin, userA, 'recovery_rotate', SID);

    await withSession(admin, userA, SID, async () => {
      const bad = blobFields(userA, 0, 'cipher-a');
      await assert.rejects(
        async () => {
          const digest = sha256Hex(bad.cipher);
          await admin.query(
            'select public.tharmesh_recovery_rotate_commit_svc($1::uuid,$2::uuid,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) as g',
            [
              userA,
              grant,
              SID,
              'req-bad-body-01',
              'deadbeef',
              0,
              'scrypt',
              16384,
              8,
              1,
              bad.salt,
              bad.iv,
              bad.cipher,
            ]
          );
          void digest;
        },
        (e) => e.code === '22023'
      );
    });
    assert.equal(await grantConsumed(pool, grant), false);

    const grant2 = await issueGrant(admin, userA, 'recovery_rotate', SID);
    await admin.query('set role service_role');
    await admin.query(
      `insert into public.recovery_blobs (account_id, kdf, n, r, p, salt_b64, iv_b64, ciphertext_b64, generation)
       values ($1,'scrypt',16384,8,1,'c2FsdA==','aXY=','already-there',1)`,
      [userA]
    );
    await withSession(admin, userA, SID, async () => {
      const fields = blobFields(userA, 0, 'cipher-new');
      await assert.rejects(
        () => commitRotate(admin, userA, grant2, SID, { ...fields, requestId: 'req-cas-fail-01' }),
        (e) => e.code === '23505'
      );
    });
    assert.equal(await grantConsumed(pool, grant2), false);
    const still = (
      await admin.query('select ciphertext_b64 from public.recovery_blobs where account_id = $1', [userA])
    ).rows[0].ciphertext_b64;
    assert.equal(still, 'already-there');

    const grant3 = await issueGrant(admin, userA, 'recovery_rotate', SID);
    const req = 'req-success-00001';
    const fields = { ...blobFields(userA, 1, 'cipher-v2'), requestId: req };
    let gen;
    await withSession(
      admin,
      userA,
      SID,
      async () => {
        gen = (await commitRotate(admin, userA, grant3, SID, fields)).rows[0].g;
      },
      { commit: true }
    );
    assert.equal(gen, 2);
    assert.equal(await grantConsumed(pool, grant3), true);

    const grant4 = await issueGrant(admin, userA, 'recovery_rotate', SID);
    let replay;
    await withSession(admin, userA, SID, async () => {
      replay = (await commitRotate(admin, userA, grant4, SID, fields)).rows[0].g;
    }, { commit: true });
    assert.equal(replay, 2);
    assert.equal(await grantConsumed(pool, grant4), false);

    await admin.query('delete from public.recovery_blobs where account_id = $1', [userA]);
    const gA = await issueGrant(admin, userA, 'recovery_rotate', SID);
    const gB = await issueGrant(admin, userA, 'recovery_rotate', SID);
    const fields0 = { ...blobFields(userA, 0, 'race-cipher-a'), requestId: 'req-race-a-0001' };
    const fieldsB = { ...blobFields(userA, 0, 'race-cipher-b'), requestId: 'req-race-b-0001' };
    const pool2 = new Pool({
      database: DB,
      user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
      password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
      host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
    });
    const attempt = async (grantId, fields) => {
      const c = await pool2.connect();
      try {
        await withSession(
          c,
          userA,
          SID,
          () => commitRotate(c, userA, grantId, SID, fields),
          { commit: true }
        );
        return { ok: true };
      } catch (e) {
        return { code: e.code };
      } finally {
        c.release();
      }
    };
    const [ra, rb] = await Promise.all([attempt(gA, fields0), attempt(gB, fieldsB)]);
    const wins = [ra, rb].filter((r) => r.ok).length;
    assert.equal(wins, 1);
    assert.equal(
      (await admin.query('select count(*)::int as c from public.recovery_blobs where account_id = $1', [userA])).rows[0]
        .c,
      1
    );
    await pool2.end();
  } finally {
    admin.release();
    await pool.end();
  }
});
