/**
 * Exploit regression: authenticated role must not write recovery_blobs directly.
 * local Postgres, not hosted Supabase
 */
import assert from 'node:assert/strict';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { Pool } = require('/tmp/tharmesh-pg-tests/node_modules/pg');
const DB = process.env.THARMESH_PG_TEST_DB ?? 'tharmesh_pgtest_bypass';
const userA = 'f1000000-0000-4000-8000-0000000000a1';
const userB = 'f1000000-0000-4000-8000-0000000000b2';

function ensureDb() {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'postgres-apply.sh');
  const r = spawnSync('bash', [script, DB], {
    encoding: 'utf8',
    env: process.env,
  });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'postgres-apply failed');
}

async function withJwt(client, userId, fn) {
  await client.query('begin');
  try {
    await client.query('set local role authenticated');
    await client.query("select set_config('request.jwt.claim.sub', $1, true)", [userId]);
    await fn();
    await client.query('rollback');
  } catch (e) {
    await client.query('rollback');
    throw e;
  }
}

test('recovery_blobs direct-write bypass closed', async () => {
  ensureDb();
  const pool = new Pool({
    database: DB,
    user: process.env.THARMESH_PG_USER ?? 'tharmesh_runner',
    password: process.env.THARMESH_PG_PASSWORD ?? 'pgtest_local',
    host: process.env.THARMESH_PG_HOST ?? '127.0.0.1',
  });
  const client = await pool.connect();
  try {
    await client.query('reset role');
    await client.query('insert into auth.users values ($1, now()), ($2, now()) on conflict do nothing', [
      userA,
      userB,
    ]);

    await withJwt(client, userA, async () => {
      await assert.rejects(
        () =>
          client.query(
            `insert into public.recovery_blobs (account_id, kdf, n, r, p, salt_b64, iv_b64, ciphertext_b64, generation)
             values ($1::uuid, 'scrypt', 16384, 8, 1, 's', 'i', 'c', 1)`,
            [userA]
          ),
        (e) =>
          e.code === '42501' ||
          e.code === '0A000' ||
          e.code === '42501' ||
          String(e.message ?? '').toLowerCase().includes('permission denied')
      );
    });

    await client.query('set role service_role');
    await client.query(
      `select public.tharmesh_recovery_put_svc($1::uuid, 0, 'scrypt', 16384, 8, 1, 's', 'i', 'c')`,
      [userA]
    );

    await withJwt(client, userA, async () => {
      const row = (
        await client.query('select generation, ciphertext_b64 from public.recovery_blobs where account_id = $1', [
          userA,
        ])
      ).rows[0];
      assert.equal(row.generation, 1);
      assert.equal(row.ciphertext_b64, 'c');
    });
    await withJwt(client, userA, async () => {
      await assert.rejects(
        () =>
          client.query(
            `update public.recovery_blobs set ciphertext_b64 = 'evil' where account_id = $1`,
            [userA]
          ),
        (e) => e.code === '42501' || e.message?.includes('permission denied')
      );
    });
    await withJwt(client, userA, async () => {
      await assert.rejects(
        () => client.query(`delete from public.recovery_blobs where account_id = $1`, [userA]),
        (e) => e.code === '42501' || e.message?.includes('permission denied')
      );
    });

    await withJwt(client, userB, async () => {
      const rows = (
        await client.query('select count(*)::int as c from public.recovery_blobs where account_id = $1', [userB])
      ).rows[0].c;
      assert.equal(rows, 0);
    });
  } finally {
    client.release();
    await pool.end();
  }
});
