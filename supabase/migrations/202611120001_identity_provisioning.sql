-- Apply with the Supabase migration owner. Automatic admission starts disabled.
begin;

create schema if not exists tharmesh_private;

create table tharmesh_private.identity_admission (
    singleton boolean primary key check (singleton),
    enabled boolean not null default false
);
insert into tharmesh_private.identity_admission (singleton) values (true);

create table tharmesh_private.canonical_identities (
    account_id uuid primary key references auth.users (id) on delete cascade,
    mesh_public_key text,
    generation integer check (generation = 1),
    check ((mesh_public_key is null) = (generation is null))
);
alter table tharmesh_private.canonical_identities enable row level security;
revoke all on schema tharmesh_private from public, anon, authenticated;
revoke all on all tables in schema tharmesh_private from public, anon, authenticated;

create function tharmesh_private.admit_new_identity()
returns trigger language plpgsql security definer set search_path = ''
as $$
begin
    if exists (select 1 from tharmesh_private.identity_admission where singleton and enabled) then
        insert into tharmesh_private.canonical_identities (account_id) values (new.id);
    end if;
    return new;
end;
$$;

revoke all on function tharmesh_private.admit_new_identity() from public, anon, authenticated;
create trigger tharmesh_admit_new_identity
after insert on auth.users
for each row execute function tharmesh_private.admit_new_identity();

create function public.tharmesh_provision_identity(p_mesh_public_key text default null)
returns table (state text, mesh_public_key text, generation integer)
language plpgsql security definer set search_path = ''
as $$
declare
    caller uuid := auth.uid();
    canonical tharmesh_private.canonical_identities%rowtype;
begin
    if caller is null or not exists (
        select 1 from auth.users u where u.id = caller and u.email_confirmed_at is not null
    ) then
        raise exception 'Verified account required' using errcode = '42501';
    end if;

    if p_mesh_public_key is not null and
        (length(p_mesh_public_key) <> 122 or p_mesh_public_key !~ '^[A-Za-z0-9_-]+$') then
        raise exception 'Invalid public key encoding' using errcode = '22023';
    end if;

    select * into canonical from tharmesh_private.canonical_identities i
        where i.account_id = caller for update;
    if not found then
        return query select 'legacy_unknown'::text, null::text, null::integer;
        return;
    end if;

    if canonical.mesh_public_key is null and (
        exists (select 1 from public.devices d where d.account_id = caller)
        or exists (select 1 from public.recovery_blobs r where r.account_id = caller)
    ) then
        return query select 'recovery_required'::text, null::text, null::integer;
        return;
    end if;

    if canonical.mesh_public_key is null and p_mesh_public_key is not null then
        if not exists (select 1 from tharmesh_private.identity_admission where singleton and enabled) then
            return query select 'recovery_required'::text, null::text, null::integer;
            return;
        end if;
        update tharmesh_private.canonical_identities i
            set mesh_public_key = p_mesh_public_key, generation = 1
            where i.account_id = caller returning i.* into canonical;
    end if;

    return query select
        case when canonical.mesh_public_key is null then 'unprovisioned'
             when p_mesh_public_key is null then 'active'
             when canonical.mesh_public_key = p_mesh_public_key then 'active'
             else 'recovery_required' end,
        canonical.mesh_public_key, canonical.generation;
end;
$$;

revoke all on function public.tharmesh_provision_identity(text) from public, anon, authenticated;
grant execute on function public.tharmesh_provision_identity(text) to authenticated;

commit;
