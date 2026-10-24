-- PostgreSQL schema owned by identity.
create table simpoint_ac_data_scope (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    type varchar(30) not null check ((type in ('SELF','EXPRESSION','CUSTOM','DEPT','DEPT_AND_BELOW','ALL'))),
    name varchar(100) not null,
    description varchar(200),
    resource_code varchar(200),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    expression text,
    id varchar(255) not null,
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_ac_data_scope_dept (
    data_scope_id varchar(255) not null,
    dept_id varchar(255) not null,
    primary key (data_scope_id, dept_id)
);

create table simpoint_ac_field_scope (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    name varchar(100) not null,
    description varchar(200),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_ac_field_scope_entry (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    access varchar(20) not null check ((access in ('HIDDEN','MASKED','VISIBLE','EDITABLE'))),
    field varchar(100) not null,
    resource varchar(200) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    field_scope_id varchar(255) not null,
    id varchar(255) not null,
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uq_field_scope_entry unique (field_scope_id, resource, field)
);

create table simpoint_ac_micro_modules (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    plugin_artifact_sha256 varchar(64),
    plugin_version varchar(64),
    remote_version varchar(64),
    plugin_id varchar(128),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    display_name varchar(255) not null,
    entry varchar(255) not null unique,
    id varchar(255) not null,
    service_name varchar(255),
    updated_by varchar(255),
    description oid,
    primary key (id)
);

create table simpoint_ac_micro_services (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    last_heartbeat timestamp(6),
    updated_at timestamp(6) with time zone,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    display_name varchar(255),
    id varchar(255) not null,
    service_name varchar(255),
    updated_by varchar(255),
    description oid,
    primary key (id)
);

create table simpoint_ac_resource_ancestors_rel (
    ancestor_id varchar(255) not null,
    child_id varchar(255) not null,
    primary key (ancestor_id, child_id)
);

create table simpoint_ac_resources (
    danger boolean,
    disabled boolean not null,
    grantable boolean not null,
    public_access boolean not null,
    require_org_tenant boolean not null,
    sort integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    method varchar(16),
    route_kind varchar(32),
    type varchar(32) not null check ((type in ('GROUP','MODULE','PAGE','FEATURE','ACTION','API'))),
    parent_id varchar(36),
    scope_types varchar(64) default 'SYSTEM' not null,
    icon varchar(100),
    name varchar(100) not null,
    code varchar(120) not null unique,
    label varchar(120),
    resource_alias varchar(120),
    title varchar(120),
    plugin_id varchar(128),
    component varchar(160),
    path varchar(200) unique,
    pattern varchar(240),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    description varchar(255),
    id varchar(255) not null,
    permission_metadata text,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_ac_role_resource_grants (
    page_policy boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    role_id varchar(36) not null,
    resource_code varchar(120) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    data_scope_id varchar(255),
    field_scope_id varchar(255),
    id varchar(255) not null,
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_ac_role_scope_binding (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    revision bigint,
    updated_at timestamp(6) with time zone,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    data_scope_id varchar(255),
    field_scope_id varchar(255),
    id varchar(255) not null,
    role_id varchar(255) not null,
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uq_role_scope_binding unique (tenant_id, role_id)
);

create table simpoint_ac_roles (
    priority integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    authority varchar(255) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    description varchar(255),
    id varchar(255) not null,
    role_name varchar(255) not null,
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_ac_route_domains (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    display_name varchar(100) not null,
    domain_name varchar(100) not null,
    region varchar(100),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    module_name varchar(255) unique,
    updated_by varchar(255),
    description oid,
    primary key (id)
);

create table simpoint_ac_user_preferences (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    user_id varchar(64) not null,
    preference_key varchar(200) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    preference_value TEXT,
    primary key (id),
    constraint uq_user_preference_key unique (user_id, preference_key)
);

create table simpoint_ac_user_role_rel (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    role_id varchar(255),
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    user_id varchar(255),
    primary key (id)
);

create table simpoint_ac_users (
    account_non_expired boolean,
    account_non_locked boolean,
    credentials_non_expired boolean,
    email_verified boolean,
    enabled boolean,
    phone_number_verified boolean,
    super_admin boolean,
    two_factor_enabled boolean,
    authorization_version bigint not null default 0,
    birthdate timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    two_factor_pending_until timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    phone_number varchar(18),
    org_id varchar(36),
    email varchar(64) unique,
    two_factor_secret varchar(512),
    address varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    family_name varchar(255),
    gender varchar(255),
    given_name varchar(255),
    id varchar(255) not null,
    locale varchar(255),
    middle_name varchar(255),
    name varchar(255),
    nickname varchar(255),
    password varchar(255),
    picture varchar(255),
    preferred_username varchar(255),
    profile varchar(255),
    updated_by varchar(255),
    website varchar(255),
    zoneinfo varchar(255),
    decorator oid,
    primary key (id)
);

create table simpoint_platform_identity (
    user_id varchar(255) not null,
    primary key (user_id)
);

create table simpoint_platform_identity_role (
    role_code varchar(255) not null check ((role_code in ('PLATFORM_ADMIN','ACCOUNT_ADMIN','AUDITOR'))),
    user_id varchar(255) not null,
    primary key (user_id, role_code)
);

create table simpoint_platform_security_audit (
    occurred_at timestamp(6) with time zone,
    reason varchar(500),
    after_state varchar(2000),
    before_state varchar(2000),
    action varchar(255),
    actor_id varchar(255),
    id varchar(255) not null,
    target_id varchar(255),
    primary key (id)
);

create table simpoint_platform_security_state (
    id varchar(255) not null,
    primary key (id)
);

create table simpoint_platform_step_up (
    failures integer not null default 0,
    blocked_until timestamp(6) with time zone,
    last_totp_step bigint,
    user_id varchar(255) not null,
    primary key (user_id)
);

create table simpoint_saas_application_resource_rel (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    application_code varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    resource_code varchar(255),
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_saas_applications (
    enabled boolean,
    sort integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    code varchar(128) not null unique,
    name varchar(128) not null,
    description varchar(512),
    homepage varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_saas_dictionaries (
    enabled boolean,
    sort integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    code varchar(128) not null unique,
    name varchar(128) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_saas_dictionary_items (
    enabled boolean,
    sort integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    dictionary_code varchar(128) not null,
    item_value varchar(128) not null,
    name varchar(128) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    i18n_key varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uk_simpoint_saas_dictionary_item_code_value unique (dictionary_code, item_value)
);

create table simpoint_saas_organizations (
    enabled boolean not null,
    sort integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    parent_id varchar(36),
    organization_type varchar(64),
    code varchar(128) not null,
    name varchar(128) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    tenant_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id),
    constraint uk_simpoint_saas_org_tenant_code unique (tenant_id, code)
);

create table simpoint_saas_package_application_rel (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    application_code varchar(255),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    package_code varchar(255),
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_saas_packages (
    duration_days integer,
    enabled boolean,
    price numeric(19,2),
    sort integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    code varchar(128) not null unique,
    name varchar(128) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_saas_tenant_package_rel (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    package_code varchar(255),
    tenant_id varchar(255),
    updated_by varchar(255),
    primary key (id)
);

CREATE TABLE simpoint_saas_tenant_resources (
  tenant_id varchar(36) NOT NULL,
  id varchar(36) NOT NULL,
  platform_resource_id varchar(36),
  target_resource_id varchar(36),
  managed_owner varchar(64),
  managed_key varchar(160),
  managed_host_resource_id varchar(36),
  permission_metadata text,
  parent_id varchar(36),
  code varchar(120) NOT NULL,
  name varchar(100) NOT NULL,
  type varchar(32) NOT NULL CHECK (type IN ('GROUP','MODULE','PAGE','FEATURE','ACTION','API')),
  path varchar(200),
  component varchar(160),
  route_kind varchar(32),
  icon varchar(100),
  sort integer,
  description varchar(255),
  enabled boolean NOT NULL DEFAULT true,
  grantable boolean NOT NULL DEFAULT false,
  local_modified boolean NOT NULL DEFAULT false,
  manual_disabled boolean NOT NULL DEFAULT false,
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  lifecycle_generation bigint NOT NULL DEFAULT 0,
  formally_offline boolean NOT NULL DEFAULT false,
  CONSTRAINT pk_tenant_resources PRIMARY KEY (tenant_id, id),
  CONSTRAINT uq_tenant_resource_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_tenant_resource_source UNIQUE (tenant_id, platform_resource_id),
  CONSTRAINT chk_tenant_resource_managed_identity CHECK (
    (managed_owner IS NULL AND managed_key IS NULL)
    OR (managed_owner IS NOT NULL AND managed_key IS NOT NULL
      AND platform_resource_id IS NULL AND target_resource_id IS NULL)
  )
);

CREATE INDEX idx_tenant_resources_parent
  ON simpoint_saas_tenant_resources (tenant_id, parent_id, sort, name);
CREATE INDEX idx_tenant_resources_target
  ON simpoint_saas_tenant_resources (tenant_id, target_resource_id);
CREATE UNIQUE INDEX uq_tenant_resource_managed_key
  ON simpoint_saas_tenant_resources (tenant_id, managed_owner, managed_key)
  WHERE managed_owner IS NOT NULL;


create table simpoint_saas_tenant_user_rel (
    enabled boolean not null default true,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    revision bigint not null default 0,
    updated_at timestamp(6) with time zone,
    user_type varchar(32) not null default 'INTERNAL',
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    org_id varchar(255),
    tenant_id varchar(255),
    updated_by varchar(255),
    user_id varchar(255),
    primary key (id),
    constraint uk_tenant_member_user unique (tenant_id, user_id)
);

create table simpoint_saas_tenants (
    authorization_version bigint not null default 0,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    resource_mode varchar(16) not null default 'PLATFORM' check ((resource_mode in ('PLATFORM','CUSTOM'))),
    tenant_type varchar(32) not null check ((tenant_type in ('PERSONAL','ORGANIZATION'))),
    name varchar(128) not null unique,
    description varchar(512),
    background_image varchar(1024),
    logo varchar(1024),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    owner_id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create index idx_resource_ancestor_children
   on simpoint_ac_resource_ancestors_rel (ancestor_id, child_id);

create index idx_role_grant_overview
   on simpoint_ac_role_resource_grants (tenant_id, role_id, deleted_at, resource_code);

create index idx_role_tenant_catalog
   on simpoint_ac_roles (tenant_id, deleted_at, role_name, id);

create index idx_user_preference_user_id
   on simpoint_ac_user_preferences (user_id);

create index idx_user_role_overview
   on simpoint_ac_user_role_rel (tenant_id, role_id, deleted_at, user_id);

create index idx_email
   on simpoint_ac_users (email);

create index idx_phone
   on simpoint_ac_users (phone_number);

create index idx_resource_application_license
   on simpoint_saas_application_resource_rel (resource_code, application_code, deleted_at);

create index idx_application_package_license
   on simpoint_saas_package_application_rel (application_code, package_code, deleted_at);

create index idx_tenant_package_license
   on simpoint_saas_tenant_package_rel (tenant_id, package_code, deleted_at);

alter table simpoint_ac_data_scope_dept
   add constraint fk_data_scope_dept_scope
   foreign key (data_scope_id)
   references simpoint_ac_data_scope;

alter table simpoint_platform_identity_role
   add constraint fk_platform_identity_role_identity
   foreign key (user_id)
   references simpoint_platform_identity;

-- Domain guarantees previously supplied by additive production migrations.
ALTER TABLE simpoint_platform_identity
  ADD CONSTRAINT fk_platform_identity_user FOREIGN KEY (user_id) REFERENCES simpoint_ac_users (id);
ALTER TABLE simpoint_platform_step_up
  ADD CONSTRAINT fk_platform_step_up_user FOREIGN KEY (user_id) REFERENCES simpoint_ac_users (id);
CREATE INDEX ix_platform_audit_time ON simpoint_platform_security_audit (occurred_at DESC, id);
CREATE INDEX ix_platform_audit_target ON simpoint_platform_security_audit (target_id, occurred_at DESC);
CREATE INDEX idx_data_scope_tenant_page ON simpoint_ac_data_scope (tenant_id, resource_code)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_tenant_resource_managed_parent ON simpoint_saas_tenant_resources
  (tenant_id, managed_owner, parent_id) WHERE managed_owner IS NOT NULL;
CREATE INDEX idx_tenant_member_org_page ON simpoint_saas_tenant_user_rel (tenant_id, org_id, user_id)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_organization_tenant_parent ON simpoint_saas_organizations (tenant_id, parent_id, id)
  WHERE deleted_at IS NULL;

-- Structural serialization anchor only: installation never creates users or grants roles.
INSERT INTO simpoint_platform_security_state (id) VALUES ('platform');
