CREATE TABLE simpoint_app_removed_submissions (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  process_instance_id VARCHAR(64) NOT NULL,
  operation_id VARCHAR(36) NOT NULL
);
