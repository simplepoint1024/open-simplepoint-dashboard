-- Adds service-owned dynamic tenant resources for published APP pages.
-- Apply before deploying the matching Common service.
ALTER TABLE simpoint_saas_tenant_resources
  ADD COLUMN IF NOT EXISTS managed_owner varchar(64);
ALTER TABLE simpoint_saas_tenant_resources
  ADD COLUMN IF NOT EXISTS managed_key varchar(160);
ALTER TABLE simpoint_saas_tenant_resources
  ADD COLUMN IF NOT EXISTS managed_host_resource_id varchar(36);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_resource_managed_key
  ON simpoint_saas_tenant_resources (tenant_id, managed_owner, managed_key)
  WHERE managed_owner IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_tenant_resource_managed_parent
  ON simpoint_saas_tenant_resources (tenant_id, managed_owner, parent_id)
  WHERE managed_owner IS NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chk_tenant_resource_managed_identity'
  ) THEN
    ALTER TABLE simpoint_saas_tenant_resources
      ADD CONSTRAINT chk_tenant_resource_managed_identity CHECK (
        (managed_owner IS NULL AND managed_key IS NULL)
        OR (managed_owner IS NOT NULL AND managed_key IS NOT NULL
          AND platform_resource_id IS NULL AND target_resource_id IS NULL)
      );
  END IF;
END $$;
