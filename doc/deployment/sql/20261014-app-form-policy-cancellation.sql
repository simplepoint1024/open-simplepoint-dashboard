-- Apply after 20261014-app-form-policy-publications.sql, with APP publication writers stopped.
-- Target cancellation consumes admission; old request keys must keep their original terminal release.
BEGIN;
LOCK TABLE simpoint_app_form_page_bindings, simpoint_app_form_policy_publications IN ACCESS EXCLUSIVE MODE;
ALTER TABLE simpoint_app_form_page_bindings DROP CONSTRAINT IF EXISTS uk_simpoint_app_form_page_binding_target;
ALTER TABLE simpoint_app_form_page_bindings DROP CONSTRAINT IF EXISTS chk_simpoint_app_form_page_binding_status;
ALTER TABLE simpoint_app_form_page_bindings ADD CONSTRAINT chk_simpoint_app_form_page_binding_status CHECK (status IN (
  'DATA_PREPARING', 'RESOURCE_PREPARING', 'RESOURCE_READY', 'ACTIVATING', 'ACTIVE', 'RETIRED', 'CANCELLED', 'FAILED'));
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_page_binding_live_target
  ON simpoint_app_form_page_bindings (tenant_id, page_id, form_id, form_version)
  WHERE status NOT IN ('RETIRED', 'CANCELLED');
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_page_binding_tenant_id
  ON simpoint_app_form_page_bindings (tenant_id, id);
CREATE TABLE IF NOT EXISTS simpoint_app_form_release_keys (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL,
  request_key varchar(80) NOT NULL,
  binding_id varchar(36) NOT NULL,
  created_at timestamptz NOT NULL,
  CONSTRAINT uk_app_release_request_key UNIQUE (tenant_id, request_key),
  CONSTRAINT fk_app_release_request_binding FOREIGN KEY (tenant_id, binding_id)
    REFERENCES simpoint_app_form_page_bindings (tenant_id, id)
);
INSERT INTO simpoint_app_form_release_keys (id, tenant_id, request_key, binding_id, created_at)
  SELECT md5(json_build_array(tenant_id, idempotency_key)::text), tenant_id, idempotency_key, id, created_at
  FROM simpoint_app_form_page_bindings
  ON CONFLICT (tenant_id, request_key) DO NOTHING;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM simpoint_app_form_page_bindings b JOIN simpoint_app_form_release_keys k
      ON k.tenant_id = b.tenant_id AND k.request_key = b.idempotency_key WHERE k.binding_id <> b.id) THEN
    RAISE EXCEPTION 'A publication request key is assigned to a different release';
  END IF;
END $$;
ALTER TABLE simpoint_app_form_policy_publications ADD COLUMN IF NOT EXISTS cancellation_json text;
ALTER TABLE simpoint_app_form_policy_publications DROP CONSTRAINT IF EXISTS chk_app_policy_status;
ALTER TABLE simpoint_app_form_policy_publications ADD CONSTRAINT chk_app_policy_status
  CHECK (status IN ('PREPARED', 'CANCELLING', 'CANCELLED', 'TARGET_READY', 'ACTIVE'));
ALTER TABLE simpoint_app_form_policy_publications DROP CONSTRAINT IF EXISTS chk_app_policy_acknowledgement;
ALTER TABLE simpoint_app_form_policy_publications ADD CONSTRAINT chk_app_policy_acknowledgement CHECK (
  (status IN ('PREPARED', 'CANCELLING') AND target_fingerprint IS NULL AND cancellation_json IS NULL) OR
  (status IN ('TARGET_READY', 'ACTIVE', 'CANCELLED') AND target_fingerprint IS NOT NULL
    AND target_fingerprint ~ '^[a-f0-9]{64}$' AND inspection_json IS NULL
    AND ((status = 'CANCELLED' AND cancellation_json IS NOT NULL) OR
      (status IN ('TARGET_READY', 'ACTIVE') AND cancellation_json IS NULL))));
DROP INDEX IF EXISTS uk_app_policy_form_open;
CREATE UNIQUE INDEX uk_app_policy_form_open ON simpoint_app_form_policy_publications (tenant_id, form_id)
  WHERE status NOT IN ('ACTIVE', 'CANCELLED');
DROP INDEX IF EXISTS uk_app_policy_page_open;
CREATE UNIQUE INDEX uk_app_policy_page_open ON simpoint_app_form_policy_publications (tenant_id, page_id)
  WHERE status NOT IN ('ACTIVE', 'CANCELLED');
COMMIT;
