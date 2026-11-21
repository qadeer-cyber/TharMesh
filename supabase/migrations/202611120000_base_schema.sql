-- Base account-plane schema, tracked as a migration.
--
-- supabase/schema.sql has always been pasted into the Supabase SQL editor by hand
-- (see its own header), so it is not part of the migration history and
-- `supabase db push` does NOT install it. On a project that has never had it
-- applied, a migration-only push fails at
-- 202611120003_identity_device_guard.sql with
--   ERROR: relation "public.devices" does not exist
-- after 0001 and 0002 have already been recorded as applied, leaving the
-- migration history half-written and needing repair.
--
-- This file is generated from supabase/schema.sql with one change: every
-- `create policy` is preceded by `drop policy if exists`, so the file is
-- idempotent and safe both on a fresh project and on one where schema.sql was
-- already applied by hand. SupabaseSchemaBootstrapParityTest asserts it stays
-- in step with supabase/schema.sql.
--
-- Generated 2026-11-21. Do not edit by hand: change supabase/schema.sql and
-- regenerate.

-- TharMesh account plane. Run in the Supabase SQL editor.
-- The app never sends message bodies or recovery secrets.
-- The anon key is not an admin key. RLS is the authorization.
-- GoTrue rate limits are configured in the Supabase dashboard, not in this file.
-- The app also applies AuthRateLimiter before it calls GoTrue.

create table if not exists public.profiles (
    account_id uuid primary key references auth.users (id) on delete cascade,
    handle text not null,
    display_name text not null,
    avatar_url text,
    created_at timestamptz not null default now(),
    constraint profiles_handle_len check (char_length(handle) between 3 and 20),
    constraint profiles_handle_charset check (handle ~ '^[a-z0-9_]+$'),
    constraint profiles_handle_unique unique (handle),
    constraint profiles_avatar_https check (
        avatar_url is null or avatar_url like 'https://%'
    )
);

create table if not exists public.handles (
    handle text primary key,
    account_id uuid not null unique references auth.users (id) on delete cascade,
    constraint handles_charset check (handle ~ '^[a-z0-9_]+$')
);

create table if not exists public.devices (
    account_id uuid not null references auth.users (id) on delete cascade,
    device_id text not null,
    mesh_public_key text not null,
    device_public_key text not null,
    certificate text not null,
    created_at timestamptz not null default now(),
    primary key (account_id, device_id)
);

-- Ciphertext only. Do not add a column for key material or for the recovery secret.
create table if not exists public.recovery_blobs (
    account_id uuid primary key references auth.users (id) on delete cascade,
    kdf text not null,
    n integer not null check (n >= 16384),
    r integer not null check (r >= 8),
    p integer not null check (p >= 1),
    salt_b64 text not null,
    iv_b64 text not null,
    ciphertext_b64 text not null,
    updated_at timestamptz not null default now(),
    constraint recovery_kdf_scrypt check (kdf = 'scrypt')
);

alter table public.profiles enable row level security;
alter table public.handles enable row level security;
alter table public.devices enable row level security;
alter table public.recovery_blobs enable row level security;

drop policy if exists profiles_select_own on public.profiles;
create policy profiles_select_own on public.profiles
    for select using (auth.uid() = account_id);
-- Directory lookup. Profiles hold handle and display name only, not recovery material.
drop policy if exists profiles_select_directory on public.profiles;
create policy profiles_select_directory on public.profiles
    for select using (auth.uid() is not null);
drop policy if exists profiles_insert_own on public.profiles;
create policy profiles_insert_own on public.profiles
    for insert with check (auth.uid() = account_id);
drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles
    for update using (auth.uid() = account_id) with check (auth.uid() = account_id);

drop policy if exists handles_select_own on public.handles;
create policy handles_select_own on public.handles
    for select using (auth.uid() = account_id);
drop policy if exists handles_insert_own on public.handles;
create policy handles_insert_own on public.handles
    for insert with check (auth.uid() = account_id);
-- Handle text is the primary key. Updates are not granted, so a handle cannot be renamed
-- onto someone else's row or swapped after insert.

drop policy if exists devices_select_own on public.devices;
create policy devices_select_own on public.devices
    for select using (auth.uid() = account_id);
drop policy if exists devices_insert_own on public.devices;
create policy devices_insert_own on public.devices
    for insert with check (auth.uid() = account_id);
drop policy if exists devices_update_own on public.devices;
create policy devices_update_own on public.devices
    for update using (auth.uid() = account_id) with check (auth.uid() = account_id);

-- recovery_blobs: SELECT-only for authenticated after migration 009+. Inserts/updates go through Edge + *_svc RPCs.
drop policy if exists recovery_select_own on public.recovery_blobs;
create policy recovery_select_own on public.recovery_blobs
    for select using (auth.uid() = account_id);
