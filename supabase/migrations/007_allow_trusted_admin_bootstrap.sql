-- Allow a trusted server-side workflow (such as SQL Editor / service role) to
-- bootstrap an administrator, while still rejecting attempts from a signed-in
-- client session. Client column grants and RLS continue to prevent self-promotion.

create or replace function public.protect_profile_admin()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if new.is_admin is distinct from old.is_admin and auth.uid() is not null then
    raise exception 'is_admin may only be changed by a trusted administrator workflow';
  end if;
  return new;
end;
$$;

revoke all on function public.protect_profile_admin() from public, anon, authenticated;
