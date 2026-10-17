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
