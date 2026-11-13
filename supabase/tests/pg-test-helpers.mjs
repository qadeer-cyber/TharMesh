import { createSign } from 'node:crypto';

export async function withSession(client, userId, sessionId, fn, { commit = false, claimsOnly = false } = {}) {
  await client.query('begin');
  try {
    await client.query('set local role authenticated');
    if (claimsOnly && sessionId) {
      await client.query("select set_config('request.jwt.claims', $1, true)", [
        JSON.stringify({ sub: userId, session_id: sessionId, role: 'authenticated' }),
      ]);
    } else {
      await client.query("select set_config('request.jwt.claim.sub', $1, true)", [userId]);
      if (sessionId) {
        await client.query("select set_config('request.jwt.claim.session_id', $1, true)", [sessionId]);
      }
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

export function revokeChallengeMsg(account, sessionId, requestingId, targetId, grantId, challengeId) {
  return Buffer.from(
    [
      'TM-DEVICE-REVOKE-CHALLENGE-v1',
      account,
      sessionId,
      requestingId,
      targetId,
      grantId,
      challengeId,
    ].join('\x1F'),
    'utf8'
  );
}

export function signDer(privKey, msg) {
  const s = createSign('SHA256');
  s.update(msg);
  s.end();
  return Buffer.from(s.sign({ key: privKey, dsaEncoding: 'der' }))
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
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

export async function issueRevokeChallenge(client, account, sessionId, requestingId, targetId, grantId) {
  await client.query('reset role');
  await client.query('set role service_role');
  return (
    await client.query(
      'select public.tharmesh_device_revoke_challenge_issue_svc($1::uuid,$2,$3,$4,$5::uuid) as c',
      [account, sessionId, requestingId, targetId, grantId]
    )
  ).rows[0].c;
}

export async function revokeViaSvc(client, account, challengeId) {
  await client.query('reset role');
  await client.query('set role service_role');
  await client.query('select public.tharmesh_device_revoke_svc($1::uuid,$2::uuid)', [account, challengeId]);
}

export async function revokeWithChallenge(
  client,
  account,
  sessionId,
  requestingId,
  targetId,
  grantId
) {
  const challengeId = await issueRevokeChallenge(client, account, sessionId, requestingId, targetId, grantId);
  await revokeViaSvc(client, account, challengeId);
  return challengeId;
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
