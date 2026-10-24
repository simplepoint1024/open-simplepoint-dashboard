-- PostgreSQL control metadata owned by dna-federation. Business target tables are not installed here.
create table simpoint_dna_data_assets (
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    asset_type varchar(32) not null,
    catalog_id varchar(64) not null,
    code varchar(128) not null unique,
    name varchar(128) not null,
    owner varchar(128),
    schema_pattern varchar(256),
    table_pattern varchar(256),
    description varchar(512),
    tags varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_dna_data_quality_rules (
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    last_run_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    last_run_status varchar(16),
    severity varchar(16) not null,
    rule_type varchar(32) not null,
    catalog_id varchar(64) not null,
    code varchar(128) not null unique,
    name varchar(128) not null,
    target_column varchar(128),
    expected_value varchar(256),
    target_table varchar(256) not null,
    description varchar(512),
    last_run_message varchar(2048),
    check_sql varchar(4096),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_dna_federation_catalogs (
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    catalog_type varchar(32),
    code varchar(128) not null unique,
    name varchar(128) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_dna_federation_jdbc_users (
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    user_phone_number varchar(32),
    catalog_id varchar(64) not null,
    user_email varchar(64),
    user_id varchar(64) not null,
    catalog_code varchar(128) not null,
    catalog_name varchar(128) not null,
    user_display_name varchar(128) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    operation_permissions varchar(1024) array not null,
    primary key (id)
);

create table simpoint_dna_federation_query_audits (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    executed_at timestamp(6) with time zone,
    execution_time_ms bigint,
    result_rows bigint,
    updated_at timestamp(6) with time zone,
    status varchar(32),
    catalog_code varchar(128),
    executed_by varchar(128),
    view_code varchar(128),
    error_message varchar(4000),
    pushdown_summary varchar(4000),
    query_text varchar(12000),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_dna_federation_query_policies (
    allow_cross_source_join boolean,
    allow_sql_console boolean,
    enabled boolean,
    max_rows integer,
    timeout_ms integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    catalog_id varchar(64) not null,
    code varchar(128) not null unique,
    name varchar(128) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_dna_federation_query_templates (
    enabled boolean,
    is_public boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    catalog_id varchar(64),
    code varchar(128) not null unique,
    name varchar(128) not null,
    description varchar(512),
    tags varchar(512),
    sql_text varchar(12000) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_dna_federation_schemas (
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    catalog_id varchar(64) not null,
    code varchar(128) not null unique,
    name varchar(128) not null,
    description varchar(512),
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

create table simpoint_dna_federation_views (
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    schema_id varchar(64) not null,
    code varchar(128) not null unique,
    name varchar(128) not null,
    description varchar(512),
    definition_sql varchar(12000) not null,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    id varchar(255) not null,
    updated_by varchar(255),
    primary key (id)
);

-- Saved query-model drafts. Apply before starting the DNA service on installations without JPA DDL update.
CREATE TABLE simpoint_dna_data_models (
  id varchar(36) PRIMARY KEY,
  name varchar(120) NOT NULL,
  description varchar(500),
  model_type varchar(24) NOT NULL,
  data_source_id varchar(64),
  sql_text text,
  status varchar(20) NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT ck_dna_data_model_type CHECK (model_type IN ('DNA_SQL', 'NATIVE_SQL')),
  CONSTRAINT ck_dna_data_model_source CHECK (model_type <> 'NATIVE_SQL' OR data_source_id IS NOT NULL)
);
CREATE INDEX idx_dna_model_updated ON simpoint_dna_data_models (deleted_at, updated_at);
CREATE INDEX idx_dna_model_type ON simpoint_dna_data_models (model_type, deleted_at);

