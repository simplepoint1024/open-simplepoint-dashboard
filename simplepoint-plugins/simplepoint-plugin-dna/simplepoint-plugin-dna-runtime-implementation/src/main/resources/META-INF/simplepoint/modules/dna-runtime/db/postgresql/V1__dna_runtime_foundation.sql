-- PostgreSQL control metadata owned by dna-runtime. Business target tables are not installed here.
CREATE TABLE simpoint_dna_physical_models (
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

CREATE INDEX idx_dna_physical_model_status
  ON simpoint_dna_physical_models (tenant_id, status);
CREATE INDEX idx_dna_physical_model_updated
  ON simpoint_dna_physical_models (updated_at);

CREATE TABLE simpoint_dna_physical_model_versions (
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

CREATE INDEX idx_dna_physical_model_version_checksum
  ON simpoint_dna_physical_model_versions (physical_model_id, schema_checksum);

CREATE TABLE simpoint_dna_physical_bindings (
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

CREATE INDEX idx_dna_physical_binding_tenant
  ON simpoint_dna_physical_bindings (tenant_id, physical_model_id);

CREATE TABLE simpoint_dna_runtime_audit_events (
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

CREATE INDEX idx_dna_runtime_audit_model
  ON simpoint_dna_runtime_audit_events (tenant_id, physical_model_id, occurred_at);
CREATE INDEX idx_dna_runtime_audit_type
  ON simpoint_dna_runtime_audit_events (event_type, occurred_at);

CREATE TABLE simpoint_dna_schema_migrations (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  physical_model_id varchar(36) NOT NULL,
  from_version integer,
  target_version integer NOT NULL,
  target_schema_checksum varchar(64) NOT NULL,
  target_physical_checksum varchar(64),
  actual_schema_checksum varchar(64),
  verified_schema_checksum varchar(64),
  status varchar(24) NOT NULL,
  risk varchar(24),
  dialect_id varchar(64),
  database_version varchar(128),
  error_code varchar(64),
  execution_token varchar(36) NOT NULL,
  started_at timestamp with time zone NOT NULL,
  finished_at timestamp with time zone,
  lock_version bigint NOT NULL DEFAULT 0,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT fk_dna_schema_migration_model FOREIGN KEY (physical_model_id)
    REFERENCES simpoint_dna_physical_models (id),
  CONSTRAINT uk_dna_schema_migration_target
    UNIQUE (tenant_id, physical_model_id, target_version),
  CONSTRAINT ck_dna_schema_migration_status CHECK (
    status IN ('VALIDATING', 'READY', 'MIGRATING', 'VERIFYING', 'ACTIVE', 'FAILED')
  ),
  CONSTRAINT ck_dna_schema_migration_risk CHECK (
    risk IS NULL OR risk IN ('SAFE', 'CONDITIONAL', 'DESTRUCTIVE')
  ),
  CONSTRAINT ck_dna_schema_migration_versions CHECK (
    target_version >= 1 AND (from_version IS NULL OR from_version >= 1)
  )
);

CREATE INDEX idx_dna_schema_migration_model
  ON simpoint_dna_schema_migrations (tenant_id, physical_model_id, status);
CREATE INDEX idx_dna_schema_migration_started
  ON simpoint_dna_schema_migrations (started_at);

CREATE TABLE simpoint_dna_schema_migration_steps (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  migration_id varchar(36) NOT NULL,
  step_sequence integer NOT NULL,
  operation varchar(32) NOT NULL,
  logical_object varchar(128) NOT NULL,
  risk varchar(24) NOT NULL,
  payload_json text NOT NULL,
  statement_sql text NOT NULL,
  statement_checksum varchar(64) NOT NULL,
  status varchar(24) NOT NULL,
  attempts integer NOT NULL DEFAULT 0,
  error_code varchar(64),
  started_at timestamp with time zone,
  finished_at timestamp with time zone,
  lock_version bigint NOT NULL DEFAULT 0,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT fk_dna_schema_migration_step FOREIGN KEY (migration_id)
    REFERENCES simpoint_dna_schema_migrations (id),
  CONSTRAINT uk_dna_schema_migration_step UNIQUE (migration_id, step_sequence),
  CONSTRAINT ck_dna_schema_migration_step_sequence CHECK (step_sequence >= 1),
  CONSTRAINT ck_dna_schema_migration_step_attempts CHECK (attempts >= 0),
  CONSTRAINT ck_dna_schema_migration_step_risk CHECK (
    risk IN ('SAFE', 'CONDITIONAL', 'DESTRUCTIVE')
  ),
  CONSTRAINT ck_dna_schema_migration_step_status CHECK (
    status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED')
  )
);

CREATE INDEX idx_dna_schema_migration_step_status
  ON simpoint_dna_schema_migration_steps (tenant_id, migration_id, status);

CREATE TABLE simpoint_dna_model_resource_bindings (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  physical_model_id varchar(36) NOT NULL,
  resource_code varchar(160) NOT NULL,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT fk_dna_model_resource_model FOREIGN KEY (physical_model_id)
    REFERENCES simpoint_dna_physical_models (id),
  CONSTRAINT uk_dna_model_resource_binding
    UNIQUE (tenant_id, physical_model_id, resource_code)
);

CREATE INDEX idx_dna_model_resource_lookup
  ON simpoint_dna_model_resource_bindings (tenant_id, resource_code, physical_model_id);

ALTER TABLE simpoint_dna_physical_models
  ADD CONSTRAINT uk_dna_physical_model_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE simpoint_dna_record_create_receipts (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  physical_model_id varchar(36) NOT NULL,
  idempotency_key varchar(128) NOT NULL,
  request_fingerprint varchar(64) NOT NULL,
  record_id varchar(36) NOT NULL,
  status varchar(16) NOT NULL,
  result_updated_at timestamp with time zone,
  completed_at timestamp with time zone,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT fk_dna_record_create_receipt_model FOREIGN KEY (tenant_id, physical_model_id)
    REFERENCES simpoint_dna_physical_models (tenant_id, id),
  CONSTRAINT uk_dna_record_create_idempotency
    UNIQUE (tenant_id, physical_model_id, idempotency_key),
  CONSTRAINT ck_dna_record_create_receipt_status CHECK (
    status IN ('PENDING', 'SUCCEEDED')
  ),
  CONSTRAINT ck_dna_record_create_receipt_result CHECK (
    (status = 'PENDING' AND result_updated_at IS NULL AND completed_at IS NULL)
    OR (status = 'SUCCEEDED' AND result_updated_at IS NOT NULL AND completed_at IS NOT NULL)
  )
);

CREATE INDEX idx_dna_record_create_receipt_model
  ON simpoint_dna_record_create_receipts (tenant_id, physical_model_id, created_at);

CREATE INDEX idx_dna_record_create_receipt_status
  ON simpoint_dna_record_create_receipts (status, created_at);

CREATE TABLE simpoint_dna_resource_projections (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL,
  resource_code varchar(160) NOT NULL,
  owner_key varchar(160),
  generation bigint NOT NULL DEFAULT 0,
  physical_model_id varchar(36),
  lock_version bigint NOT NULL DEFAULT 0,
  created_by varchar(64) NOT NULL,
  updated_by varchar(64) NOT NULL,
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  CONSTRAINT uk_dna_resource_projection UNIQUE (tenant_id, resource_code),
  CONSTRAINT fk_dna_resource_projection_model FOREIGN KEY (tenant_id, physical_model_id)
    REFERENCES simpoint_dna_physical_models (tenant_id, id),
  CONSTRAINT chk_dna_resource_projection_owner CHECK (
    (owner_key IS NULL AND generation = 0 AND physical_model_id IS NULL)
    OR (owner_key IS NOT NULL AND generation > 0 AND physical_model_id IS NOT NULL)
  )
);

ALTER TABLE simpoint_dna_resource_projections
  ADD COLUMN formally_offline BOOLEAN NOT NULL DEFAULT FALSE;
