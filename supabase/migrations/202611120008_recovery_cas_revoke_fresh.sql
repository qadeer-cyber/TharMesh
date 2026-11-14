-- Recovery blob generation (compare-and-swap) and guarded device revoke (fresh auth + last-device lockout).
begin;

alter table public.recovery_blobs
    add column if not exists generation integer not null default 1;

create or replace function public.tharmesh_recovery_generation()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    gen int;
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    select r.generation into gen from public.recovery_blobs r where r.account_id = caller;
    if not found then
        return 0;
    end if;
    return gen;
end;
$$;

revoke all on function public.tharmesh_recovery_generation() from public, anon;
grant execute on function public.tharmesh_recovery_generation() to authenticated;

create or replace function public.tharmesh_recovery_put_svc(
    p_account uuid,
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
    next_gen integer;
    updated integer;
begin
    if p_account is null or p_expected_generation is null or p_expected_generation < 0 then
        raise exception 'Invalid recovery request' using errcode = '22023';
    end if;
    if p_kdf is distinct from 'scrypt' or p_n < 16384 or p_r < 8 or p_p < 1 then
        raise exception 'Invalid recovery blob' using errcode = '22023';
    end if;
    if p_salt_b64 is null or p_iv_b64 is null or p_ciphertext_b64 is null then
        raise exception 'Invalid recovery blob' using errcode = '22023';
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
        return 1;
    end if;
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
    return p_expected_generation + 1;
end;
$$;

revoke all on function public.tharmesh_recovery_put_svc(
    uuid, integer, text, integer, integer, integer, text, text, text
) from public, anon, authenticated;
grant execute on function public.tharmesh_recovery_put_svc(
    uuid, integer, text, integer, integer, integer, text, text, text
) to service_role;

create or replace function public.tharmesh_device_revoke(p_device_id text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    active_count int;
begin
    if caller is null or p_device_id is null or length(trim(p_device_id)) = 0 then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    if not public.tharmesh_auth_fresh_required(900) then
        raise exception 'Fresh auth required' using errcode = '42501';
    end if;
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
end;
$$;

revoke all on function public.tharmesh_device_revoke(text) from public, anon;
grant execute on function public.tharmesh_device_revoke(text) to authenticated;

commit;
