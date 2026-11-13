export async function withSession(client, userId, sessionId, fn, { commit = false } = {}) {
  await client.query('begin');
  try {
    await client.query('set local role authenticated');
    await client.query("select set_config('request.jwt.claim.sub', $1, true)", [userId]);
    if (sessionId) {
      await client.query("select set_config('request.jwt.claim.session_id', $1, true)", [sessionId]);
    }
    const result = await fn();
    if (commit) await client.query('commit');
    else await client.query('rollback');
    return result;
  } catch (e) {
    await client.query('rollback');
    throw e;
  }
}

export async function issueGrant(client, account, action, sessionId) {
  const ts = Math.floor(Date.now() / 1000);
  await client.query('reset role');
  await client.query('set role service_role');
  return (
    await client.query(
      'select public.tharmesh_sensitive_grant_issue_svc($1::uuid,$2,$3,$4::bigint,$5::bigint,$6) as g',
      [account, action, sessionId, ts, ts, 900]
    )
  ).rows[0].g;
}

export function blobFields(account, gen, cipher = 'cipher-a') {
  const ts = Math.floor(Date.now() / 1000);
  return {
    kdf: 'scrypt',
    n: 16384,
    r: 8,
    p: 1,
    salt: 'c2FsdA==',
    iv: 'aXY=',
    cipher,
    gen,
    hash: null,
    account,
    ts,
  };
}
