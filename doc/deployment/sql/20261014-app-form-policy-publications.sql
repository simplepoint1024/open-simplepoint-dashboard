-- APP PostgreSQL control database; run after domain, release-intent and resource-generations.
-- Pause APP publication writes and drain old APP nodes before enabling policy coordination.
BEGIN;
ALTER TABLE simpoint_app_forms ADD COLUMN IF NOT EXISTS write_policy_version bigint NOT NULL DEFAULT 0;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_app_form_write_policy'
      AND conrelid = 'simpoint_app_forms'::regclass) THEN
    ALTER TABLE simpoint_app_forms ADD CONSTRAINT chk_app_form_write_policy CHECK (write_policy_version >= 0);
  END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_page_binding_policy_owner
  ON simpoint_app_form_page_bindings (tenant_id, application_id, form_id, page_id, id);
CREATE TABLE IF NOT EXISTS simpoint_app_form_policy_publications (
  id varchar(36) PRIMARY KEY,
  tenant_id varchar(64) NOT NULL,
  application_id varchar(36) NOT NULL,
  form_id varchar(36) NOT NULL,
  page_id varchar(36) NOT NULL,
  binding_id varchar(36) NOT NULL,
  governing_version integer NOT NULL,
  expected_version bigint NOT NULL,
  target_version bigint NOT NULL,
  command_json text NOT NULL,
  status varchar(24) NOT NULL,
  target_fingerprint varchar(64),
  inspection_json text,
  created_by varchar(64),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  lock_version bigint NOT NULL DEFAULT 0,
  CONSTRAINT uk_app_policy_binding UNIQUE (tenant_id, binding_id),
  CONSTRAINT uk_app_policy_version UNIQUE (tenant_id, form_id, target_version),
  CONSTRAINT fk_app_policy_binding FOREIGN KEY (tenant_id, application_id, form_id, page_id, binding_id)
    REFERENCES simpoint_app_form_page_bindings (tenant_id, application_id, form_id, page_id, id),
  CONSTRAINT fk_app_policy_governing_version FOREIGN KEY (tenant_id, form_id, governing_version)
    REFERENCES simpoint_app_form_versions (tenant_id, form_id, version_number),
  CONSTRAINT chk_app_policy_versions CHECK (expected_version >= 0 AND target_version > expected_version),
  CONSTRAINT chk_app_policy_status CHECK (status IN ('PREPARED', 'TARGET_READY', 'ACTIVE')),
  CONSTRAINT chk_app_policy_acknowledgement CHECK (
    (status = 'PREPARED' AND target_fingerprint IS NULL) OR
    (status IN ('TARGET_READY', 'ACTIVE') AND target_fingerprint IS NOT NULL AND target_fingerprint ~ '^[a-f0-9]{64}$' AND inspection_json IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_app_policy_pending
  ON simpoint_app_form_policy_publications (tenant_id, form_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_policy_form_open
  ON simpoint_app_form_policy_publications (tenant_id, form_id) WHERE status <> 'ACTIVE';
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_policy_page_open
  ON simpoint_app_form_policy_publications (tenant_id, page_id) WHERE status <> 'ACTIVE';
COMMIT;
-- Keep intents and nonzero versions on rollback. Never resume old, unfenced publication writers.
