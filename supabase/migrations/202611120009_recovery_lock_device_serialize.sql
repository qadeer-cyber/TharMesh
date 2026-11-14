-- 0.6 P0: deny direct recovery_blobs writes; serialize device mutations; action-scoped sensitive grants.
begin;

-- Recovery: authenticated may SELECT own row only; no direct INSERT/UPDATE/DELETE (PostgREST bypass closed).
drop policy if exists recovery_insert_own on public.recovery_blobs;
drop policy if exists recovery_update_own on public.recovery_blobs;

revoke insert, update, delete on table public.recovery_blobs from anon, authenticated, public;
grant select on table public.recovery_blobs to authenticated;

create or replace function tharmesh_private.account_device_mutation_lock(p_account uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_account is null then
        raise exception 'Account required' using errcode = '42501';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(p_account::text, 0)
    );
end;
$$;

revoke all on function tharmesh_private.account_device_mutation_lock(uuid) from public, anon, authenticated;

create table if not exists tharmesh_private.sensitive_action_grants (
    grant_id uuid primary key default pg_catalog.gen_random_uuid(),
    account_id uuid not null references auth.users (id) on delete cascade,
    action text not null,
    session_sub text,
    auth_amr_ts bigint not null,
    token_iat bigint not null,
    issued_at timestamptz not null default clock_timestamp(),
    expires_at timestamptz not null,
    consumed_at timestamptz
);

create index if not exists sensitive_action_grants_open_idx
    on tharmesh_private.sensitive_action_grants (account_id, action)
    where consumed_at is null;

revoke all on table tharmesh_private.sensitive_action_grants from public, anon, authenticated;

create or replace function public.tharmesh_sensitive_grant_issue_svc(
    p_account uuid,
    p_action text,
    p_session_sub text,
    p_auth_amr_ts bigint,
    p_token_iat bigint,
    p_ttl_seconds integer default 900
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    gid uuid := pg_catalog.gen_random_uuid();
    ttl int := greatest(coalesce(p_ttl_seconds, 900), 60);
begin
    if p_account is null or p_action is null or length(trim(p_action)) = 0
        or p_auth_amr_ts is null or p_auth_amr_ts < 1 or p_token_iat is null then
        raise exception 'Invalid grant request' using errcode = '22023';
    end if;
    if p_token_iat < p_auth_amr_ts then
        raise exception 'Token predates auth event' using errcode = '42501';
    end if;
    insert into tharmesh_private.sensitive_action_grants (
        grant_id, account_id, action, session_sub, auth_amr_ts, token_iat, expires_at
    )
        values (
            gid,
            p_account,
            trim(p_action),
            nullif(trim(p_session_sub), ''),
            p_auth_amr_ts,
            p_token_iat,
            clock_timestamp() + make_interval(secs => ttl)
        );
    insert into tharmesh_private.auth_freshness (account_id, verified_at, auth_amr_ts, token_iat)
        values (p_account, clock_timestamp(), p_auth_amr_ts, p_token_iat)
    on conflict (account_id) do update
        set verified_at = excluded.verified_at,
            auth_amr_ts = excluded.auth_amr_ts,
            token_iat = excluded.token_iat;
    return gid;
end;
$$;

create or replace function public.tharmesh_sensitive_grant_consume_svc(
    p_account uuid,
    p_grant_id uuid,
    p_action text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    row tharmesh_private.sensitive_action_grants%rowtype;
begin
    if p_account is null or p_grant_id is null or p_action is null then
        raise exception 'Invalid grant consume' using errcode = '22023';
    end if;
    select * into row from tharmesh_private.sensitive_action_grants g
        where g.grant_id = p_grant_id and g.account_id = p_account for update;
    if not found or row.consumed_at is not null then
        raise exception 'Grant invalid or consumed' using errcode = '42501';
    end if;
    if row.action <> trim(p_action) then
        raise exception 'Grant action mismatch' using errcode = '42501';
    end if;
    if row.expires_at <= clock_timestamp() then
        raise exception 'Grant expired' using errcode = '42501';
    end if;
    update tharmesh_private.sensitive_action_grants g
        set consumed_at = clock_timestamp()
        where g.grant_id = p_grant_id;
end;
$$;

revoke all on function public.tharmesh_sensitive_grant_issue_svc(uuid, text, text, bigint, bigint, integer)
    from public, anon, authenticated;
grant execute on function public.tharmesh_sensitive_grant_issue_svc(uuid, text, text, bigint, bigint, integer)
    to service_role;

revoke all on function public.tharmesh_sensitive_grant_consume_svc(uuid, uuid, text)
    from public, anon, authenticated;
grant execute on function public.tharmesh_sensitive_grant_consume_svc(uuid, uuid, text)
    to service_role;

create or replace function public.tharmesh_device_revoke(p_device_id text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    active_count int;
    remaining int;
begin
    if caller is null or p_device_id is null or length(trim(p_device_id)) = 0 then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    if not public.tharmesh_auth_fresh_required(900) then
        raise exception 'Fresh auth required' using errcode = '42501';
    end if;
    perform tharmesh_private.account_device_mutation_lock(caller);
    select count(*)::int into active_count
        from public.devices d
        where d.account_id = caller and d.revoked_at is null;
    if active_count <= 1 then
        raise exception 'Last active device cannot be revoked' using errcode = '42501';
    end if;
    update public.devices d
        set revoked_at = clock_timestamp()
        where d.account_id = caller and d.device_id = p_device_id and d.revoked_at is null;
    if not found then
        raise exception 'Device not found or already revoked' using errcode = '42501';
    end if;
    select count(*)::int into remaining
        from public.devices d
        where d.account_id = caller and d.revoked_at is null;
    if remaining < 1 then
        raise exception 'Last active device cannot be revoked' using errcode = '42501';
    end if;
end;
$$;

create or replace function public.tharmesh_enrollment_finish_svc(
    p_account uuid,
    p_nonce_b64 text,
    p_certificate text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    pending tharmesh_private.enrollment_pending%rowtype;
begin
    if p_account is null then
        raise exception 'Account required' using errcode = '42501';
    end if;
    perform tharmesh_private.account_device_mutation_lock(p_account);
    select * into pending from tharmesh_private.enrollment_pending e
        where e.account_id = p_account and e.nonce_b64 = p_nonce_b64 for update;
    if not found then
        raise exception 'Unknown enrollment' using errcode = '42501';
    end if;
    if pending.consumed_at is not null then
        raise exception 'Enrollment already consumed' using errcode = '42501';
    end if;
    if pending.approval_sig_b64 is null then
        raise exception 'Enrollment not approved' using errcode = '42501';
    end if;
    if pending.expires_at <= clock_timestamp() then
        raise exception 'Enrollment expired' using errcode = '42501';
    end if;
    insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
        values (
            p_account,
            pending.device_id,
            pending.mesh_public_key,
            pending.new_device_pubkey,
            coalesce(nullif(trim(p_certificate), ''), 'trusted-enrollment:' || p_nonce_b64)
        );
    update tharmesh_private.enrollment_pending e
        set consumed_at = clock_timestamp()
        where e.account_id = p_account and e.nonce_b64 = p_nonce_b64;
end;
$$;

create or replace function public.tharmesh_bootstrap_device_svc(
    p_account uuid,
    p_device_id text,
    p_device_pubkey text,
    p_challenge uuid,
    p_certificate text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    mesh text;
    row tharmesh_private.bootstrap_device_challenges%rowtype;
begin
    if p_account is null or p_device_id is null or p_device_pubkey is null or p_challenge is null
        or length(p_device_pubkey) < 80 then
        raise exception 'Invalid device fields' using errcode = '22023';
    end if;
    perform tharmesh_private.account_device_mutation_lock(p_account);
    select * into row from tharmesh_private.bootstrap_device_challenges b
        where b.account_id = p_account for update;
    if not found or row.challenge <> p_challenge or row.consumed_at is not null then
        raise exception 'Invalid bootstrap challenge' using errcode = '42501';
    end if;
    if row.expires_at <= clock_timestamp() then
        raise exception 'Bootstrap challenge expired' using errcode = '42501';
    end if;
    select i.mesh_public_key into mesh
        from tharmesh_private.canonical_identities i where i.account_id = p_account;
    if mesh is null then
        raise exception 'Canonical mesh required' using errcode = '42501';
    end if;
    if exists (
        select 1 from public.devices d
        where d.account_id = p_account and d.revoked_at is null
    ) then
        raise exception 'Trusted device already registered' using errcode = '42501';
    end if;
    insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
        values (
            p_account,
            p_device_id,
            mesh,
            p_device_pubkey,
            coalesce(nullif(trim(p_certificate), ''), 'bootstrap-device')
        );
    update tharmesh_private.bootstrap_device_challenges b
        set consumed_at = clock_timestamp()
        where b.account_id = p_account;
end;
$$;

commit;
