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
