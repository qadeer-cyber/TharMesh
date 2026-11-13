-- 0.6 supervisor fixes: session-bound grants, atomic recovery commit, grant-gated revoke.
begin;

create extension if not exists pgcrypto;

do $$ begin
    alter table tharmesh_private.sensitive_action_grants
        rename column session_sub to session_id;
exception
    when undefined_column then
        alter table tharmesh_private.sensitive_action_grants
            add column if not exists session_id text;
end $$;

create table if not exists tharmesh_private.recovery_upload_idempotency (
    account_id uuid not null references auth.users (id) on delete cascade,
    request_id text not null,
    ciphertext_sha256 text not null,
    generation_after integer not null,
    committed_at timestamptz not null default clock_timestamp(),
    primary key (account_id, request_id)
);

revoke all on table tharmesh_private.recovery_upload_idempotency from public, anon, authenticated;

drop function if exists public.tharmesh_sensitive_grant_issue_svc(uuid, text, text, bigint, bigint, integer);

create or replace function public.tharmesh_sensitive_grant_issue_svc(
    p_account uuid,
    p_action text,
    p_session_id text,
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
    act text := trim(p_action);
    ttl int := least(greatest(coalesce(p_ttl_seconds, 900), 60), 900);
begin
    if p_account is null or act is null or length(act) = 0
        or p_auth_amr_ts is null or p_auth_amr_ts < 1 or p_token_iat is null then
        raise exception 'Invalid grant request' using errcode = '22023';
    end if;
    if act not in ('recovery_rotate', 'device_revoke') then
        raise exception 'Action not allowlisted' using errcode = '42501';
    end if;
    if p_session_id is null or length(trim(p_session_id)) < 8 then
        raise exception 'Session id required' using errcode = '22023';
    end if;
    if p_token_iat < p_auth_amr_ts then
        raise exception 'Token predates auth event' using errcode = '42501';
    end if;
    insert into tharmesh_private.sensitive_action_grants (
        grant_id, account_id, action, session_id, auth_amr_ts, token_iat, expires_at
    )
        values (
            gid,
            p_account,
            act,
            trim(p_session_id),
            p_auth_amr_ts,
            p_token_iat,
            clock_timestamp() + make_interval(secs => ttl)
        );
    return gid;
end;
$$;

create or replace function public.tharmesh_recovery_rotate_commit_svc(
    p_account uuid,
    p_grant_id uuid,
    p_session_id text,
    p_request_id text,
    p_ciphertext_sha256 text,
    p_expected_generation integer,
    p_kdf text,
    p_n integer,
    p_r integer,
    p_p integer,
    p_salt_b64 text,
    p_iv_b64 text,
    p_ciphertext_b64 text
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    grant_row tharmesh_private.sensitive_action_grants%rowtype;
    idem tharmesh_private.recovery_upload_idempotency%rowtype;
    next_gen integer;
    updated integer;
    digest_hex text;
begin
    if p_account is null or p_grant_id is null or p_session_id is null or p_request_id is null
        or length(trim(p_request_id)) < 8 or length(trim(p_request_id)) > 128 then
        raise exception 'Invalid recovery request' using errcode = '22023';
    end if;
    if p_kdf is distinct from 'scrypt' or p_n is null or p_n < 16384 or p_r is null or p_r < 8 or p_p is null or p_p < 1 then
        raise exception 'Invalid recovery blob' using errcode = '22023';
    end if;
    if p_salt_b64 is null or p_iv_b64 is null or p_ciphertext_b64 is null then
        raise exception 'Invalid recovery blob' using errcode = '22023';
    end if;
    if p_expected_generation is null or p_expected_generation < 0 then
        raise exception 'Invalid recovery request' using errcode = '22023';
    end if;
    digest_hex := pg_catalog.encode(public.digest(pg_catalog.convert_to(p_ciphertext_b64, 'UTF8'), 'sha256'), 'hex');
    if p_ciphertext_sha256 is distinct from digest_hex then
        raise exception 'Ciphertext hash mismatch' using errcode = '22023';
    end if;

    select * into idem from tharmesh_private.recovery_upload_idempotency i
        where i.account_id = p_account and i.request_id = trim(p_request_id);
    if found then
        if idem.ciphertext_sha256 <> digest_hex then
            raise exception 'Idempotency key reused with different payload' using errcode = '23505';
        end if;
        return idem.generation_after;
    end if;

    select * into grant_row from tharmesh_private.sensitive_action_grants g
        where g.grant_id = p_grant_id and g.account_id = p_account for update;
    if not found or grant_row.consumed_at is not null then
        raise exception 'Grant invalid or consumed' using errcode = '42501';
    end if;
    if grant_row.action <> 'recovery_rotate' then
        raise exception 'Grant action mismatch' using errcode = '42501';
    end if;
    if grant_row.session_id is distinct from trim(p_session_id) then
        raise exception 'Grant session mismatch' using errcode = '42501';
    end if;
    if grant_row.expires_at <= clock_timestamp() then
        raise exception 'Grant expired' using errcode = '42501';
    end if;

    if p_expected_generation = 0 then
        if exists (select 1 from public.recovery_blobs r where r.account_id = p_account) then
            raise exception 'Recovery generation conflict' using errcode = '23505';
        end if;
        insert into public.recovery_blobs (
            account_id, kdf, n, r, p, salt_b64, iv_b64, ciphertext_b64, generation, updated_at
        )
            values (
                p_account, p_kdf, p_n, p_r, p_p, p_salt_b64, p_iv_b64, p_ciphertext_b64, 1, clock_timestamp()
            );
        next_gen := 1;
    else
        update public.recovery_blobs r
            set kdf = p_kdf,
                n = p_n,
                r = p_r,
                p = p_p,
                salt_b64 = p_salt_b64,
                iv_b64 = p_iv_b64,
                ciphertext_b64 = p_ciphertext_b64,
                generation = p_expected_generation + 1,
                updated_at = clock_timestamp()
            where r.account_id = p_account and r.generation = p_expected_generation;
        get diagnostics updated = row_count;
        if updated <> 1 then
            raise exception 'Recovery generation conflict' using errcode = '23505';
        end if;
        next_gen := p_expected_generation + 1;
    end if;

    update tharmesh_private.sensitive_action_grants g
        set consumed_at = clock_timestamp()
        where g.grant_id = p_grant_id;

    insert into tharmesh_private.recovery_upload_idempotency (
        account_id, request_id, ciphertext_sha256, generation_after
    )
        values (p_account, trim(p_request_id), digest_hex, next_gen);

    return next_gen;
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
declare
    caller uuid := auth.uid();
    session_id text := nullif(trim(current_setting('request.jwt.claim.session_id', true)), '');
    grant_row tharmesh_private.sensitive_action_grants%rowtype;
    active_count int;
    remaining int;
begin
    if caller is null or p_device_id is null or length(trim(p_device_id)) = 0
        or p_grant_id is null or session_id is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    if p_active_device_pubkey is null or length(trim(p_active_device_pubkey)) < 40 then
        raise exception 'Active device proof required' using errcode = '42501';
    end if;

    select * into grant_row from tharmesh_private.sensitive_action_grants g
        where g.grant_id = p_grant_id and g.account_id = caller for update;
    if not found or grant_row.consumed_at is not null then
        raise exception 'Grant invalid or consumed' using errcode = '42501';
    end if;
    if grant_row.action <> 'device_revoke' then
        raise exception 'Grant action mismatch' using errcode = '42501';
    end if;
    if grant_row.session_id is distinct from session_id then
        raise exception 'Grant session mismatch' using errcode = '42501';
    end if;
    if grant_row.expires_at <= clock_timestamp() then
        raise exception 'Grant expired' using errcode = '42501';
    end if;

    if not exists (
        select 1 from public.devices d
        where d.account_id = caller and d.revoked_at is null
          and d.device_public_key = trim(p_active_device_pubkey)
    ) then
        raise exception 'Caller device not active on account' using errcode = '42501';
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

    update tharmesh_private.sensitive_action_grants g
        set consumed_at = clock_timestamp()
        where g.grant_id = p_grant_id;
end;
$$;

drop function if exists public.tharmesh_device_revoke(text);

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
    perform tharmesh_private.account_device_mutation_lock(p_account);
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

create or replace function public.tharmesh_commit_explicit_reset()
returns void
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
    perform tharmesh_private.account_device_mutation_lock(caller);
    update tharmesh_private.canonical_identities i
        set explicit_reset_at = clock_timestamp()
        where i.account_id = caller and i.explicit_reset_at is null;
end;
$$;

revoke all on function public.tharmesh_recovery_rotate_commit_svc(
    uuid, uuid, text, text, text, integer, text, integer, integer, integer, text, text, text
) from public, anon, authenticated;
grant execute on function public.tharmesh_recovery_rotate_commit_svc(
    uuid, uuid, text, text, text, integer, text, integer, integer, integer, text, text, text
) to service_role;

revoke all on function public.tharmesh_device_revoke(text, uuid, text) from public, anon;
grant execute on function public.tharmesh_device_revoke(text, uuid, text) to authenticated;

commit;
