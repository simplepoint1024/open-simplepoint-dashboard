CREATE TABLE IF NOT EXISTS simpoint_workflow_definitions (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  definition_key VARCHAR(80) NOT NULL,
  name VARCHAR(160) NOT NULL,
  category VARCHAR(80),
  description VARCHAR(1000),
  status VARCHAR(20) NOT NULL,
  latest_version INTEGER NOT NULL DEFAULT 0,
  draft_revision BIGINT NOT NULL DEFAULT 1,
  draft_xml TEXT NOT NULL,
  draft_form_contract TEXT,
  published_checksum VARCHAR(64),
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_workflow_definition_tenant_key UNIQUE (tenant_id, definition_key)
);
CREATE INDEX IF NOT EXISTS idx_workflow_definition_tenant_updated
  ON simpoint_workflow_definitions (tenant_id, updated_at);

CREATE TABLE IF NOT EXISTS simpoint_workflow_definition_versions (
  id VARCHAR(36) PRIMARY KEY,
  definition_id VARCHAR(36) NOT NULL REFERENCES simpoint_workflow_definitions(id),
  tenant_id VARCHAR(64) NOT NULL,
  version_no INTEGER NOT NULL,
  bpmn_xml TEXT NOT NULL,
  form_contract TEXT,
  definition_name VARCHAR(160),
  definition_category VARCHAR(80),
  definition_description VARCHAR(1000),
  checksum_version INTEGER,
  checksum VARCHAR(64) NOT NULL,
  comment VARCHAR(500),
  flowable_deployment_id VARCHAR(64) NOT NULL,
  flowable_process_definition_id VARCHAR(128) NOT NULL,
  published_by VARCHAR(64) NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_workflow_definition_version UNIQUE (definition_id, version_no),
  CONSTRAINT uk_workflow_version_process_definition UNIQUE (flowable_process_definition_id)
);
CREATE INDEX IF NOT EXISTS idx_workflow_version_tenant_definition
  ON simpoint_workflow_definition_versions (tenant_id, definition_id, version_no);

-- Additive, idempotent migration. Legacy snapshots deliberately retain checksum version 1.
ALTER TABLE simpoint_workflow_definitions ADD COLUMN IF NOT EXISTS draft_form_contract TEXT;
ALTER TABLE simpoint_workflow_definition_versions ADD COLUMN IF NOT EXISTS form_contract TEXT;
ALTER TABLE simpoint_workflow_definition_versions ADD COLUMN IF NOT EXISTS definition_name VARCHAR(160);
ALTER TABLE simpoint_workflow_definition_versions ADD COLUMN IF NOT EXISTS definition_category VARCHAR(80);
ALTER TABLE simpoint_workflow_definition_versions ADD COLUMN IF NOT EXISTS definition_description VARCHAR(1000);
ALTER TABLE simpoint_workflow_definition_versions ADD COLUMN IF NOT EXISTS checksum_version INTEGER;

CREATE TABLE IF NOT EXISTS simpoint_workflow_instances (
  process_instance_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  definition_id VARCHAR(36) NOT NULL REFERENCES simpoint_workflow_definitions(id),
  definition_version_id VARCHAR(36) NOT NULL REFERENCES simpoint_workflow_definition_versions(id),
  definition_key VARCHAR(80) NOT NULL,
  definition_name VARCHAR(160) NOT NULL,
  definition_version INTEGER NOT NULL,
  business_key VARCHAR(160),
  title VARCHAR(240) NOT NULL,
  starter_id VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  ended_at TIMESTAMP WITH TIME ZONE,
  cancel_reason VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_workflow_instance_tenant_started
  ON simpoint_workflow_instances (tenant_id, started_at);
CREATE INDEX IF NOT EXISTS idx_workflow_instance_starter_started
  ON simpoint_workflow_instances (tenant_id, starter_id, started_at);
CREATE INDEX IF NOT EXISTS idx_workflow_instance_business_key
  ON simpoint_workflow_instances (tenant_id, business_key);

CREATE TABLE IF NOT EXISTS simpoint_workflow_instance_watchers (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  process_instance_id VARCHAR(64) NOT NULL REFERENCES simpoint_workflow_instances(process_instance_id),
  user_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_workflow_watcher_instance_user UNIQUE (process_instance_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_workflow_watcher_user_created
  ON simpoint_workflow_instance_watchers (tenant_id, user_id, created_at);

CREATE TABLE IF NOT EXISTS simpoint_workflow_notification_outbox (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  event_key VARCHAR(200) NOT NULL,
  target_user_id VARCHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  link_url VARCHAR(1024) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  sent_at TIMESTAMP WITH TIME ZONE,
  last_error VARCHAR(1000),
  CONSTRAINT uk_workflow_outbox_event UNIQUE (event_key)
);
CREATE INDEX IF NOT EXISTS idx_workflow_outbox_delivery
  ON simpoint_workflow_notification_outbox (status, next_attempt_at);
