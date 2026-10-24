-- Refresh intent is durable and independent of both staging-generation and event-delivery leases.
create table simpoint_workflow_member_label_refresh (
  tenant_id varchar(255) primary key references simpoint_workflow_member_label_state(tenant_id) on delete cascade,
  generation bigint not null default 0 check (generation >= 0),
  due_at timestamptz not null default now(),
  lease_token varchar(36),
  lease_until timestamptz,
  failures integer not null default 0 check (failures between 0 and 20),
  last_error varchar(64),
  checked_at timestamptz,
  check ((lease_token is null and lease_until is null)
      or (lease_token is not null and lease_until is not null))
);
create index idx_workflow_member_label_refresh_due
  on simpoint_workflow_member_label_refresh(due_at, tenant_id);
insert into simpoint_workflow_member_label_refresh(tenant_id)
  select tenant_id from simpoint_workflow_member_label_state;

create function simplepoint_track_member_label_refresh() returns trigger
language plpgsql as $$
begin
  execute format('insert into %I.simpoint_workflow_member_label_refresh(tenant_id)'
    || ' values ($1) on conflict do nothing', TG_TABLE_SCHEMA) using NEW.tenant_id;
  return NEW;
end;
$$;
create trigger simplepoint_member_labels_track_refresh
  after insert on simpoint_workflow_member_label_state
  for each row execute function simplepoint_track_member_label_refresh();
