CREATE TABLE simpoint_dna_form_storage_removals (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  form_id VARCHAR(36) NOT NULL,
  form_version INTEGER NOT NULL,
  plan_json TEXT,
  execution_token VARCHAR(36),
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  lease_until TIMESTAMP WITH TIME ZONE,
  completed_at TIMESTAMP WITH TIME ZONE,
  attempts INTEGER NOT NULL DEFAULT 0,
  UNIQUE (tenant_id, form_id)
);
CREATE INDEX idx_dna_form_storage_removal_ready
  ON simpoint_dna_form_storage_removals (next_attempt_at, id) WHERE completed_at IS NULL;
