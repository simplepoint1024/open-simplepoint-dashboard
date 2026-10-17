-- One immutable business submission can create one workflow instance even with another client operation key.
CREATE TABLE IF NOT EXISTS simpoint_workflow_business_links (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  provider VARCHAR(80) NOT NULL,
  business_key VARCHAR(160) NOT NULL,
  process_instance_id VARCHAR(64) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_workflow_business_process ON simpoint_workflow_business_links(tenant_id, process_instance_id);

CREATE TABLE IF NOT EXISTS simpoint_workflow_form_bindings (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  definition_id VARCHAR(36) NOT NULL,
  definition_version INTEGER NOT NULL,
  provider VARCHAR(80) NOT NULL,
  form_id VARCHAR(160) NOT NULL,
  form_version INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_workflow_bound_form ON simpoint_workflow_form_bindings(tenant_id, provider, form_id, form_version);
