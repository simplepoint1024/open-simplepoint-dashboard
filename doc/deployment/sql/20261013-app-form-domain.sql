-- Application form control-plane foundation.
-- Apply after the existing simplepoint-service-app schema.

BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_app_definition_tenant_id
  ON simpoint_app_definitions (tenant_id, id);

ALTER TABLE simpoint_app_pages
  ADD COLUMN IF NOT EXISTS form_id VARCHAR(36);
ALTER TABLE simpoint_app_pages
  ADD COLUMN IF NOT EXISTS form_version INTEGER;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_app_page_tenant_app_id
  ON simpoint_app_pages (tenant_id, application_id, id);

CREATE TABLE IF NOT EXISTS simpoint_app_forms (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  application_id VARCHAR(36) NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  status VARCHAR(20) NOT NULL,
  draft_revision BIGINT NOT NULL DEFAULT 0,
  latest_version INTEGER NOT NULL DEFAULT 0,
  published_version INTEGER,
  physical_model_id VARCHAR(64),
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_simpoint_app_form_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT uk_simpoint_app_form_code UNIQUE (tenant_id, application_id, code),
  CONSTRAINT fk_simpoint_app_form_application
    FOREIGN KEY (tenant_id, application_id)
    REFERENCES simpoint_app_definitions (tenant_id, id),
  CONSTRAINT chk_simpoint_app_form_status
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_simpoint_app_form_app
  ON simpoint_app_forms (tenant_id, application_id, created_at);
CREATE INDEX IF NOT EXISTS idx_simpoint_app_form_status
  ON simpoint_app_forms (tenant_id, status, updated_at);

CREATE TABLE IF NOT EXISTS simpoint_app_form_drafts (
  form_id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  document_json TEXT NOT NULL,
  checksum VARCHAR(64) NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0,
  updated_by VARCHAR(64),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_simpoint_app_form_draft_form
    FOREIGN KEY (tenant_id, form_id)
    REFERENCES simpoint_app_forms (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS simpoint_app_form_versions (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  form_id VARCHAR(36) NOT NULL,
  version_number INTEGER NOT NULL,
  document_json TEXT NOT NULL,
  checksum VARCHAR(64) NOT NULL,
  storage_schema_json TEXT NOT NULL,
  storage_checksum VARCHAR(64) NOT NULL,
  physical_model_version INTEGER,
  renderer_version VARCHAR(16) NOT NULL,
  created_by VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_simpoint_app_form_version
    UNIQUE (tenant_id, form_id, version_number),
  CONSTRAINT fk_simpoint_app_form_version_form
    FOREIGN KEY (tenant_id, form_id)
    REFERENCES simpoint_app_forms (tenant_id, id)
);

CREATE INDEX IF NOT EXISTS idx_simpoint_app_form_version_lookup
  ON simpoint_app_form_versions (tenant_id, form_id, version_number);

CREATE TABLE IF NOT EXISTS simpoint_app_form_publications (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  form_id VARCHAR(36) NOT NULL,
  form_version INTEGER NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  status VARCHAR(24) NOT NULL,
  step VARCHAR(40) NOT NULL,
  migration_id VARCHAR(64),
  error_code VARCHAR(80),
  attempt INTEGER NOT NULL DEFAULT 0,
  execution_token VARCHAR(36),
  lease_until TIMESTAMP WITH TIME ZONE,
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_simpoint_app_form_publication_key
    UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT uk_simpoint_app_form_publication_version
    UNIQUE (tenant_id, form_id, form_version),
  CONSTRAINT fk_simpoint_app_form_publication_form
    FOREIGN KEY (tenant_id, form_id)
    REFERENCES simpoint_app_forms (tenant_id, id),
  CONSTRAINT chk_simpoint_app_form_publication_status CHECK (status IN (
    'VALIDATING', 'VERSION_FROZEN', 'DATA_PREPARING', 'DATA_READY',
    'RESOURCE_BINDING', 'ACTIVATING', 'ACTIVE', 'RETIRED', 'FAILED', 'RETRYING'
  ))
);

ALTER TABLE simpoint_app_form_publications
  ADD COLUMN IF NOT EXISTS execution_token VARCHAR(36);
ALTER TABLE simpoint_app_form_publications
  ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_simpoint_app_form_publication_state
  ON simpoint_app_form_publications (tenant_id, status, updated_at);

CREATE TABLE IF NOT EXISTS simpoint_app_form_page_bindings (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  application_id VARCHAR(36) NOT NULL,
  page_id VARCHAR(36) NOT NULL,
  form_id VARCHAR(36) NOT NULL,
  form_version INTEGER NOT NULL,
  scene_id VARCHAR(80) NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  status VARCHAR(24) NOT NULL,
  resource_managed_key VARCHAR(160) NOT NULL,
  resource_id VARCHAR(64),
  resource_code VARCHAR(160),
  runtime_path VARCHAR(200),
  error_code VARCHAR(80),
  attempt INTEGER NOT NULL DEFAULT 0,
  execution_token VARCHAR(36),
  lease_until TIMESTAMP WITH TIME ZONE,
  activated_at TIMESTAMP WITH TIME ZONE,
  lock_version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_simpoint_app_form_page_binding_key
    UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT uk_simpoint_app_form_page_binding_target
    UNIQUE (tenant_id, page_id, form_id, form_version),
  CONSTRAINT fk_simpoint_app_form_page_binding_page
    FOREIGN KEY (tenant_id, application_id, page_id)
    REFERENCES simpoint_app_pages (tenant_id, application_id, id),
  CONSTRAINT fk_simpoint_app_form_page_binding_form
    FOREIGN KEY (tenant_id, form_id)
    REFERENCES simpoint_app_forms (tenant_id, id),
  CONSTRAINT fk_simpoint_app_form_page_binding_version
    FOREIGN KEY (tenant_id, form_id, form_version)
    REFERENCES simpoint_app_form_versions (tenant_id, form_id, version_number),
  CONSTRAINT chk_simpoint_app_form_page_binding_status CHECK (status IN (
    'RESOURCE_PREPARING', 'RESOURCE_READY', 'ACTIVATING',
    'ACTIVE', 'RETIRED', 'FAILED'
  ))
);

CREATE INDEX IF NOT EXISTS idx_simpoint_app_form_page_binding_page
  ON simpoint_app_form_page_bindings (tenant_id, page_id, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_simpoint_app_form_page_binding_form
  ON simpoint_app_form_page_bindings (tenant_id, form_id, form_version);
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_app_form_page_binding_active
  ON simpoint_app_form_page_bindings (tenant_id, page_id)
  WHERE status = 'ACTIVE';

COMMIT;

-- Rollback is intentionally destructive and therefore manual:
-- DROP TABLE simpoint_app_form_publications;
-- DROP TABLE simpoint_app_form_versions;
-- DROP TABLE simpoint_app_form_drafts;
-- DROP TABLE simpoint_app_forms;
