begin;

-- Only the signature-verifying Edge Function may call the original write RPC.
revoke execute on function public.tharmesh_provision_identity(text) from authenticated;

create table tharmesh_private.identity_challenges (
    account_id uuid primary key references auth.users(id) on delete cascade,
    nonce uuid not null,
    mesh_public_key text not null,
    expires_at timestamptz not null
);
alter table tharmesh_private.identity_challenges enable row level security;
revoke all on tharmesh_private.identity_challenges from public, anon, authenticated;

create function public.tharmesh_identity_state()
returns table (state text, mesh_public_key text, generation integer)
language sql security definer set search_path = ''
as $$
    select * from public.tharmesh_provision_identity(null::text);
$$;

create function public.tharmesh_identity_challenge(p_account uuid, p_key text)
returns uuid
language plpgsql security definer set search_path = ''
as $$
declare
    challenge uuid := pg_catalog.gen_random_uuid();
begin
    if p_key is null or length(p_key) <> 122 or p_key !~ '^[A-Za-z0-9_-]+$' then
        raise exception 'Invalid public key' using errcode = '22023';
    end if;
    if not exists (
        select 1 from tharmesh_private.canonical_identities i
        join auth.users u on u.id = i.account_id
        where i.account_id = p_account and u.email_confirmed_at is not null
        and (i.mesh_public_key is null or i.mesh_public_key = p_key)
    ) then
        raise exception 'Identity recovery required' using errcode = '42501';
    end if;
    insert into tharmesh_private.identity_challenges (account_id, nonce, mesh_public_key, expires_at)
        values (p_account, challenge, p_key, clock_timestamp() + interval '2 minutes')
    on conflict (account_id) do update set
        nonce = excluded.nonce, mesh_public_key = excluded.mesh_public_key, expires_at = excluded.expires_at;
    return challenge;
end;
$$;

create function public.tharmesh_identity_finish(p_account uuid, p_nonce uuid, p_key text)
returns table (state text, mesh_public_key text, generation integer)
language plpgsql security definer set search_path = ''
as $$
begin
    delete from tharmesh_private.identity_challenges c
    where c.account_id = p_account and c.nonce = p_nonce and c.mesh_public_key = p_key
          and c.expires_at > clock_timestamp();
    if not found then
        raise exception 'Challenge expired or already consumed' using errcode = '42501';
    end if;
    -- The verifier passes the subject from the validated user token.
    perform set_config('request.jwt.claim.sub', p_account::text, true);
    return query select * from public.tharmesh_provision_identity(p_key);
end;
$$;

revoke all on function public.tharmesh_identity_state() from public, anon, authenticated;
grant execute on function public.tharmesh_identity_state() to authenticated;
revoke all on function public.tharmesh_identity_challenge(uuid,text) from public, anon, authenticated;
revoke all on function public.tharmesh_identity_finish(uuid,uuid,text) from public, anon, authenticated;
grant execute on function public.tharmesh_identity_challenge(uuid,text) to service_role;
grant execute on function public.tharmesh_identity_finish(uuid,uuid,text) to service_role;

commit;
