-- Workflow-owned display projection. It never contains account credentials or grants.
create table simpoint_workflow_member_label_state (
  tenant_id varchar(255) primary key,
  active_generation varchar(36),
  source_epoch varchar(36),
  source_revision bigint check (source_revision >= 0),
  tenant_active boolean,
  observed_epoch varchar(36),
  observed_revision bigint check (observed_revision >= 0),
  build_generation varchar(36),
  build_epoch varchar(36),
  build_revision bigint check (build_revision >= 0),
  build_cursor varchar(255),
  build_pages integer not null default 0 check (build_pages >= 0),
  build_rows bigint not null default 0 check (build_rows >= 0),
  build_complete boolean not null default false,
  build_tenant_active boolean,
  lease_owner varchar(36),
  lease_until timestamp with time zone,
  updated_at timestamp with time zone not null default current_timestamp,
  constraint ck_workflow_member_labels_observed check (
    (observed_epoch is null and observed_revision is null)
    or (observed_epoch is not null and observed_revision is not null)),
  constraint ck_workflow_member_labels_active check (
    (active_generation is null and source_epoch is null and source_revision is null and tenant_active is null)
    or (active_generation is not null and source_epoch is not null and source_revision is not null and tenant_active is not null)),
  constraint ck_workflow_member_labels_build check (
    (build_generation is null and build_epoch is null and build_revision is null and build_cursor is null
      and build_pages=0 and build_rows=0 and not build_complete and build_tenant_active is null)
    or (build_generation is not null and build_epoch is not null and build_revision is not null
      and ((build_pages=0 and build_rows=0 and build_cursor is null and not build_complete and build_tenant_active is null)
        or (build_pages>0 and build_tenant_active is not null
          and ((build_complete and build_cursor is null) or (not build_complete and build_cursor is not null)))))),
  constraint ck_workflow_member_labels_lease check (
    (lease_owner is null and lease_until is null)
    or (lease_owner is not null and lease_until is not null and build_generation is not null)),
  constraint ck_workflow_member_labels_generations check (
    active_generation is null or build_generation is null or active_generation <> build_generation)
);

create table simpoint_workflow_member_labels (
  tenant_id varchar(255) not null,
  generation varchar(36) not null,
  user_id varchar(255) not null,
  name varchar(767),
  primary key (tenant_id, generation, user_id),
  constraint fk_workflow_member_label_tenant foreign key (tenant_id)
    references simpoint_workflow_member_label_state(tenant_id)
);
