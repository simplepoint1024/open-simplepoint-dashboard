CREATE TABLE simpoint_workflow_history_removal_batches (batch_key VARCHAR(64) PRIMARY KEY);
CREATE TABLE simpoint_workflow_history_removals (
  instance_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  operation_id VARCHAR(36) NOT NULL UNIQUE,
  batch_id VARCHAR(36) NOT NULL,
  definition_version INTEGER NOT NULL,
  ended_at TIMESTAMP WITH TIME ZONE NOT NULL,
  requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
  app_completed BOOLEAN NOT NULL DEFAULT FALSE,
  personal_completed BOOLEAN NOT NULL DEFAULT FALSE,
  completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_workflow_history_removals_batch
  ON simpoint_workflow_history_removals (tenant_id, batch_id);

ALTER TABLE simpoint_workflow_operation_receipts ADD COLUMN process_instance_id VARCHAR(64);
ALTER TABLE simpoint_workflow_operation_receipts ADD COLUMN purged BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE simpoint_workflow_operation_receipts
SET process_instance_id = CAST(result_json AS jsonb) ->> 'id';
CREATE INDEX idx_workflow_receipt_process
  ON simpoint_workflow_operation_receipts (tenant_id, process_instance_id);
