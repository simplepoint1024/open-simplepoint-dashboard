CREATE TABLE simpoint_workflow_personal_archive (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  list_name VARCHAR(16) NOT NULL,
  entry_id VARCHAR(128) NOT NULL,
  process_instance_id VARCHAR(64) NOT NULL
    REFERENCES simpoint_workflow_instances(process_instance_id) ON DELETE CASCADE,
  archived_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uk_workflow_personal_archive_entry UNIQUE (tenant_id, user_id, list_name, entry_id)
);

CREATE INDEX idx_workflow_personal_archive_process
  ON simpoint_workflow_personal_archive (process_instance_id);
