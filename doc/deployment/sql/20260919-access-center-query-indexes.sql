-- PostgreSQL role catalog / correlated overview counts.
-- Run each statement outside a transaction on existing installations before the new release.
-- New installations also declare these indexes in the entity mappings.
-- No business records are changed; IF NOT EXISTS makes the script repeatable.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_role_tenant_catalog
    ON simpoint_ac_roles (tenant_id, deleted_at, role_name, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_role_grant_overview
    ON simpoint_ac_role_resource_grants (tenant_id, role_id, deleted_at, resource_code);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_role_overview
    ON simpoint_ac_user_role_rel (tenant_id, role_id, deleted_at, user_id);
