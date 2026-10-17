-- After policy-cancellation, with all APP publication writers stopped.
-- Legacy resource attempts must finish before a new policy may be prepared.
BEGIN;
LOCK TABLE simpoint_app_form_page_bindings, simpoint_app_form_policy_publications IN ACCESS EXCLUSIVE MODE;
ALTER TABLE simpoint_app_form_page_bindings
  ADD COLUMN IF NOT EXISTS policy_protocol_version integer NOT NULL DEFAULT 0;
ALTER TABLE simpoint_app_form_page_bindings DROP CONSTRAINT IF EXISTS chk_app_binding_policy_protocol;
ALTER TABLE simpoint_app_form_page_bindings ADD CONSTRAINT chk_app_binding_policy_protocol
  CHECK (policy_protocol_version IN (0, 1));
UPDATE simpoint_app_form_page_bindings b SET policy_protocol_version = 1
  WHERE b.policy_protocol_version = 0 AND (b.attempt = 0 OR EXISTS (
    SELECT 1 FROM simpoint_app_form_policy_publications p WHERE p.tenant_id = b.tenant_id AND p.binding_id = b.id));
COMMIT;
