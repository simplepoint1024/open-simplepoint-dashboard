-- COMMON control database, PostgreSQL. Retain these fences on application rollback.
ALTER TABLE simpoint_saas_tenant_resources
  ADD COLUMN IF NOT EXISTS lifecycle_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE simpoint_saas_tenant_resources
  ADD COLUMN IF NOT EXISTS formally_offline BOOLEAN NOT NULL DEFAULT FALSE;
