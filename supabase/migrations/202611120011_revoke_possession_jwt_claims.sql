-- 0.6 gate 2: possession proof via edge; JWT claims session; revoke TOCTOU fix; fail-closed authenticated revoke.
begin;

create or replace function auth.jwt()
returns jsonb
language sql
stable
as $$
    select coalesce(
        nullif(current_setting('request.jwt.claims', true), '')::jsonb,
        jsonb_strip_nulls(jsonb_build_object(
            'sub', nullif(current_setting('request.jwt.claim.sub', true), ''),
            'session_id', nullif(current_setting('request.jwt.claim.session_id', true), ''),
            'jti', nullif(current_setting('request.jwt.claim.jti', true), '')
        ))
    );
$$;

create or replace function public.tharmesh_jwt_session_id()
returns text
language sql
stable
set search_path = ''
as $$
    select coalesce(
        nullif(trim(auth.jwt() ->> 'session_id'), ''),
        nullif(trim(auth.jwt() ->> 'jti'), '')
    );
$$;

create or replace function public.tharmesh_device_revoke_svc(
    p_account uuid,
    p_device_id text,
    p_grant_id uuid,
    p_session_id text,
    p_caller_device_pubkey text,
    p_proof_ts bigint
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    grant_row tharmesh_private.sensitive_action_grants%rowtype;
    active_count int;
    remaining int;
    now_ts bigint := extract(epoch from clock_timestamp())::bigint;
begin
    if p_account is null or p_device_id is null or length(trim(p_device_id)) = 0
        or p_grant_id is null or p_session_id is null or length(trim(p_session_id)) < 8 then
        raise exception 'Invalid revoke request' using errcode = '22023';
    end if;
    if p_caller_device_pubkey is null or length(trim(p_caller_device_pubkey)) < 40 then
        raise exception 'Caller device proof required' using errcode = '42501';
    end if;
    if p_proof_ts is null or p_proof_ts < 1 or abs(now_ts - p_proof_ts) > 900 then
        raise exception 'Proof timestamp stale' using errcode = '42501';
    end if;

    select * into grant_row from tharmesh_private.sensitive_action_grants g
        where g.grant_id = p_grant_id and g.account_id = p_account for update;
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

    perform tharmesh_private.account_device_mutation_lock(p_account);

    if not exists (
        select 1 from public.devices d
        where d.account_id = p_account and d.revoked_at is null
          and d.device_public_key = trim(p_caller_device_pubkey)
    ) then
        raise exception 'Caller device not active on account' using errcode = '42501';
    end if;

    select count(*)::int into active_count
        from public.devices d
        where d.account_id = p_account and d.revoked_at is null;
    if active_count <= 1 then
        raise exception 'Last active device cannot be revoked' using errcode = '42501';
    end if;

    update public.devices d
        set revoked_at = clock_timestamp()
        where d.account_id = p_account and d.device_id = p_device_id and d.revoked_at is null;
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
        where g.grant_id = p_grant_id;
end;
$$;

create or replace function public.tharmesh_device_revoke(
    p_device_id text,
    p_grant_id uuid,
    p_active_device_pubkey text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    raise exception 'Direct device revoke RPC disabled; use device-revoke edge' using errcode = '42501';
end;
$$;

revoke all on function public.tharmesh_device_revoke(text, uuid, text) from public, anon, authenticated;

revoke all on function public.tharmesh_device_revoke_svc(uuid, text, uuid, text, text, bigint)
    from public, anon, authenticated;
grant execute on function public.tharmesh_device_revoke_svc(uuid, text, uuid, text, text, bigint) to service_role;

commit;
