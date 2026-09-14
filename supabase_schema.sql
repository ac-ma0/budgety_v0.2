-- Run this once in the Supabase SQL editor. The app only uses the publishable key.
create table if not exists public.budget_sync_records (
    user_id uuid not null references auth.users(id) on delete cascade,
    record_key text not null,
    record_type text not null,
    payload jsonb not null,
    updated_at timestamptz not null,
    deleted boolean not null default false,
    primary key (user_id, record_key)
);
create or replace function public.keep_latest_budget_record()
returns trigger language plpgsql as $$
begin
    if new.updated_at < old.updated_at then
        return old;
    end if;
    return new;
end $$;
drop trigger if exists budget_sync_latest_wins on public.budget_sync_records;
create trigger budget_sync_latest_wins before update on public.budget_sync_records
for each row execute function public.keep_latest_budget_record();
alter table public.budget_sync_records enable row level security;
drop policy if exists "users manage their budget records" on public.budget_sync_records;
create policy "users manage their budget records"
    on public.budget_sync_records for all
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

create table if not exists public.audit_logs (
    user_id uuid not null references auth.users(id) on delete cascade,
    log_key text not null,
    action text not null,
    details text not null,
    log_date text not null,
    budget_user_id integer not null,
    updated_at timestamptz not null,
    deleted boolean not null default false,
    primary key (user_id, log_key)
);
alter table public.audit_logs
    add column if not exists budget_user_id integer;
-- Existing deployments may have added this column as nullable.  Numeric local
-- ids are device-local, so legacy/remote rows are safely attributed to the
-- account's first local id and are remapped by the Android restore path.
update public.audit_logs set budget_user_id = 1 where budget_user_id is null;
alter table public.audit_logs alter column budget_user_id set default 1;
alter table public.audit_logs alter column budget_user_id set not null;
create or replace function public.keep_latest_audit_log()
returns trigger language plpgsql as $$
begin
    if new.updated_at < old.updated_at then return old; end if;
    return new;
end $$;
drop trigger if exists audit_logs_latest_wins on public.audit_logs;
create trigger audit_logs_latest_wins before update on public.audit_logs
for each row execute function public.keep_latest_audit_log();
alter table public.audit_logs enable row level security;
drop policy if exists "users manage their audit logs" on public.audit_logs;
create policy "users manage their audit logs" on public.audit_logs for all
    using (auth.uid() = user_id) with check (auth.uid() = user_id);
