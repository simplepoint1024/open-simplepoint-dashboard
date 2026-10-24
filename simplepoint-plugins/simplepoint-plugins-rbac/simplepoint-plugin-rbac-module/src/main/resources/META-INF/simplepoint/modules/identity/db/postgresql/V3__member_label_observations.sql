-- Remember real tenant IDs, including deleted/truncated tenants, for bounded durable invalidation.
-- No names, credentials or grants are retained here. Original source triggers still own revision.
create table simpoint_identity_member_label_observations (
  tenant_id varchar(255) primary key,
  observed_epoch varchar(36),
  observed_revision bigint,
  observed_at timestamptz,
  check ((observed_epoch is null and observed_revision is null and observed_at is null)
      or (observed_epoch is not null and observed_revision is not null and observed_revision >= 0 and observed_at is not null))
);
insert into simpoint_identity_member_label_observations(tenant_id) select id from simpoint_saas_tenants;
create index idx_member_label_observation_due
  on simpoint_identity_member_label_observations(observed_at nulls first, tenant_id);

create function simplepoint_track_member_label_tenant() returns trigger
language plpgsql as $$
begin
  execute format('insert into %I.simpoint_identity_member_label_observations(tenant_id)'
    || ' values ($1) on conflict do nothing', TG_TABLE_SCHEMA) using NEW.id;
  return NEW;
end;
$$;
create trigger simplepoint_member_labels_track_tenant
  after insert or update of id on simpoint_saas_tenants
  for each row execute function simplepoint_track_member_label_tenant();

-- Avoid scanning every tracked tenant on idle polls. Set only after all observations commit.
alter table simpoint_identity_member_label_revision add column notification_epoch varchar(36);
alter table simpoint_identity_member_label_revision add column notification_revision bigint;
alter table simpoint_identity_member_label_revision add column notification_complete boolean not null default false;
alter table simpoint_identity_member_label_revision add constraint ck_member_label_notification
  check ((notification_epoch is null and notification_revision is null and not notification_complete)
      or (notification_epoch is not null and notification_revision is not null and notification_revision >= 0));
