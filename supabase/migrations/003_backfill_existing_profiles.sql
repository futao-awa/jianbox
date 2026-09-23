-- Accounts created before the profile trigger was installed need one
-- idempotent backfill so they can use profile reads and daily check-in.
insert into public.profiles (id, display_name)
select u.id,
       coalesce(nullif(u.raw_user_meta_data->>'display_name', ''), split_part(coalesce(u.email, ''), '@', 1))
from auth.users u
on conflict (id) do nothing;
