-- Allow the Android client to read both stable and beta releases.
-- Publication, time windows, and administrative writes remain protected by RLS
-- and the admin-console Edge Function.
drop policy if exists "published updates are public" on public.app_updates;
create policy "published updates are public"
  on public.app_updates
  for select
  to anon, authenticated
  using (is_published = true and channel in ('stable', 'beta'));
