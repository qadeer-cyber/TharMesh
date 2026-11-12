-- Run as migration owner against an isolated Supabase database only.
-- All fixtures are rolled back.
begin;
insert into auth.users (id, email, email_confirmed_at)
values ('f1e00000-0000-4000-8000-000000000001', 'legacy@tharmesh.invalid', now());

update tharmesh_private.identity_admission set enabled = true where singleton;
insert into auth.users (id, email, email_confirmed_at)
values ('f1e00000-0000-4000-8000-000000000002', 'new@tharmesh.invalid', now());

set local role authenticated;
select set_config('request.jwt.claim.sub', 'f1e00000-0000-4000-8000-000000000001', true);
do $$
begin
    if (select state from public.tharmesh_provision_identity(repeat('A', 122))) is distinct from 'legacy_unknown' then
        raise exception 'Legacy account was admitted';
    end if;
end;
$$;

select set_config('request.jwt.claim.sub', 'f1e00000-0000-4000-8000-000000000002', true);
do $$
begin
    if (select state from public.tharmesh_provision_identity()) is distinct from 'unprovisioned' then
        raise exception 'New account was not admitted';
    end if;
    if (select state from public.tharmesh_provision_identity(repeat('A', 122))) is distinct from 'active' then
        raise exception 'Original registration failed';
    end if;
    if (select state from public.tharmesh_provision_identity(repeat('A', 122))) is distinct from 'active' then
        raise exception 'Same-key retry failed';
    end if;
    if (select state from public.tharmesh_provision_identity(repeat('B', 122))) is distinct from 'recovery_required' then
        raise exception 'Conflicting key was not rejected';
    end if;
    if (select mesh_public_key from public.tharmesh_provision_identity()) is distinct from repeat('A', 122) then
        raise exception 'Original key changed';
    end if;
    begin
        update tharmesh_private.canonical_identities set mesh_public_key = repeat('B', 122);
        raise exception 'Direct writes were allowed';
    exception when insufficient_privilege then null;
    end;
end;
$$;
rollback;
