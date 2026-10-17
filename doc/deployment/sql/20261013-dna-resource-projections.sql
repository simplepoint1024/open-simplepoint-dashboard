-- DNA control database, PostgreSQL. Run after the DNA Record runtime and create-receipt migrations.
-- Projection rows are persistent serialization/fencing records; never delete them during rollback.
BEGIN;
CREATE TABLE IF NOT EXISTS simpoint_dna_resource_projections (
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
COMMIT;
