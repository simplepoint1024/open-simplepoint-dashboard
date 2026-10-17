-- Run AFTER all authorization readers use the revoked-grant query fix.
-- Invalidate cached contexts built by the former query which relied on a request filter.
-- Re-running only advances the affected tenant versions; no grant or history is changed.
UPDATE simpoint_saas_tenants t
SET authorization_version = COALESCE(t.authorization_version, 0) + 1
WHERE EXISTS (
  SELECT 1 FROM simpoint_ac_role_resource_grants g
  WHERE g.tenant_id = t.id AND g.deleted_at IS NOT NULL
);
