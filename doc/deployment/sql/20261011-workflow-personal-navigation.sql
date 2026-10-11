-- Run AFTER the new workflow resource manifest has registered, in one maintenance transaction.
-- Retain stable resource IDs/codes, all role grants and local navigation customizations.
DO $$ BEGIN
  IF (SELECT count(*) FROM simpoint_ac_resources WHERE deleted_at IS NULL AND disabled = false
      AND ((code IN ('workflow.tasks.view','workflow.instances.started.view') AND route_kind = 'hidden')
        OR (code = 'workflow.instances.view' AND type = 'PAGE' AND component = 'workflow/Management'))) <> 3 THEN
    RAISE EXCEPTION 'Register the consolidated workflow resource manifest before migrating tenant navigation';
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS simpoint_workflow_navigation_migrations (
  tenant_id VARCHAR(36) NOT NULL,
  resource_id VARCHAR(36) NOT NULL,
  previous_row JSONB NOT NULL,
  migrated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (tenant_id, resource_id)
);
CREATE TEMPORARY TABLE workflow_navigation_candidates ON COMMIT DROP AS
SELECT r.tenant_id, r.id, to_jsonb(r) AS previous_row,
  p.type AS new_type, p.component AS new_component,
  CASE WHEN r.platform_resource_id IS NULL AND p.route_kind <> 'hidden' THEN r.route_kind ELSE p.route_kind END AS new_route_kind,
  CASE WHEN r.platform_resource_id IS NOT NULL AND NOT r.local_modified THEN p.name ELSE r.name END AS new_name,
  CASE WHEN r.platform_resource_id IS NOT NULL AND (NOT r.local_modified OR r.path IS NULL) THEN p.path ELSE r.path END AS new_path,
  CASE WHEN r.platform_resource_id IS NOT NULL AND (NOT r.local_modified
    OR (r.type <> p.type AND r.parent_id IN (SELECT id FROM simpoint_ac_resources WHERE code='workflow.instances.started.view')))
    THEN p.parent_id ELSE r.parent_id END AS new_parent_id
FROM simpoint_saas_tenant_resources r JOIN simpoint_ac_resources p
  ON p.id = COALESCE(r.platform_resource_id, r.target_resource_id)
WHERE p.deleted_at IS NULL AND p.disabled = false
  AND p.code IN ('workflow.tasks.view','workflow.instances.started.view','workflow.instances.view')
  AND (r.type IS DISTINCT FROM p.type OR r.component IS DISTINCT FROM p.component
    OR r.route_kind IS DISTINCT FROM CASE WHEN r.platform_resource_id IS NULL AND p.route_kind <> 'hidden' THEN r.route_kind ELSE p.route_kind END
    OR (r.platform_resource_id IS NOT NULL AND NOT r.local_modified
      AND (r.name IS DISTINCT FROM p.name OR r.path IS DISTINCT FROM p.path OR r.parent_id IS DISTINCT FROM p.parent_id)))
FOR UPDATE OF r;

INSERT INTO simpoint_workflow_navigation_migrations (tenant_id, resource_id, previous_row, migrated_at)
SELECT tenant_id, id, previous_row, CURRENT_TIMESTAMP FROM workflow_navigation_candidates
ON CONFLICT (tenant_id,resource_id) DO NOTHING;

UPDATE simpoint_saas_tenant_resources r SET type = c.new_type, component = c.new_component,
  route_kind = c.new_route_kind, name = c.new_name, path = c.new_path, parent_id = c.new_parent_id,
  updated_at = CURRENT_TIMESTAMP
FROM workflow_navigation_candidates c WHERE r.tenant_id = c.tenant_id AND r.id = c.id;

UPDATE simpoint_saas_tenants SET authorization_version = COALESCE(authorization_version,0) + 1
WHERE id IN (SELECT DISTINCT tenant_id FROM workflow_navigation_candidates);
