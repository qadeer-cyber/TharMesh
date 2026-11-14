-- 0.6: server-issued single-use revoke challenges; DB-bound possession; svc commit txn.
begin;

create table if not exists tharmesh_private.device_revoke_challenges (
    challenge_id uuid primary key default gen_random_uuid(),
    account_id uuid not null references auth.users (id) on delete cascade,
    session_id text not null,
    requesting_device_id text not null,
    target_device_id text not null,
    grant_id uuid not null references tharmesh_private.sensitive_action_grants (grant_id),
    expires_at timestamptz not null,
    consumed_at timestamptz,
    issued_at timestamptz not null default clock_timestamp()
);

create index if not exists device_revoke_challenges_account_idx
    on tharmesh_private.device_revoke_challenges (account_id, consumed_at);

revoke all on table tharmesh_private.device_revoke_challenges from public, anon, authenticated;

drop function if exists public.tharmesh_device_revoke_svc(uuid, text, uuid, text, text, bigint);

create or replace function public.tharmesh_device_revoke_challenge_issue_svc(
    p_account uuid,
    p_session_id text,
    p_requesting_device_id text,
    p_target_device_id text,
    p_grant_id uuid
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    gid uuid := pg_catalog.gen_random_uuid();
    grant_row tharmesh_private.sensitive_action_grants%rowtype;
begin
    if p_account is null or p_session_id is null or length(trim(p_session_id)) < 8
        or p_requesting_device_id is null or length(trim(p_requesting_device_id)) < 1
        or p_target_device_id is null or length(trim(p_target_device_id)) < 1
        or p_grant_id is null then
        raise exception 'Invalid challenge request' using errcode = '22023';
    end if;
    if trim(p_requesting_device_id) = trim(p_target_device_id) then
        raise exception 'Cannot revoke requesting device' using errcode = '22023';
    end if;
    select * into grant_row from tharmesh_private.sensitive_action_grants g
        where g.grant_id = p_grant_id and g.account_id = p_account;
    if not found or grant_row.consumed_at is not null then
        raise exception 'Grant invalid or consumed' using errcode = '42501';
    end if;
    if grant_row.action <> 'device_revoke' then
        raise exception 'Grant action mismatch' using errcode = '42501';
    end if;
    if grant_row.session_id is distinct from trim(p_session_id) then
        raise exception 'Grant session mismatch' using errcode = '42501';
    end if;
    if grant_row.expires_at <= clock_timestamp() then
        raise exception 'Grant expired' using errcode = '42501';
    end if;
    if not exists (
        select 1 from public.devices d
        where d.account_id = p_account and d.device_id = trim(p_requesting_device_id) and d.revoked_at is null
    ) then
        raise exception 'Requesting device not active' using errcode = '42501';
    end if;
    insert into tharmesh_private.device_revoke_challenges (
        challenge_id, account_id, session_id, requesting_device_id, target_device_id, grant_id, expires_at
    )
        values (
            gid,
            p_account,
            trim(p_session_id),
            trim(p_requesting_device_id),
            trim(p_target_device_id),
            p_grant_id,
            clock_timestamp() + interval '120 seconds'
        );
    return gid;
end;
$$;

create or replace function public.tharmesh_device_pubkey_lookup_svc(
    p_account uuid,
    p_device_id text
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    pub text;
begin
    select d.device_public_key into pub
        from public.devices d
        where d.account_id = p_account and d.device_id = trim(p_device_id);
    if pub is null then
        raise exception 'Device not found' using errcode = '42501';
    end if;
    return pub;
end;
$$;

create or replace function public.tharmesh_device_revoke_challenge_get_svc(
    p_challenge_id uuid
)
returns table (
    account_id uuid,
    session_id text,
    requesting_device_id text,
    target_device_id text,
    grant_id uuid,
    expires_at timestamptz,
    consumed_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    return query
        select
            c.account_id,
            c.session_id,
            c.requesting_device_id,
            c.target_device_id,
            c.grant_id,
            c.expires_at,
            c.consumed_at
        from tharmesh_private.device_revoke_challenges c
        where c.challenge_id = p_challenge_id;
end;
$$;

create or replace function public.tharmesh_device_revoke_svc(
    p_account uuid,
    p_challenge_id uuid
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    ch tharmesh_private.device_revoke_challenges%rowtype;
    grant_row tharmesh_private.sensitive_action_grants%rowtype;
    active_count int;
    remaining int;
begin
    if p_account is null or p_challenge_id is null then
        raise exception 'Invalid revoke request' using errcode = '22023';
    end if;

    perform tharmesh_private.account_device_mutation_lock(p_account);

    select * into ch from tharmesh_private.device_revoke_challenges c
        where c.challenge_id = p_challenge_id and c.account_id = p_account for update;
    if not found then
        raise exception 'Challenge not found' using errcode = '42501';
    end if;
    if ch.consumed_at is not null then
        raise exception 'Challenge already consumed' using errcode = '42501';
    end if;
    if ch.expires_at <= clock_timestamp() then
        raise exception 'Challenge expired' using errcode = '42501';
    end if;

    if not exists (
        select 1 from public.devices d
        where d.account_id = p_account
          and d.device_id = ch.requesting_device_id
          and d.revoked_at is null
    ) then
        raise exception 'Requesting device not active' using errcode = '42501';
    end if;

    update tharmesh_private.device_revoke_challenges c
        set consumed_at = clock_timestamp()
        where c.challenge_id = p_challenge_id and c.consumed_at is null;
    if not found then
        raise exception 'Challenge already consumed' using errcode = '42501';
    end if;

    select * into grant_row from tharmesh_private.sensitive_action_grants g
        where g.grant_id = ch.grant_id and g.account_id = p_account for update;
    if not found or grant_row.consumed_at is not null then
        raise exception 'Grant invalid or consumed' using errcode = '42501';
    end if;
    if grant_row.action <> 'device_revoke' then
        raise exception 'Grant action mismatch' using errcode = '42501';
    end if;
    if grant_row.session_id is distinct from ch.session_id then
        raise exception 'Grant session mismatch' using errcode = '42501';
    end if;
    if grant_row.expires_at <= clock_timestamp() then
        raise exception 'Grant expired' using errcode = '42501';
    end if;

    select count(*)::int into active_count
        from public.devices d
        where d.account_id = p_account and d.revoked_at is null;
    if active_count <= 1 then
        raise exception 'Last active device cannot be revoked' using errcode = '42501';
    end if;

    update public.devices d
        set revoked_at = clock_timestamp()
        where d.account_id = p_account
          and d.device_id = ch.target_device_id
          and d.revoked_at is null;
    if not found then
        raise exception 'Device not found or already revoked' using errcode = '42501';
    end if;

    select count(*)::int into remaining
        from public.devices d
        where d.account_id = p_account and d.revoked_at is null;
    if remaining < 1 then
        raise exception 'Last active device cannot be revoked' using errcode = '42501';
    end if;

    update tharmesh_private.sensitive_action_grants g
        set consumed_at = clock_timestamp()
        where g.grant_id = ch.grant_id;
end;
$$;

revoke all on function public.tharmesh_device_revoke_challenge_issue_svc(uuid, text, text, text, uuid)
    from public, anon, authenticated;
grant execute on function public.tharmesh_device_revoke_challenge_issue_svc(uuid, text, text, text, uuid)
    to service_role;

revoke all on function public.tharmesh_device_pubkey_lookup_svc(uuid, text)
    from public, anon, authenticated;
grant execute on function public.tharmesh_device_pubkey_lookup_svc(uuid, text) to service_role;

revoke all on function public.tharmesh_device_revoke_challenge_get_svc(uuid)
    from public, anon, authenticated;
grant execute on function public.tharmesh_device_revoke_challenge_get_svc(uuid) to service_role;

revoke all on function public.tharmesh_device_revoke_svc(uuid, uuid)
    from public, anon, authenticated;
grant execute on function public.tharmesh_device_revoke_svc(uuid, uuid) to service_role;

commit;
