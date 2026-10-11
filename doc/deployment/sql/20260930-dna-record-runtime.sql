-- DNA form Runtime phase 3 record authorization projection for PostgreSQL control-plane installs.

CREATE TABLE IF NOT EXISTS simpoint_dna_model_resource_bindings (
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

CREATE INDEX IF NOT EXISTS idx_dna_model_resource_lookup
  ON simpoint_dna_model_resource_bindings (tenant_id, resource_code, physical_model_id);

-- Destructive rollback, only when resource bindings no longer need to be retained:
-- DROP TABLE simpoint_dna_model_resource_bindings;
