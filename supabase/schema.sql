-- TharMesh account plane. Run in the Supabase SQL editor.
-- The app never sends message bodies or recovery secrets.
-- The anon key is not an admin key. RLS is the authorization.

create table if not exists public.profiles (
    account_id uuid primary key references auth.users (id) on delete cascade,
    handle text not null,
    display_name text not null,
    avatar_url text,
    created_at timestamptz not null default now(),
    constraint profiles_handle_len check (char_length(handle) between 3 and 20),
    constraint profiles_handle_charset check (handle ~ '^[a-z0-9_]+$'),
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

create policy profiles_select_own on public.profiles
    for select using (auth.uid() = account_id);
create policy profiles_insert_own on public.profiles
    for insert with check (auth.uid() = account_id);
create policy profiles_update_own on public.profiles
    for update using (auth.uid() = account_id) with check (auth.uid() = account_id);

create policy handles_select_own on public.handles
    for select using (auth.uid() = account_id);
create policy handles_insert_own on public.handles
    for insert with check (auth.uid() = account_id);
-- Handle text is the primary key. Updates are not granted, so a handle cannot be renamed
-- onto someone else's row or swapped after insert.

create policy devices_select_own on public.devices
    for select using (auth.uid() = account_id);
create policy devices_insert_own on public.devices
    for insert with check (auth.uid() = account_id);
create policy devices_update_own on public.devices
    for update using (auth.uid() = account_id) with check (auth.uid() = account_id);

create policy recovery_select_own on public.recovery_blobs
    for select using (auth.uid() = account_id);
create policy recovery_insert_own on public.recovery_blobs
    for insert with check (auth.uid() = account_id and kdf = 'scrypt');
create policy recovery_update_own on public.recovery_blobs
    for update using (auth.uid() = account_id) with check (auth.uid() = account_id and kdf = 'scrypt');
