ALTER TABLE simpoint_saas_tenant_resources ADD COLUMN navigation_root_key VARCHAR(160);
ALTER TABLE simpoint_saas_tenant_resources ADD COLUMN navigation_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE simpoint_saas_tenant_resources ADD COLUMN navigation_digest VARCHAR(64);
CREATE INDEX idx_tenant_managed_navigation ON simpoint_saas_tenant_resources (tenant_id, managed_owner, navigation_root_key);
