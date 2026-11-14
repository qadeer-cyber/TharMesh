-- Update 0.5 P0: no in-DB signature verification; enrollment writes are service-role only.
begin;

drop function if exists tharmesh_private.verify_ecdsa_p256_spki(text, bytea, text);

create table if not exists tharmesh_private.auth_freshness (
    account_id uuid primary key references auth.users (id) on delete cascade,
    verified_at timestamptz not null,
    session_iat bigint not null
);

revoke all on tharmesh_private.auth_freshness from public, anon, authenticated;

drop function if exists public.tharmesh_enrollment_begin(text, text, text, text, timestamptz);
drop function if exists public.tharmesh_enrollment_approve(text, text, text);
drop function if exists public.tharmesh_enrollment_finish(text, text);
drop function if exists public.tharmesh_bootstrap_device(text, text, text);

create or replace function public.tharmesh_enrollment_begin_svc(
    p_account uuid,
    p_device_id text,
    p_new_device_pubkey text,
    p_nonce_b64 text,
    p_request_sig_b64 text,
    p_expires_at timestamptz default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    mesh text;
    exp timestamptz := coalesce(p_expires_at, clock_timestamp() + interval '10 minutes');
begin
    if p_account is null then
        raise exception 'Account required' using errcode = '42501';
    end if;
    if not exists (
        select 1 from auth.users u where u.id = p_account and u.email_confirmed_at is not null
    ) then
        raise exception 'Verified account required' using errcode = '42501';
    end if;
    if p_device_id is null or p_new_device_pubkey is null or p_nonce_b64 is null
        or length(p_new_device_pubkey) < 80 or length(p_request_sig_b64) < 40 then
        raise exception 'Invalid enrollment fields' using errcode = '22023';
    end if;
    select i.mesh_public_key into mesh from tharmesh_private.canonical_identities i where i.account_id = p_account;
    if mesh is null then
        raise exception 'Canonical mesh required' using errcode = '42501';
    end if;
    if exists (
        select 1 from tharmesh_private.enrollment_pending e
        where e.account_id = p_account and e.nonce_b64 = p_nonce_b64
    ) then
        raise exception 'Enrollment nonce already used' using errcode = '42501';
    end if;
    insert into tharmesh_private.enrollment_pending
        (account_id, nonce_b64, mesh_public_key, new_device_pubkey, device_id, request_sig_b64, expires_at)
    values (p_account, p_nonce_b64, mesh, p_new_device_pubkey, p_device_id, p_request_sig_b64, exp);
end;
$$;

create or replace function public.tharmesh_trusted_device_pubkey_svc(
    p_account uuid,
    p_trusted_device_id text
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    trusted_pubkey text;
begin
    select d.device_public_key into trusted_pubkey from public.devices d
        where d.account_id = p_account and d.device_id = p_trusted_device_id and d.revoked_at is null;
    if trusted_pubkey is null then
        raise exception 'Trusted device not found' using errcode = '42501';
    end if;
    return trusted_pubkey;
end;
$$;

create or replace function public.tharmesh_enrollment_approve_svc(
    p_account uuid,
    p_nonce_b64 text,
    p_trusted_device_id text,
    p_approval_sig_b64 text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    pending tharmesh_private.enrollment_pending%rowtype;
    active_count int;
begin
    if p_account is null then
        raise exception 'Account required' using errcode = '42501';
    end if;
    select count(*)::int into active_count from public.devices d
        where d.account_id = p_account and d.revoked_at is null;
    if active_count < 1 then
        raise exception 'Trusted device required' using errcode = '42501';
    end if;
    if not exists (
        select 1 from public.devices d
        where d.account_id = p_account and d.device_id = p_trusted_device_id and d.revoked_at is null
    ) then
        raise exception 'Trusted device not found' using errcode = '42501';
    end if;
    select * into pending from tharmesh_private.enrollment_pending e
        where e.account_id = p_account and e.nonce_b64 = p_nonce_b64 and e.consumed_at is null for update;
    if not found then
        raise exception 'Unknown or consumed enrollment' using errcode = '42501';
    end if;
    if pending.expires_at <= clock_timestamp() then
        raise exception 'Enrollment expired' using errcode = '42501';
    end if;
    if pending.approval_sig_b64 is not null then
        raise exception 'Enrollment already approved' using errcode = '42501';
    end if;
    update tharmesh_private.enrollment_pending e
        set approval_sig_b64 = p_approval_sig_b64,
            approved_device_id = p_trusted_device_id
        where e.account_id = p_account and e.nonce_b64 = p_nonce_b64;
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
    p_certificate text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    mesh text;
begin
    if p_account is null or p_device_id is null or p_device_pubkey is null
        or length(p_device_pubkey) < 80 then
        raise exception 'Invalid device fields' using errcode = '22023';
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
        return;
    end if;
    insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
        values (
            p_account,
            p_device_id,
            mesh,
            p_device_pubkey,
            coalesce(nullif(trim(p_certificate), ''), 'bootstrap-device')
        );
end;
$$;

create or replace function public.tharmesh_auth_fresh_record_svc(p_account uuid, p_session_iat bigint)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_account is null or p_session_iat is null or p_session_iat < 1 then
        raise exception 'Invalid auth record' using errcode = '22023';
    end if;
    insert into tharmesh_private.auth_freshness (account_id, verified_at, session_iat)
        values (p_account, clock_timestamp(), p_session_iat)
    on conflict (account_id) do update
        set verified_at = excluded.verified_at, session_iat = excluded.session_iat;
end;
$$;

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
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    select * into row from tharmesh_private.auth_freshness f where f.account_id = caller;
    if not found then
        return false;
    end if;
    return row.verified_at > clock_timestamp() - make_interval(secs => greatest(p_max_age_seconds, 60));
end;
$$;

revoke all on function public.tharmesh_enrollment_begin_svc(uuid, text, text, text, text, timestamptz) from public, anon, authenticated;
grant execute on function public.tharmesh_enrollment_begin_svc(uuid, text, text, text, text, timestamptz) to service_role;

revoke all on function public.tharmesh_trusted_device_pubkey_svc(uuid, text) from public, anon, authenticated;
grant execute on function public.tharmesh_trusted_device_pubkey_svc(uuid, text) to service_role;

revoke all on function public.tharmesh_enrollment_approve_svc(uuid, text, text, text) from public, anon, authenticated;
grant execute on function public.tharmesh_enrollment_approve_svc(uuid, text, text, text) to service_role;

revoke all on function public.tharmesh_enrollment_finish_svc(uuid, text, text) from public, anon, authenticated;
grant execute on function public.tharmesh_enrollment_finish_svc(uuid, text, text) to service_role;

revoke all on function public.tharmesh_bootstrap_device_svc(uuid, text, text, text) from public, anon, authenticated;
grant execute on function public.tharmesh_bootstrap_device_svc(uuid, text, text, text) to service_role;

revoke all on function public.tharmesh_auth_fresh_record_svc(uuid, bigint) from public, anon, authenticated;
grant execute on function public.tharmesh_auth_fresh_record_svc(uuid, bigint) to service_role;

revoke all on function public.tharmesh_auth_fresh_required(integer) from public, anon;
grant execute on function public.tharmesh_auth_fresh_required(integer) to authenticated;

commit;
