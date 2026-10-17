-- Run after 20261013-app-form-domain.sql. PostgreSQL APP control database.
-- Records the target page before remote DNA preparation. No data rewrite or physical DDL.
BEGIN;
ALTER TABLE simpoint_app_form_page_bindings
  DROP CONSTRAINT IF EXISTS chk_simpoint_app_form_page_binding_status;
ALTER TABLE simpoint_app_form_page_bindings
  ADD CONSTRAINT chk_simpoint_app_form_page_binding_status CHECK (status IN (
    'DATA_PREPARING', 'RESOURCE_PREPARING', 'RESOURCE_READY', 'ACTIVATING',
    'ACTIVE', 'RETIRED', 'FAILED'
  ));
COMMIT;

-- Rollback: deploy-compatible application code first. Do not remove DATA_PREPARING from
-- the constraint while such intents remain; resume them or retain this additive constraint.
