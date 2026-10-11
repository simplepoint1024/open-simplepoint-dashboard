-- DNA form Runtime phase 2 publication state for PostgreSQL control-plane installations.
-- Physical form tables are created in managed target datasources, not in this control-plane schema.

CREATE TABLE IF NOT EXISTS simpoint_dna_schema_migrations (
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

-- Backfill the fencing token when upgrading an installation created by an earlier draft.
ALTER TABLE simpoint_dna_schema_migrations
  ADD COLUMN IF NOT EXISTS execution_token varchar(36);
UPDATE simpoint_dna_schema_migrations
  SET execution_token = id
  WHERE execution_token IS NULL;
ALTER TABLE simpoint_dna_schema_migrations
  ALTER COLUMN execution_token SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dna_schema_migration_model
  ON simpoint_dna_schema_migrations (tenant_id, physical_model_id, status);
CREATE INDEX IF NOT EXISTS idx_dna_schema_migration_started
  ON simpoint_dna_schema_migrations (started_at);

CREATE TABLE IF NOT EXISTS simpoint_dna_schema_migration_steps (
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

CREATE INDEX IF NOT EXISTS idx_dna_schema_migration_step_status
  ON simpoint_dna_schema_migration_steps (tenant_id, migration_id, status);

-- Destructive rollback, only when no publication records must be retained:
-- DROP TABLE simpoint_dna_schema_migration_steps;
-- DROP TABLE simpoint_dna_schema_migrations;
