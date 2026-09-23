-- Cover the audit-log foreign key for efficient administrator lookups and deletes.
create index if not exists admin_audit_logs_actor_id_idx
  on public.admin_audit_logs (actor_id);
