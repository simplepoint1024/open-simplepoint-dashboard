-- PostgreSQL additive migration. Review and back up first; run before deploying new binaries.
-- Does NOT create or promote any administrator. Existing User.super_admin remains authoritative.
BEGIN;

ALTER TABLE simpoint_ac_users
  ADD COLUMN IF NOT EXISTS authorization_version bigint NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS simpoint_platform_security_state (
  id varchar(255) PRIMARY KEY
);
INSERT INTO simpoint_platform_security_state(id) VALUES ('platform') ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS simpoint_platform_identity (
  user_id varchar(255) PRIMARY KEY REFERENCES simpoint_ac_users(id)
);
CREATE TABLE IF NOT EXISTS simpoint_platform_identity_role (
  user_id varchar(255) NOT NULL REFERENCES simpoint_platform_identity(user_id),
  role_code varchar(255) NOT NULL
    CHECK (role_code IN ('PLATFORM_ADMIN', 'ACCOUNT_ADMIN', 'AUDITOR')),
  PRIMARY KEY (user_id, role_code)
);
CREATE TABLE IF NOT EXISTS simpoint_platform_security_audit (
  id varchar(255) PRIMARY KEY,
  actor_id varchar(255),
  target_id varchar(255),
  action varchar(255),
  before_state varchar(2000),
  after_state varchar(2000),
  reason varchar(500),
  occurred_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_platform_audit_time ON simpoint_platform_security_audit(occurred_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_platform_audit_target ON simpoint_platform_security_audit(target_id, occurred_at DESC);
CREATE TABLE IF NOT EXISTS simpoint_platform_step_up (
  user_id varchar(255) PRIMARY KEY REFERENCES simpoint_ac_users(id),
  failures integer NOT NULL DEFAULT 0,
  blocked_until timestamptz,
  last_totp_step bigint
);

ALTER TABLE simpoint_saas_tenant_user_rel
  ADD COLUMN IF NOT EXISTS org_id varchar(255),
  ADD COLUMN IF NOT EXISTS enabled boolean NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS revision bigint NOT NULL DEFAULT 0;

-- Refuse ambiguous existing relationships instead of silently deleting user data.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM simpoint_saas_tenant_user_rel
    GROUP BY tenant_id, user_id HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION 'Duplicate tenant memberships: review tenant_id/user_id groups before migration';
  END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_member_user
  ON simpoint_saas_tenant_user_rel(tenant_id, user_id);

-- Copy the old global organization ONLY into its matching active organization tenant.
-- Idempotent: do not overwrite a membership organization already selected by an operator.
UPDATE simpoint_saas_tenant_user_rel m
SET org_id = u.org_id, revision = m.revision + 1
FROM simpoint_ac_users u, simpoint_saas_organizations o
WHERE m.user_id = u.id AND u.org_id = o.id AND m.tenant_id = o.tenant_id
  AND m.org_id IS NULL AND m.deleted_at IS NULL AND u.deleted_at IS NULL
  AND o.deleted_at IS NULL AND o.enabled = true;

COMMIT;
