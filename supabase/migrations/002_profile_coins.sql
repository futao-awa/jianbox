alter table public.profiles add column if not exists coins integer not null default 0;
alter table public.profiles add column if not exists checkin_count integer not null default 0;
alter table public.profiles add column if not exists last_checkin_date date;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'profiles_coins_nonnegative') then
    alter table public.profiles add constraint profiles_coins_nonnegative check (coins >= 0);
  end if;
  if not exists (select 1 from pg_constraint where conname = 'profiles_checkin_count_nonnegative') then
    alter table public.profiles add constraint profiles_checkin_count_nonnegative check (checkin_count >= 0);
  end if;
end $$;

create table if not exists public.daily_checkins (
  user_id uuid not null references auth.users(id) on delete cascade,
  checkin_date date not null,
  reward smallint not null check (reward between 1 and 10),
  created_at timestamptz not null default now(),
  primary key (user_id, checkin_date)
);

alter table public.daily_checkins enable row level security;
revoke all on public.daily_checkins from anon, authenticated;
grant select on public.daily_checkins to authenticated;

drop policy if exists "checkin owner read" on public.daily_checkins;
create policy "checkin owner read" on public.daily_checkins for select to authenticated
  using ((select auth.uid()) = user_id);

create or replace function public.jianbox_daily_checkin()
returns table (claimed boolean, reward smallint, balance integer, total_checkins integer, checkin_date date)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_user uuid := auth.uid();
  v_today date := timezone('Asia/Shanghai', now())::date;
  v_reward smallint := (floor(random() * 10) + 1)::smallint;
  v_inserted smallint;
begin
  if v_user is null then raise exception 'authentication required'; end if;

  insert into public.daily_checkins (user_id, checkin_date, reward)
  values (v_user, v_today, v_reward)
  on conflict (user_id, checkin_date) do nothing
  returning daily_checkins.reward into v_inserted;

  if v_inserted is not null then
    update public.profiles
       set coins = coins + v_inserted,
           checkin_count = checkin_count + 1,
           last_checkin_date = v_today
     where id = v_user;
    return query select true, v_inserted, p.coins, p.checkin_count, v_today
      from public.profiles p where p.id = v_user;
  else
    return query select false, d.reward, p.coins, p.checkin_count, v_today
      from public.daily_checkins d join public.profiles p on p.id = d.user_id
     where d.user_id = v_user and d.checkin_date = v_today;
  end if;
end;
$$;

revoke all on function public.jianbox_daily_checkin() from public, anon;
grant execute on function public.jianbox_daily_checkin() to authenticated;

-- The app can read these values but cannot PATCH any reward-related column.
revoke update (coins, checkin_count, last_checkin_date) on public.profiles from authenticated;
