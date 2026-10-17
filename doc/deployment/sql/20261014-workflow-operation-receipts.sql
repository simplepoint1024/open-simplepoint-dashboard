-- Durable confirmations share the workflow engine transaction; no business field values are stored here.
CREATE TABLE IF NOT EXISTS simpoint_workflow_operation_receipts (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  result_json TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_workflow_receipt_tenant_created
  ON simpoint_workflow_operation_receipts (tenant_id, created_at);
