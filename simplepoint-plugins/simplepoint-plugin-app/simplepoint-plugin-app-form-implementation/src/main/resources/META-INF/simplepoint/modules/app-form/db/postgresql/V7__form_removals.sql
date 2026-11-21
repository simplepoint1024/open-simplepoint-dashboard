CREATE TABLE simpoint_app_form_removals (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  application_id VARCHAR(36) NOT NULL,
  form_id VARCHAR(36) NOT NULL UNIQUE,
  form_version INTEGER NOT NULL,
  draft_revision BIGINT,
  requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE
);
