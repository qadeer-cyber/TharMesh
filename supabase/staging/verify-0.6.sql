-- Staging verification (0.6): run as service_role after migrations 001–013.
-- identity_admission.enabled must remain false in staging.

\set ON_ERROR_STOP on

select 'admission_off' as check,
       (select not enabled from tharmesh_private.identity_admission where singleton limit 1) as ok;

select 'recovery_blobs_no_authenticated_write' as check,
       not exists (
         select 1 from information_schema.role_table_grants g
         where g.table_name = 'recovery_blobs'
           and g.grantee = 'authenticated'
           and g.privilege_type in ('INSERT', 'UPDATE', 'DELETE')
       ) as ok;

select 'device_revoke_svc_session_arg' as check,
       exists (
         select 1 from pg_proc p
         join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public' and p.proname = 'tharmesh_device_revoke_svc'
           and pg_get_function_identity_arguments(p.oid) = 'p_account uuid, p_challenge_id uuid, p_session_id text'
       ) as ok;

select 'authenticated_revoke_fail_closed' as check,
       coalesce(
         (
           select pg_get_functiondef(p.oid) like '%fail%closed%' or pg_get_functiondef(p.oid) like '%42501%'
           from pg_proc p join pg_namespace n on n.oid = p.pronamespace
           where n.nspname = 'public' and p.proname = 'tharmesh_device_revoke'
             and pg_get_function_identity_arguments(p.oid) = 'p_device_id text, p_grant_id uuid, p_session_id text'
           limit 1
         ),
         false
       ) as ok;

select 'recovery_rotate_svc_exists' as check,
       exists (
         select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public' and p.proname = 'tharmesh_recovery_rotate_commit_svc'
       ) as ok;
