-- Explicit legacy preparation for the inspected 2026-10-21 PostgreSQL deployment.
-- Run only through the reviewed adoption procedure, after fencing legacy writers,
-- verifying the pre-change structural fingerprint and taking a restorable backup.
-- This is not a Flyway baseline and does not replace module migrations V2 and later.
-- No business rows are inserted, rewritten or removed. A failed statement rolls back
-- the complete preparation; a successful run must be followed by exact V1 comparison.
BEGIN;
SET LOCAL lock_timeout = '15s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE simpoint_app_definitions
  ADD CONSTRAINT uk_simpoint_app_definition_tenant_id
  UNIQUE USING INDEX uk_simpoint_app_definition_tenant_id;

-- The permitted values are unchanged; canonical order matches the immutable V1.
ALTER TABLE simpoint_ac_data_scope DROP CONSTRAINT simpoint_data_scope_type_check;
ALTER TABLE simpoint_ac_data_scope ADD CONSTRAINT simpoint_data_scope_type_check
  CHECK (type IN ('SELF', 'EXPRESSION', 'CUSTOM', 'DEPT', 'DEPT_AND_BELOW', 'ALL'));

ALTER TABLE simpoint_ac_roles
  ALTER COLUMN authority SET NOT NULL,
  ALTER COLUMN role_name SET NOT NULL;
ALTER TABLE simpoint_ac_users
  ALTER COLUMN authorization_version SET DEFAULT 0,
  ALTER COLUMN two_factor_secret TYPE varchar(512),
  ADD COLUMN two_factor_pending_until timestamp(6) with time zone;
ALTER TABLE simpoint_platform_identity
  ADD CONSTRAINT fk_platform_identity_user FOREIGN KEY (user_id) REFERENCES simpoint_ac_users(id);
CREATE INDEX idx_platform_security_audit_occurred
  ON simpoint_platform_security_audit (occurred_at DESC, id);
CREATE INDEX idx_platform_security_audit_target
  ON simpoint_platform_security_audit (target_id, occurred_at DESC);
ALTER TABLE simpoint_platform_step_up
  ALTER COLUMN failures SET DEFAULT 0,
  ADD CONSTRAINT fk_platform_step_up_user FOREIGN KEY (user_id) REFERENCES simpoint_ac_users(id);
ALTER TABLE simpoint_saas_tenant_resources
  ADD CONSTRAINT tenant_resource_type_check
  CHECK (type IN ('GROUP', 'MODULE', 'PAGE', 'FEATURE', 'ACTION', 'API'));
ALTER TABLE simpoint_saas_tenant_user_rel
  ALTER COLUMN revision SET DEFAULT 0,
  ALTER COLUMN revision SET NOT NULL,
  ALTER COLUMN enabled SET DEFAULT true,
  ALTER COLUMN enabled SET NOT NULL;
ALTER TABLE simpoint_saas_tenants
  ALTER COLUMN authorization_version SET DEFAULT 0,
  ADD CONSTRAINT tenant_resource_mode_check CHECK (resource_mode IN ('PLATFORM', 'CUSTOM'));

-- Preserve the same four-part uniqueness and every historical bootstrap record.
ALTER TABLE simpoint_platform_contribution DROP CONSTRAINT simpoint_platform_contribution_pkey;
ALTER TABLE simpoint_platform_contribution ADD CONSTRAINT simpoint_platform_contribution_pkey
  PRIMARY KEY (contribution_type, module_code, service_name, contribution_key);
COMMIT;
