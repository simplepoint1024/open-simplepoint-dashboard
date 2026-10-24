-- Legacy entitlements are adopted explicitly; this migration never grants or activates anything.
ALTER TABLE simpoint_saas_tenant_package_instances
    ADD COLUMN acquisition varchar(32) NOT NULL DEFAULT 'ASSIGNED',
    ADD COLUMN adopted_at timestamp(6) with time zone,
    ADD CONSTRAINT ck_tenant_package_acquisition CHECK (
        (acquisition = 'ASSIGNED' AND adopted_at IS NULL) OR
        (acquisition IN ('LEGACY_PACKAGE', 'LEGACY_PUBLIC', 'LEGACY_ROLE') AND adopted_at IS NOT NULL
         AND duration_days IS NULL AND state IN ('ACTIVE', 'REVOKED')
         AND assigned_at = adopted_at AND activated_at IS NOT NULL AND activated_at = adopted_at));
ALTER TABLE simpoint_saas_tenant_resource_setup ADD COLUMN adopted_by varchar(36);
