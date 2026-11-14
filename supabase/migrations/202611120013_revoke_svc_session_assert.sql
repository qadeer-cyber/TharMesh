-- 0.6: DB-enforced session match on revoke commit (not Edge-only).
begin;

drop function if exists public.tharmesh_device_revoke_svc(uuid, uuid);

create or replace function public.tharmesh_device_revoke_svc(
    p_account uuid,
    p_challenge_id uuid,
    p_session_id text
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
    if p_account is null or p_challenge_id is null or p_session_id is null or length(trim(p_session_id)) < 8 then
        raise exception 'Invalid revoke request' using errcode = '22023';
    end if;

    perform tharmesh_private.account_device_mutation_lock(p_account);

    select * into ch from tharmesh_private.device_revoke_challenges c
        where c.challenge_id = p_challenge_id and c.account_id = p_account for update;
    if not found then
        raise exception 'Challenge not found' using errcode = '42501';
    end if;
    if ch.session_id is distinct from trim(p_session_id) then
        raise exception 'Challenge session mismatch' using errcode = '42501';
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

revoke all on function public.tharmesh_device_revoke_svc(uuid, uuid, text)
    from public, anon, authenticated;
grant execute on function public.tharmesh_device_revoke_svc(uuid, uuid, text) to service_role;

commit;
