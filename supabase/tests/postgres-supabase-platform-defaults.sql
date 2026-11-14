-- Supabase platform baseline for public schema objects (local PG stub only).
-- Source: Supabase postgres image init — default API role grants on user tables
-- before project-specific REVOKEs in migrations (e.g. 009 recovery_blobs).
-- Do not re-apply after migrations; runner applies this once after schema.sql.
grant usage on schema public to postgres, anon, authenticated, service_role;
grant all on all tables in schema public to postgres, service_role;
grant select, insert, update, delete on all tables in schema public to anon, authenticated;
grant usage, select on all sequences in schema public to anon, authenticated, service_role;
-- Functions created in schema.sql (if any) only; migrations 001+ grant per-function.
grant execute on all functions in schema public to anon, authenticated, service_role;
grant insert on auth.users to service_role;
