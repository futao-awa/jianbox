-- Cover release and announcement creator foreign keys for joins and deletes.

create index if not exists app_updates_created_by_idx
  on public.app_updates (created_by)
  where created_by is not null;

create index if not exists app_announcements_created_by_idx
  on public.app_announcements (created_by)
  where created_by is not null;
