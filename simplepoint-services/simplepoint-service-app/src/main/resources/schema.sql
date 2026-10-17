CREATE TABLE IF NOT EXISTS simpoint_app_definitions (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  icon VARCHAR(60),
  status VARCHAR(20) NOT NULL,
  main_page_id VARCHAR(36) NOT NULL,
  created_by VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_simpoint_app_definition_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS idx_simpoint_app_tenant_updated
  ON simpoint_app_definitions (tenant_id, updated_at);

ALTER TABLE simpoint_app_definitions
  ADD COLUMN IF NOT EXISTS navigation_group BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS simpoint_app_pages (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  application_id VARCHAR(36) NOT NULL REFERENCES simpoint_app_definitions(id),
  kind VARCHAR(12) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  sort_order INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  resource_id VARCHAR(64),
  resource_code VARCHAR(160),
  form_id VARCHAR(36),
  form_version INTEGER,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_simpoint_app_page_kind CHECK (kind IN ('MAIN', 'ATTACHED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_app_page_tenant_app_id
  ON simpoint_app_pages (tenant_id, application_id, id);
CREATE INDEX IF NOT EXISTS idx_simpoint_app_page_tenant_app
  ON simpoint_app_pages (tenant_id, application_id, sort_order);

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
  write_policy_version BIGINT NOT NULL DEFAULT 0,
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
  CONSTRAINT chk_app_form_write_policy CHECK (write_policy_version >= 0),
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
  generation BIGINT NOT NULL CHECK (generation > 0),
  policy_protocol_version integer NOT NULL DEFAULT 0 CHECK (policy_protocol_version IN (0, 1)),
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
    'DATA_PREPARING', 'RESOURCE_PREPARING', 'RESOURCE_READY', 'ACTIVATING',
    'ACTIVE', 'RETIRED', 'CANCELLED', 'FAILED'
  ))
);
CREATE INDEX IF NOT EXISTS idx_simpoint_app_form_page_binding_page
  ON simpoint_app_form_page_bindings (tenant_id, page_id, status, updated_at);
CREATE INDEX IF NOT EXISTS idx_simpoint_app_form_page_binding_form
  ON simpoint_app_form_page_bindings (tenant_id, form_id, form_version);
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_app_form_page_binding_active
  ON simpoint_app_form_page_bindings (tenant_id, page_id)
  WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS uk_app_page_binding_generation
  ON simpoint_app_form_page_bindings (tenant_id, page_id, generation);

CREATE UNIQUE INDEX IF NOT EXISTS uk_app_page_binding_live_target
  ON simpoint_app_form_page_bindings (tenant_id, page_id, form_id, form_version)
  WHERE status NOT IN ('RETIRED', 'CANCELLED');
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_page_binding_tenant_id
  ON simpoint_app_form_page_bindings (tenant_id, id);
CREATE TABLE IF NOT EXISTS simpoint_app_form_release_keys (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL,
  request_key varchar(80) NOT NULL,
  binding_id varchar(36) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT uk_app_release_request_key UNIQUE (tenant_id, request_key),
  CONSTRAINT fk_app_release_request_binding FOREIGN KEY (tenant_id, binding_id)
    REFERENCES simpoint_app_form_page_bindings (tenant_id, id)
);

-- Durable form-wide policy publication coordination.
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_page_binding_policy_owner
  ON simpoint_app_form_page_bindings (tenant_id, application_id, form_id, page_id, id);
CREATE TABLE IF NOT EXISTS simpoint_app_form_policy_publications (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL,
  application_id varchar(36) NOT NULL,
  form_id varchar(36) NOT NULL,
  page_id varchar(36) NOT NULL,
  binding_id varchar(36) NOT NULL,
  governing_version integer NOT NULL,
  expected_version bigint NOT NULL,
  target_version bigint NOT NULL,
  command_json text NOT NULL,
  status varchar(24) NOT NULL,
  target_fingerprint varchar(64),
  inspection_json text,
  cancellation_json text,
  created_by varchar(64),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  lock_version bigint NOT NULL DEFAULT 0,
  CONSTRAINT uk_app_policy_binding UNIQUE (tenant_id, binding_id),
  CONSTRAINT uk_app_policy_version UNIQUE (tenant_id, form_id, target_version),
  CONSTRAINT fk_app_policy_binding FOREIGN KEY (tenant_id, application_id, form_id, page_id, binding_id)
    REFERENCES simpoint_app_form_page_bindings (tenant_id, application_id, form_id, page_id, id),
  CONSTRAINT fk_app_policy_governing_version FOREIGN KEY (tenant_id, form_id, governing_version)
    REFERENCES simpoint_app_form_versions (tenant_id, form_id, version_number),
  CONSTRAINT chk_app_policy_versions CHECK (expected_version >= 0 AND target_version > expected_version),
  CONSTRAINT chk_app_policy_status CHECK (status IN ('PREPARED', 'CANCELLING', 'CANCELLED', 'TARGET_READY', 'ACTIVE')),
  CONSTRAINT chk_app_policy_acknowledgement CHECK (
    (status IN ('PREPARED', 'CANCELLING') AND target_fingerprint IS NULL AND cancellation_json IS NULL) OR
    (status IN ('TARGET_READY', 'ACTIVE', 'CANCELLED') AND target_fingerprint IS NOT NULL
      AND target_fingerprint ~ '^[a-f0-9]{64}$' AND inspection_json IS NULL
      AND ((status = 'CANCELLED' AND cancellation_json IS NOT NULL) OR
        (status IN ('TARGET_READY', 'ACTIVE') AND cancellation_json IS NULL))))
);
CREATE INDEX IF NOT EXISTS idx_app_policy_pending
  ON simpoint_app_form_policy_publications (tenant_id, form_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_policy_form_open
  ON simpoint_app_form_policy_publications (tenant_id, form_id) WHERE status NOT IN ('ACTIVE', 'CANCELLED');
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_policy_page_open
  ON simpoint_app_form_policy_publications (tenant_id, page_id) WHERE status NOT IN ('ACTIVE', 'CANCELLED');

-- Durable APP business submissions; no existing business tables are rewritten.
CREATE TABLE IF NOT EXISTS simpoint_app_form_submissions (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  application_id VARCHAR(36) NOT NULL,
  page_id VARCHAR(36) NOT NULL,
  form_id VARCHAR(36) NOT NULL,
  form_version INTEGER NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  intent_json TEXT NOT NULL,
  title VARCHAR(240) NOT NULL,
  definition_id VARCHAR(64) NOT NULL,
  definition_version INTEGER NOT NULL,
  previous_submission_id VARCHAR(64),
  record_id VARCHAR(64),
  record_version BIGINT,
  snapshot_json TEXT,
  process_instance_id VARCHAR(64),
  process_status VARCHAR(24),
  status VARCHAR(24) NOT NULL,
  step VARCHAR(24) NOT NULL,
  error_code VARCHAR(80),
  attempt INTEGER NOT NULL DEFAULT 0,
  execution_token VARCHAR(36),
  lease_until TIMESTAMP WITH TIME ZONE,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  lock_version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_app_submission_recovery ON simpoint_app_form_submissions(step, next_attempt_at, lease_until);
CREATE INDEX IF NOT EXISTS idx_app_submission_page ON simpoint_app_form_submissions(tenant_id, application_id, page_id, created_at);
CREATE INDEX IF NOT EXISTS idx_app_submission_process ON simpoint_app_form_submissions(tenant_id, process_instance_id);
CREATE INDEX IF NOT EXISTS idx_app_submission_record ON simpoint_app_form_submissions(tenant_id, form_id, record_id, created_at);

-- APP control database, PostgreSQL. Apply before enabling MQ lifecycle actions.
CREATE TABLE IF NOT EXISTS simpoint_app_resource_offline (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  application_id VARCHAR(36) NOT NULL,
  page_id VARCHAR(36) NOT NULL,
  form_id VARCHAR(36) NOT NULL,
  binding_id VARCHAR(36) NOT NULL,
  resource_id VARCHAR(64) NOT NULL,
  generation BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  request_event_id VARCHAR(36) NOT NULL,
  required_participants VARCHAR(160) NOT NULL,
  completed_participants VARCHAR(160) NOT NULL,
  retry_request_id VARCHAR(36),
  retry_actor VARCHAR(64),
  retry_at TIMESTAMP WITH TIME ZONE,
  retries INTEGER NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_app_resource_offline_generation UNIQUE (tenant_id, page_id, generation),
  CONSTRAINT chk_app_resource_offline_state CHECK (state IN ('OFFLINING', 'OFFLINE'))
);
CREATE INDEX IF NOT EXISTS ix_app_resource_offline_page
  ON simpoint_app_resource_offline (tenant_id, page_id, state);
