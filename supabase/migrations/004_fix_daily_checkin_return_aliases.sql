-- Qualify the existing row values and alias every return expression. Without
-- this, PL/pgSQL can treat the output column checkin_date as an ambiguous
-- reference to daily_checkins.checkin_date.
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
       set coins = profiles.coins + v_inserted,
           checkin_count = profiles.checkin_count + 1,
           last_checkin_date = v_today
     where profiles.id = v_user;
    return query
      select true, v_inserted, p.coins, p.checkin_count, v_today
        from public.profiles as p
       where p.id = v_user;
  else
    return query
      select false, d.reward, p.coins, p.checkin_count, d.checkin_date
        from public.daily_checkins as d
        join public.profiles as p on p.id = d.user_id
       where d.user_id = v_user
         and d.checkin_date = v_today;
  end if;
end;
$$;

revoke all on function public.jianbox_daily_checkin() from public, anon;
grant execute on function public.jianbox_daily_checkin() to authenticated;
