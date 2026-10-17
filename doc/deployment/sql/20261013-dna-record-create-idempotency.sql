-- Durable single-record create receipts for DNA Runtime PostgreSQL control-plane installs.
-- Receipts are control metadata. Form-owned record values remain in their target datasource.

DO $migration$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'uk_dna_physical_model_tenant_id'
  ) THEN
    ALTER TABLE simpoint_dna_physical_models
      ADD CONSTRAINT uk_dna_physical_model_tenant_id UNIQUE (tenant_id, id);
  END IF;
END
$migration$;

CREATE TABLE IF NOT EXISTS simpoint_dna_record_create_receipts (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(255) NOT NULL,
  physical_model_id varchar(36) NOT NULL,
  idempotency_key varchar(128) NOT NULL,
  request_fingerprint varchar(64) NOT NULL,
  record_id varchar(36) NOT NULL,
  status varchar(16) NOT NULL,
  result_updated_at timestamp with time zone,
  completed_at timestamp with time zone,
  create_org_dept_id varchar(255),
  created_by varchar(64),
  updated_by varchar(64),
  created_at timestamp with time zone NOT NULL,
  updated_at timestamp with time zone NOT NULL,
  deleted_at timestamp with time zone,
  CONSTRAINT fk_dna_record_create_receipt_model FOREIGN KEY (tenant_id, physical_model_id)
    REFERENCES simpoint_dna_physical_models (tenant_id, id),
  CONSTRAINT uk_dna_record_create_idempotency
    UNIQUE (tenant_id, physical_model_id, idempotency_key),
  CONSTRAINT ck_dna_record_create_receipt_status CHECK (
    status IN ('PENDING', 'SUCCEEDED')
  ),
  CONSTRAINT ck_dna_record_create_receipt_result CHECK (
    (status = 'PENDING' AND result_updated_at IS NULL AND completed_at IS NULL)
    OR (status = 'SUCCEEDED' AND result_updated_at IS NOT NULL AND completed_at IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS idx_dna_record_create_receipt_model
  ON simpoint_dna_record_create_receipts (tenant_id, physical_model_id, created_at);

CREATE INDEX IF NOT EXISTS idx_dna_record_create_receipt_status
  ON simpoint_dna_record_create_receipts (status, created_at);

-- Destructive rollback, only after callers can no longer retry retained idempotency keys:
-- DROP TABLE simpoint_dna_record_create_receipts;
