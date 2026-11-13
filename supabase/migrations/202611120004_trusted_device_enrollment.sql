-- Trusted additional-device enrollment (Track B). Direct device writes stay
-- blocked for admitted accounts (migration 003); completion goes through RPC.
begin;

alter table public.devices
    add column if not exists revoked_at timestamptz,
    add column if not exists last_seen_at timestamptz;

create table if not exists tharmesh_private.enrollment_pending (
    account_id uuid not null references auth.users (id) on delete cascade,
    nonce_b64 text not null,
    mesh_public_key text not null,
    new_device_pubkey text not null,
    device_id text not null,
    request_sig_b64 text not null,
    expires_at timestamptz not null,
  approved_device_id text,
    approval_sig_b64 text,
    consumed_at timestamptz,
    primary key (account_id, nonce_b64),
    check (length(nonce_b64) >= 8)
);

revoke all on tharmesh_private.enrollment_pending from public, anon, authenticated;

create or replace function tharmesh_private.base64url_decode(input text)
returns bytea
language plpgsql
immutable
strict
as $$
declare
    padded text;
    rem int;
begin
    padded := replace(replace(input, '-', '+'), '_', '/');
    rem := length(padded) % 4;
    if rem > 0 then padded := padded || repeat('=', 4 - rem); end if;
    return decode(padded, 'base64');
end;
$$;

-- In-database ECDSA verification is intentionally unavailable (fail closed).
-- Cryptographic verification runs only in Edge Functions (Update 0.5).

create or replace function tharmesh_private.device_approve_message(
    p_account uuid,
    p_new_device_pubkey text,
    p_nonce_b64 text,
    p_expires_at timestamptz
)
returns bytea
language sql
immutable
as $$
    select convert_to(
        'TM-DEVICE-APPROVE-v1' || chr(31) || p_account::text || chr(31) ||
        p_new_device_pubkey || chr(31) || p_nonce_b64 || chr(31) ||
        (extract(epoch from p_expires_at)::bigint)::text,
        'UTF8'
    );
$$;

create or replace function tharmesh_private.enroll_request_message(
    p_account uuid,
    p_mesh text,
    p_device_pubkey text,
    p_device_id text,
    p_nonce_b64 text
)
returns bytea
language sql
immutable
as $$
    select convert_to(
        'TM-ENROLL-v1' || chr(31) || p_account::text || chr(31) || p_mesh || chr(31) ||
        p_device_pubkey || chr(31) || p_device_id || chr(31) || p_nonce_b64 || chr(31) || '0',
        'UTF8'
    );
$$;

create or replace function public.tharmesh_list_devices()
returns table (
    device_id text,
    mesh_public_key text,
    device_public_key text,
    created_at timestamptz,
    revoked_at timestamptz,
    last_seen_at timestamptz,
    is_current boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    return query
        select d.device_id, d.mesh_public_key, d.device_public_key, d.created_at,
               d.revoked_at, d.last_seen_at, false
        from public.devices d
        where d.account_id = caller
        order by d.created_at asc;
end;
$$;

revoke all on function public.tharmesh_list_devices() from public, anon;
grant execute on function public.tharmesh_list_devices() to authenticated;

create or replace function public.tharmesh_device_revoke(p_device_id text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null or p_device_id is null or length(trim(p_device_id)) = 0 then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    update public.devices d
        set revoked_at = clock_timestamp()
        where d.account_id = caller and d.device_id = p_device_id and d.revoked_at is null;
    if not found then
        raise exception 'Device not found or already revoked' using errcode = '42501';
    end if;
end;
$$;

revoke all on function public.tharmesh_device_revoke(text) from public, anon;
grant execute on function public.tharmesh_device_revoke(text) to authenticated;

create or replace function public.tharmesh_enrollment_begin(
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
    caller uuid := auth.uid();
    mesh text;
    exp timestamptz := coalesce(p_expires_at, clock_timestamp() + interval '10 minutes');
    msg bytea;
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    if not exists (select 1 from auth.users u where u.id = caller and u.email_confirmed_at is not null) then
        raise exception 'Verified account required' using errcode = '42501';
    end if;
    if p_device_id is null or p_new_device_pubkey is null or p_nonce_b64 is null
        or length(p_new_device_pubkey) < 80 then
        raise exception 'Invalid enrollment fields' using errcode = '22023';
    end if;
    select i.mesh_public_key into mesh from tharmesh_private.canonical_identities i where i.account_id = caller;
    if mesh is null then
        raise exception 'Canonical mesh required' using errcode = '42501';
    end if;
    raise exception 'Enrollment verification requires Edge Function' using errcode = '42501';
    if exists (
        select 1 from tharmesh_private.enrollment_pending e
        where e.account_id = caller and e.nonce_b64 = p_nonce_b64
    ) then
        raise exception 'Enrollment nonce already used' using errcode = '42501';
    end if;
    insert into tharmesh_private.enrollment_pending
        (account_id, nonce_b64, mesh_public_key, new_device_pubkey, device_id, request_sig_b64, expires_at)
    values (caller, p_nonce_b64, mesh, p_new_device_pubkey, p_device_id, p_request_sig_b64, exp);
end;
$$;

revoke all on function public.tharmesh_enrollment_begin(text,text,text,text,timestamptz) from public, anon;
grant execute on function public.tharmesh_enrollment_begin(text,text,text,text,timestamptz) to authenticated;

create or replace function public.tharmesh_enrollment_approve(
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
    caller uuid := auth.uid();
    pending tharmesh_private.enrollment_pending%rowtype;
    trusted_pubkey text;
    msg bytea;
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    select * into pending from tharmesh_private.enrollment_pending e
        where e.account_id = caller and e.nonce_b64 = p_nonce_b64 and e.consumed_at is null for update;
    if not found then
        raise exception 'Unknown or consumed enrollment' using errcode = '42501';
    end if;
    if pending.expires_at <= clock_timestamp() then
        raise exception 'Enrollment expired' using errcode = '42501';
    end if;
    select d.device_public_key into trusted_pubkey from public.devices d
        where d.account_id = caller and d.device_id = p_trusted_device_id and d.revoked_at is null;
    if trusted_pubkey is null then
        raise exception 'Trusted device not found' using errcode = '42501';
    end if;
    raise exception 'Enrollment verification requires Edge Function' using errcode = '42501';
    update tharmesh_private.enrollment_pending e
        set approval_sig_b64 = p_approval_sig_b64,
            approved_device_id = p_trusted_device_id
        where e.account_id = caller and e.nonce_b64 = p_nonce_b64;
end;
$$;

revoke all on function public.tharmesh_enrollment_approve(text,text,text) from public, anon;
grant execute on function public.tharmesh_enrollment_approve(text,text,text) to authenticated;

create or replace function public.tharmesh_enrollment_finish(p_nonce_b64 text, p_certificate text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    pending tharmesh_private.enrollment_pending%rowtype;
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    select * into pending from tharmesh_private.enrollment_pending e
        where e.account_id = caller and e.nonce_b64 = p_nonce_b64 for update;
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
            caller,
            pending.device_id,
            pending.mesh_public_key,
            pending.new_device_pubkey,
            coalesce(nullif(trim(p_certificate), ''), 'trusted-enrollment:' || p_nonce_b64)
        );
    update tharmesh_private.enrollment_pending e
        set consumed_at = clock_timestamp()
        where e.account_id = caller and e.nonce_b64 = p_nonce_b64;
end;
$$;

revoke all on function public.tharmesh_enrollment_finish(text,text) from public, anon;
grant execute on function public.tharmesh_enrollment_finish(text,text) to authenticated;

create or replace function public.tharmesh_account_evidence()
returns table (
    identity_state text,
    mesh_public_key text,
    device_count integer,
    active_device_count integer,
    has_recovery_blob boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    st record;
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    select * into st from public.tharmesh_identity_state() limit 1;
    return query
        select st.state, st.mesh_public_key,
            (select count(*)::int from public.devices d where d.account_id = caller),
            (select count(*)::int from public.devices d where d.account_id = caller and d.revoked_at is null),
            exists (select 1 from public.recovery_blobs r where r.account_id = caller);
end;
$$;

revoke all on function public.tharmesh_account_evidence() from public, anon;
grant execute on function public.tharmesh_account_evidence() to authenticated;

commit;
