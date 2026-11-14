-- Device list "current" hint, explicit reset evidence, bootstrap first trusted device.
begin;

alter table tharmesh_private.canonical_identities
    add column if not exists explicit_reset_at timestamptz;

create or replace function public.tharmesh_list_devices(p_self_device_pubkey text default null)
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
    self_pub text := nullif(trim(p_self_device_pubkey), '');
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    return query
        select d.device_id, d.mesh_public_key, d.device_public_key, d.created_at,
               d.revoked_at, d.last_seen_at,
               (self_pub is not null and d.device_public_key = self_pub)
        from public.devices d
        where d.account_id = caller
        order by d.created_at asc;
end;
$$;

revoke all on function public.tharmesh_list_devices(text) from public, anon;
grant execute on function public.tharmesh_list_devices(text) to authenticated;

drop function if exists public.tharmesh_list_devices();

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
    update tharmesh_private.canonical_identities i
        set explicit_reset_at = clock_timestamp()
        where i.account_id = caller and i.explicit_reset_at is null;
end;
$$;

revoke all on function public.tharmesh_commit_explicit_reset() from public, anon;
grant execute on function public.tharmesh_commit_explicit_reset() to authenticated;

create or replace function public.tharmesh_bootstrap_device(
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
    caller uuid := auth.uid();
    mesh text;
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    if p_device_id is null or p_device_pubkey is null or length(p_device_pubkey) < 80 then
        raise exception 'Invalid device fields' using errcode = '22023';
    end if;
    select i.mesh_public_key into mesh
        from tharmesh_private.canonical_identities i where i.account_id = caller;
    if mesh is null then
        raise exception 'Canonical mesh required' using errcode = '42501';
    end if;
    if exists (
        select 1 from public.devices d
        where d.account_id = caller and d.revoked_at is null
    ) then
        return;
    end if;
    insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
        values (
            caller,
            p_device_id,
            mesh,
            p_device_pubkey,
            coalesce(nullif(trim(p_certificate), ''), 'bootstrap-device')
        );
end;
$$;

revoke all on function public.tharmesh_bootstrap_device(text, text, text) from public, anon;
grant execute on function public.tharmesh_bootstrap_device(text, text, text) to authenticated;

drop function if exists public.tharmesh_account_evidence();

create or replace function public.tharmesh_account_evidence()
returns table (
    identity_state text,
    mesh_public_key text,
    device_count integer,
    active_device_count integer,
    has_recovery_blob boolean,
    explicit_reset_committed boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    st record;
    reset_at timestamptz;
begin
    if caller is null then
        raise exception 'Not authenticated' using errcode = '42501';
    end if;
    select * into st from public.tharmesh_identity_state() limit 1;
    select i.explicit_reset_at into reset_at
        from tharmesh_private.canonical_identities i where i.account_id = caller;
    return query
        select st.state, st.mesh_public_key,
            (select count(*)::int from public.devices d where d.account_id = caller),
            (select count(*)::int from public.devices d where d.account_id = caller and d.revoked_at is null),
            exists (select 1 from public.recovery_blobs r where r.account_id = caller),
            (reset_at is not null);
end;
$$;

revoke all on function public.tharmesh_account_evidence() from public, anon;
grant execute on function public.tharmesh_account_evidence() to authenticated;

commit;
