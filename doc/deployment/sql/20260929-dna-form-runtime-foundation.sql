-- DNA form Runtime control-plane metadata for PostgreSQL installations.
-- This migration creates metadata only; it does not create or modify any form-owned table.
-- Rollback is destructive: drop the four tables in reverse dependency order only when no
-- Runtime model has been registered.

CREATE TABLE IF NOT EXISTS simpoint_dna_physical_models (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  app_id varchar(64) NOT NULL,
  form_id varchar(64) NOT NULL,
  display_name varchar(128) NOT NULL,
  status varchar(24) NOT NULL,
  latest_version integer NOT NULL,
  active_version integer,
  lock_version bigint NOT NULL DEFAULT 0,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT uk_dna_physical_model_form UNIQUE (tenant_id, app_id, form_id),
  CONSTRAINT ck_dna_physical_model_status CHECK (
    status IN ('DRAFT', 'VALIDATING', 'READY', 'MIGRATING', 'VERIFYING',
               'ACTIVE', 'FAILED', 'RETIRED')
  ),
  CONSTRAINT ck_dna_physical_model_version CHECK (
    latest_version >= 1
    AND (active_version IS NULL OR active_version BETWEEN 1 AND latest_version)
  )
);

CREATE INDEX IF NOT EXISTS idx_dna_physical_model_status
  ON simpoint_dna_physical_models (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_dna_physical_model_updated
  ON simpoint_dna_physical_models (updated_at);

CREATE TABLE IF NOT EXISTS simpoint_dna_physical_model_versions (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  physical_model_id varchar(36) NOT NULL,
  version_number integer NOT NULL,
  contract_version varchar(16) NOT NULL,
  schema_json text NOT NULL,
  schema_checksum varchar(64) NOT NULL,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT fk_dna_physical_version_model FOREIGN KEY (physical_model_id)
    REFERENCES simpoint_dna_physical_models (id),
  CONSTRAINT uk_dna_physical_model_version
    UNIQUE (tenant_id, physical_model_id, version_number),
  CONSTRAINT ck_dna_physical_version_number CHECK (version_number >= 1)
);

CREATE INDEX IF NOT EXISTS idx_dna_physical_model_version_checksum
  ON simpoint_dna_physical_model_versions (physical_model_id, schema_checksum);

CREATE TABLE IF NOT EXISTS simpoint_dna_physical_bindings (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  physical_model_id varchar(36) NOT NULL,
  data_source_id varchar(64) NOT NULL,
  catalog_name varchar(128) NOT NULL DEFAULT '',
  schema_name varchar(128) NOT NULL DEFAULT '',
  table_name varchar(128) NOT NULL,
  isolation_mode varchar(32) NOT NULL,
  lock_version bigint NOT NULL DEFAULT 0,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT fk_dna_physical_binding_model FOREIGN KEY (physical_model_id)
    REFERENCES simpoint_dna_physical_models (id),
  CONSTRAINT uk_dna_physical_binding_model UNIQUE (physical_model_id),
  CONSTRAINT uk_dna_physical_binding_target
    UNIQUE (data_source_id, catalog_name, schema_name, table_name),
  CONSTRAINT ck_dna_physical_binding_isolation CHECK (
    isolation_mode IN ('SHARED_DATABASE', 'DEDICATED_SCHEMA', 'DEDICATED_DATA_SOURCE')
  )
);

CREATE INDEX IF NOT EXISTS idx_dna_physical_binding_tenant
  ON simpoint_dna_physical_bindings (tenant_id, physical_model_id);

CREATE TABLE IF NOT EXISTS simpoint_dna_runtime_audit_events (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  event_type varchar(48) NOT NULL,
  occurred_at timestamp with time zone NOT NULL,
  actor_id varchar(64) NOT NULL,
  physical_model_id varchar(36) NOT NULL,
  app_id varchar(64),
  form_id varchar(64),
  data_source_id varchar(64) NOT NULL,
  model_version integer,
  affected_rows bigint,
  duration_ms bigint NOT NULL,
  outcome varchar(16) NOT NULL,
  error_code varchar(64),
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT fk_dna_runtime_audit_model FOREIGN KEY (physical_model_id)
    REFERENCES simpoint_dna_physical_models (id),
  CONSTRAINT ck_dna_runtime_audit_outcome CHECK (
    outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED')
  ),
  CONSTRAINT ck_dna_runtime_audit_metrics CHECK (
    (affected_rows IS NULL OR affected_rows >= 0) AND duration_ms >= 0
  ),
  CONSTRAINT ck_dna_runtime_audit_error CHECK (
    (outcome = 'SUCCEEDED' AND error_code IS NULL)
    OR (outcome <> 'SUCCEEDED' AND error_code IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS idx_dna_runtime_audit_model
  ON simpoint_dna_runtime_audit_events (tenant_id, physical_model_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_dna_runtime_audit_type
  ON simpoint_dna_runtime_audit_events (event_type, occurred_at);

-- Destructive rollback, execute only after verifying all four tables are empty:
-- DROP TABLE simpoint_dna_runtime_audit_events;
-- DROP TABLE simpoint_dna_physical_bindings;
-- DROP TABLE simpoint_dna_physical_model_versions;
-- DROP TABLE simpoint_dna_physical_models;
