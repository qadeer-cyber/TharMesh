-- Secure first-device bootstrap (challenge + consumed proof) and AMR-based fresh auth.
begin;

create table if not exists tharmesh_private.bootstrap_device_challenges (
    account_id uuid primary key references auth.users (id) on delete cascade,
    challenge uuid not null,
    expires_at timestamptz not null,
    consumed_at timestamptz
);

revoke all on tharmesh_private.bootstrap_device_challenges from public, anon, authenticated;

create or replace function public.tharmesh_bootstrap_challenge_svc(p_account uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
    ch uuid := pg_catalog.gen_random_uuid();
begin
    if p_account is null then
        raise exception 'Account required' using errcode = '42501';
    end if;
    if exists (
        select 1 from public.devices d
        where d.account_id = p_account and d.revoked_at is null
    ) then
        raise exception 'Trusted device already registered' using errcode = '42501';
    end if;
    if not exists (
        select 1 from tharmesh_private.canonical_identities i
        where i.account_id = p_account and i.mesh_public_key is not null
    ) then
        raise exception 'Canonical mesh required' using errcode = '42501';
    end if;
    insert into tharmesh_private.bootstrap_device_challenges (account_id, challenge, expires_at)
        values (p_account, ch, clock_timestamp() + interval '5 minutes')
    on conflict (account_id) do update
        set challenge = excluded.challenge,
            expires_at = excluded.expires_at,
            consumed_at = null;
    return ch;
end;
$$;

drop function if exists public.tharmesh_bootstrap_device_svc(uuid, text, text, text);

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

create or replace function public.tharmesh_enrollment_pending_get_svc(
    p_account uuid,
    p_nonce_b64 text
)
returns table (
    mesh_public_key text,
    new_device_pubkey text,
    device_id text,
    expires_at timestamptz,
    approval_sig_b64 text,
    approved_device_id text,
    request_sig_b64 text
)
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_account is null or p_nonce_b64 is null then
        raise exception 'Invalid request' using errcode = '22023';
    end if;
    return query
        select e.mesh_public_key, e.new_device_pubkey, e.device_id, e.expires_at,
               e.approval_sig_b64, e.approved_device_id, e.request_sig_b64
        from tharmesh_private.enrollment_pending e
        where e.account_id = p_account and e.nonce_b64 = p_nonce_b64 and e.consumed_at is null;
end;
$$;

alter table tharmesh_private.auth_freshness
    add column if not exists auth_amr_ts bigint;

do $$ begin
    alter table tharmesh_private.auth_freshness rename column session_iat to token_iat;
exception
    when undefined_column then null;
end $$;

create or replace function public.tharmesh_auth_fresh_record_svc(
    p_account uuid,
    p_auth_amr_ts bigint,
    p_token_iat bigint
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_account is null or p_auth_amr_ts is null or p_auth_amr_ts < 1 or p_token_iat is null then
        raise exception 'Invalid auth record' using errcode = '22023';
    end if;
    if p_token_iat < p_auth_amr_ts then
        raise exception 'Token predates auth event' using errcode = '42501';
    end if;
    insert into tharmesh_private.auth_freshness (account_id, verified_at, auth_amr_ts, token_iat)
        values (p_account, clock_timestamp(), p_auth_amr_ts, p_token_iat)
    on conflict (account_id) do update
        set verified_at = excluded.verified_at,
            auth_amr_ts = excluded.auth_amr_ts,
            token_iat = excluded.token_iat;
end;
$$;

drop function if exists public.tharmesh_auth_fresh_record_svc(uuid, bigint);

create or replace function public.tharmesh_auth_fresh_required(
    p_max_age_seconds integer default 900
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    row tharmesh_private.auth_freshness%rowtype;
    max_age int := greatest(p_max_age_seconds, 60);
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    select * into row from tharmesh_private.auth_freshness f where f.account_id = caller;
    if not found or row.auth_amr_ts is null then
        return false;
    end if;
    if extract(epoch from row.verified_at) - row.auth_amr_ts > max_age then
        return false;
    end if;
    return row.verified_at > clock_timestamp() - make_interval(secs => max_age)
        and (extract(epoch from clock_timestamp())::bigint - row.auth_amr_ts) <= max_age;
end;
$$;

revoke all on function public.tharmesh_bootstrap_challenge_svc(uuid) from public, anon, authenticated;
grant execute on function public.tharmesh_bootstrap_challenge_svc(uuid) to service_role;

revoke all on function public.tharmesh_bootstrap_device_svc(uuid, text, text, uuid, text) from public, anon, authenticated;
grant execute on function public.tharmesh_bootstrap_device_svc(uuid, text, text, uuid, text) to service_role;

revoke all on function public.tharmesh_enrollment_pending_get_svc(uuid, text) from public, anon, authenticated;
grant execute on function public.tharmesh_enrollment_pending_get_svc(uuid, text) to service_role;

revoke all on function public.tharmesh_auth_fresh_record_svc(uuid, bigint, bigint) from public, anon, authenticated;
grant execute on function public.tharmesh_auth_fresh_record_svc(uuid, bigint, bigint) to service_role;

commit;
