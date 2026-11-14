-- Applied by postgres-migrate-runner before forward migrations (not migration 001-013).
CREATE SCHEMA IF NOT EXISTS tharmesh_private;

CREATE TABLE IF NOT EXISTS tharmesh_private.schema_migrations (
    version text PRIMARY KEY,
    filename text NOT NULL UNIQUE,
    applied_at timestamptz NOT NULL DEFAULT now()
);
