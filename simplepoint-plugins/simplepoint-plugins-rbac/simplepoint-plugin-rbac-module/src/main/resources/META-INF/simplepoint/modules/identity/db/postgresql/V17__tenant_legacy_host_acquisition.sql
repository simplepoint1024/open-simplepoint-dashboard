-- Classification only. Explicit tenant adoption discovers existing generated-page dependencies;
-- this migration does not import resources, issue credit, or change existing grants.
ALTER TABLE simpoint_saas_tenant_package_instances DROP CONSTRAINT ck_tenant_package_acquisition;
ALTER TABLE simpoint_saas_tenant_package_instances ADD CONSTRAINT ck_tenant_package_acquisition CHECK (
    (acquisition = 'ASSIGNED' AND adopted_at IS NULL) OR
    (acquisition IN ('LEGACY_PACKAGE', 'LEGACY_PUBLIC', 'LEGACY_ROLE', 'LEGACY_HOST') AND adopted_at IS NOT NULL
     AND duration_days IS NULL AND state IN ('ACTIVE', 'REVOKED')
     AND assigned_at = adopted_at AND activated_at IS NOT NULL AND activated_at = adopted_at));
