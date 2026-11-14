begin;

-- Scope the guard to the authenticated caller. Historical accounts keep
-- their existing directory behavior until they can be reconciled safely.
create function public.tharmesh_uses_canonical_identity()
returns boolean
language sql stable security definer set search_path = ''
as $$
    select exists (
        select 1 from tharmesh_private.canonical_identities i
        where i.account_id = auth.uid()
    );
$$;

revoke all on function public.tharmesh_uses_canonical_identity() from public, anon, authenticated;
grant execute on function public.tharmesh_uses_canonical_identity() to authenticated;

-- Deny ordinary directory writes from the moment an account is admitted.
-- This also prevents a legacy client from inserting a device record while
-- first-device provisioning is in progress. A future proof-verified
-- enrollment transaction must write these records through a trusted path.
create policy devices_canonical_insert_guard on public.devices
    as restrictive for insert to authenticated
    with check (not public.tharmesh_uses_canonical_identity());
create policy devices_canonical_update_guard on public.devices
    as restrictive for update to authenticated
    using (not public.tharmesh_uses_canonical_identity())
    with check (not public.tharmesh_uses_canonical_identity());
create policy devices_canonical_delete_guard on public.devices
    as restrictive for delete to authenticated
    using (not public.tharmesh_uses_canonical_identity());

commit;
