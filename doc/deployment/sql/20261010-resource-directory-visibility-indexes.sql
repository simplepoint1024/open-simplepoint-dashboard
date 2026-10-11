-- PostgreSQL resource-directory entitlement and ancestor lookups.
-- Existing installations: run each statement outside a transaction before releasing the new query path.
-- New installations declare the same indexes in entity mappings. No business records are changed.
-- This script is not executed by the agent; IF NOT EXISTS permits deliberate repeatable rollout.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_tenant_package_license
    ON simpoint_saas_tenant_package_rel (tenant_id, package_code, deleted_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_application_package_license
    ON simpoint_saas_package_application_rel (application_code, package_code, deleted_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_resource_application_license
    ON simpoint_saas_application_resource_rel (resource_code, application_code, deleted_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_resource_ancestor_children
    ON simpoint_ac_resource_ancestors_rel (ancestor_id, child_id);
