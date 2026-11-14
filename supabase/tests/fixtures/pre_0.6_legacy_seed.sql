-- Pre-0.6 legacy plane: base schema.sql state with typical historical rows.
-- Apply BEFORE forward migrations 001–013 (upgrade-with-data test).

insert into auth.users (id, email_confirmed_at)
values ('a1000000-0000-4000-8000-000000000101', now())
on conflict do nothing;

insert into public.profiles (account_id, handle, display_name)
values ('a1000000-0000-4000-8000-000000000101', 'legacy_user', 'Legacy User')
on conflict do nothing;

insert into public.handles (handle, account_id)
values ('legacy_user', 'a1000000-0000-4000-8000-000000000101')
on conflict do nothing;

insert into public.devices (account_id, device_id, mesh_public_key, device_public_key, certificate)
values (
  'a1000000-0000-4000-8000-000000000101',
  'phone-original',
  repeat('A', 122),
  repeat('B', 122),
  'cert:legacy-original'
)
on conflict do nothing;

insert into public.recovery_blobs (
  account_id, kdf, n, r, p, salt_b64, iv_b64, ciphertext_b64
)
values (
  'a1000000-0000-4000-8000-000000000101',
  'scrypt', 16384, 8, 1,
  'c2FsdA==', 'aXY=', 'Y2lwaGVy'
)
on conflict do nothing;
