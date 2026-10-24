-- Identity references only. Historical operations require explicit authorized adoption.
ALTER TABLE simpoint_app_form_policy_publications
  ADD COLUMN IF NOT EXISTS recovery_actor_id varchar(64),
  ADD COLUMN IF NOT EXISTS recovery_role_id varchar(64),
  ADD COLUMN IF NOT EXISTS recovery_not_before timestamptz,
  ADD COLUMN IF NOT EXISTS recovery_error varchar(64),
  ADD COLUMN IF NOT EXISTS recovery_failures integer NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_app_policy_recovery
  ON simpoint_app_form_policy_publications (recovery_not_before, id)
  WHERE recovery_actor_id IS NOT NULL AND status IN ('PREPARED', 'CANCELLING', 'TARGET_READY');
