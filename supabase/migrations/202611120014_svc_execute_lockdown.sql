-- Forward-only: after 010–013 DROP/CREATE, new functions inherit PG PUBLIC EXECUTE.
-- Re-lock authenticated/anon; restore service_role EXECUTE on all public routines.
begin;

do $$
declare
    r record;
begin
    for r in
        select n.nspname as nsp, p.proname as fn, p.oid as oid
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'public' and p.proname like '%\_svc' escape '\'
    loop
        execute format('revoke all on function %s from public, anon, authenticated', r.oid::regprocedure);
    end loop;
    for r in
        select p.oid as oid
        from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where n.nspname = 'tharmesh_private' and p.prokind = 'f'
    loop
        execute format('revoke all on function %s from public, anon, authenticated', r.oid::regprocedure);
    end loop;
end $$;

grant execute on all functions in schema public to service_role;

commit;
