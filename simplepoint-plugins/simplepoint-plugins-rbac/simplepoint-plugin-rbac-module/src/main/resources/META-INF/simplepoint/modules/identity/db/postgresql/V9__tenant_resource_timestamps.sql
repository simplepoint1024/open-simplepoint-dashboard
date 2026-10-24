-- Historical rows only retained updated_at: preserve it as the earliest known catalog time.
-- New rows record their actual insertion time. No resource version or rollback metadata is added.
ALTER TABLE simpoint_saas_tenant_resources ADD COLUMN created_at timestamp(6) with time zone;
UPDATE simpoint_saas_tenant_resources SET created_at = updated_at;
ALTER TABLE simpoint_saas_tenant_resources ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE simpoint_saas_tenant_resources ADD COLUMN last_synced_at timestamp(6) with time zone;
