-- Classify membership per tenant, preserving existing rows as internal members.
ALTER TABLE simpoint_saas_tenant_user_rel
  ADD COLUMN IF NOT EXISTS user_type varchar(32);

UPDATE simpoint_saas_tenant_user_rel SET user_type = 'INTERNAL' WHERE user_type IS NULL;
ALTER TABLE simpoint_saas_tenant_user_rel ALTER COLUMN user_type SET DEFAULT 'INTERNAL';
ALTER TABLE simpoint_saas_tenant_user_rel ALTER COLUMN user_type SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_tenant_member_org_page
  ON simpoint_saas_tenant_user_rel(tenant_id, org_id, user_id)
  WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_organization_tenant_parent
  ON simpoint_saas_organizations(tenant_id, parent_id, id)
  WHERE deleted_at IS NULL;

-- Retire translations owned only by the removed standalone grant center.
DELETE FROM simpoint_i18n_messages WHERE namespace = 'access-center';
DELETE FROM simpoint_i18n_messages WHERE namespace = 'menu' AND code = 'menu.system.accessCenter';
DELETE FROM simpoint_i18n_namespaces WHERE code = 'access-center';
