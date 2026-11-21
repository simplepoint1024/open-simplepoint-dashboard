-- Durable control-plane relation dependencies; business FKs remain in the target store.
-- No automatic adoption of relations installed before governed publication was enabled.
DO 'BEGIN
  IF EXISTS (
    SELECT 1 FROM simpoint_dna_physical_model_versions v
    JOIN simpoint_dna_physical_models m ON m.tenant_id=v.tenant_id AND m.id=v.physical_model_id
    WHERE jsonb_array_length(COALESCE(NULLIF(v.schema_json::jsonb->''references'', ''null''::jsonb), ''[]''::jsonb)) > 0
      AND (v.version_number=m.active_version OR EXISTS (
        SELECT 1 FROM simpoint_dna_schema_migrations s
        WHERE s.tenant_id=v.tenant_id AND s.physical_model_id=v.physical_model_id
          AND s.target_version=v.version_number AND s.plan_json IS NOT NULL))
  ) THEN
    RAISE EXCEPTION ''Existing published references require verified catalog adoption'';
  END IF;
END';

CREATE TABLE simpoint_dna_relation_catalogs (
  tenant_id varchar(255) PRIMARY KEY,
  revision bigint NOT NULL CHECK (revision >= 0),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL
);
INSERT INTO simpoint_dna_relation_catalogs (tenant_id, revision, created_at, updated_at)
  SELECT DISTINCT tenant_id, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM simpoint_dna_physical_models;

ALTER TABLE simpoint_dna_schema_migrations
  ADD CONSTRAINT uk_dna_schema_migration_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE simpoint_dna_relation_definitions (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  source_model_id varchar(36) NOT NULL,
  source_root_model_id varchar(36) NOT NULL,
  source_version integer NOT NULL CHECK (source_version >= 1),
  relation_id varchar(64) NOT NULL,
  field_id varchar(64) NOT NULL,
  column_name varchar(63) NOT NULL,
  target_model_id varchar(36) NOT NULL,
  unique_target boolean NOT NULL,
  data_source_id varchar(64) NOT NULL,
  catalog_name varchar(128) NOT NULL,
  schema_name varchar(128) NOT NULL,
  source_table varchar(128) NOT NULL,
  target_table varchar(128) NOT NULL,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT ck_dna_relation_definition_retained CHECK (deleted_at IS NULL),
  CONSTRAINT uk_dna_relation_definition UNIQUE (tenant_id, source_model_id, source_version, relation_id),
  CONSTRAINT uk_dna_relation_field UNIQUE (tenant_id, source_model_id, source_version, field_id),
  CONSTRAINT fk_dna_relation_source_version FOREIGN KEY (tenant_id, source_model_id, source_version)
    REFERENCES simpoint_dna_physical_model_versions (tenant_id, physical_model_id, version_number),
  CONSTRAINT fk_dna_relation_source_root FOREIGN KEY (tenant_id, source_root_model_id)
    REFERENCES simpoint_dna_physical_models (tenant_id, id),
  CONSTRAINT fk_dna_relation_target FOREIGN KEY (tenant_id, target_model_id)
    REFERENCES simpoint_dna_physical_models (tenant_id, id),
  CONSTRAINT fk_dna_relation_catalog FOREIGN KEY (tenant_id)
    REFERENCES simpoint_dna_relation_catalogs (tenant_id)
);
CREATE INDEX idx_dna_relation_incoming ON simpoint_dna_relation_definitions (tenant_id, target_model_id);
CREATE INDEX idx_dna_relation_root ON simpoint_dna_relation_definitions (tenant_id, source_root_model_id);

CREATE TABLE simpoint_dna_relation_guards (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  migration_id varchar(36) NOT NULL,
  model_id varchar(36) NOT NULL,
  released boolean NOT NULL DEFAULT false,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT ck_dna_relation_guard_retained CHECK (deleted_at IS NULL),
  CONSTRAINT uk_dna_relation_guard UNIQUE (tenant_id, migration_id, model_id),
  CONSTRAINT fk_dna_relation_guard_migration FOREIGN KEY (tenant_id, migration_id)
    REFERENCES simpoint_dna_schema_migrations (tenant_id, id),
  CONSTRAINT fk_dna_relation_guard_model FOREIGN KEY (tenant_id, model_id)
    REFERENCES simpoint_dna_physical_models (tenant_id, id),
  CONSTRAINT fk_dna_relation_guard_catalog FOREIGN KEY (tenant_id)
    REFERENCES simpoint_dna_relation_catalogs (tenant_id)
);
CREATE UNIQUE INDEX uk_dna_relation_guard_pending ON simpoint_dna_relation_guards (tenant_id, model_id)
  WHERE NOT released;
