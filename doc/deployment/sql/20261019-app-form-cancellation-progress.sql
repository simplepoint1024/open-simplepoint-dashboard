-- Apply after 20261018-app-form-publication-recovery.sql and before formula publication.
-- No business values or admission versions change. Retain nonterminal undo acknowledgements;
-- a completed batch is still not a terminal cancellation. Short exclusive lock validates all rows.
BEGIN;
SET LOCAL lock_timeout = '10s';
LOCK TABLE simpoint_app_form_policy_publications IN ACCESS EXCLUSIVE MODE;
ALTER TABLE simpoint_app_form_policy_publications DROP CONSTRAINT IF EXISTS chk_app_policy_acknowledgement;
ALTER TABLE simpoint_app_form_policy_publications ADD CONSTRAINT chk_app_policy_acknowledgement CHECK (
  (status = 'PREPARED' AND target_fingerprint IS NULL AND cancellation_json IS NULL) OR
  (status = 'CANCELLING' AND target_fingerprint IS NULL AND (cancellation_json IS NULL OR COALESCE(
    jsonb_typeof(cancellation_json::jsonb) = 'object'
    AND cancellation_json::jsonb -> 'cancelled' = 'false'::jsonb
    AND (cancellation_json::jsonb ->> 'version')::bigint = expected_version
    AND cancellation_json::jsonb ->> 'publicationFingerprint' ~ '^[a-f0-9]{64}$'
    AND COALESCE(cancellation_json::jsonb -> 'targetFingerprint', 'null'::jsonb) = 'null'::jsonb
    AND cancellation_json::jsonb #>> '{progress,phase}' = 'RESTORING'
    AND cancellation_json::jsonb #>> '{progress,contractVersion}' = '1'
    AND (cancellation_json::jsonb #>> '{progress,sequence}')::bigint > 0
    AND (cancellation_json::jsonb #>> '{progress,completedRecords}')::bigint >= 0
    AND (cancellation_json::jsonb #>> '{progress,totalRecords}')::bigint
      >= (cancellation_json::jsonb #>> '{progress,completedRecords}')::bigint, false))) OR
  (status IN ('TARGET_READY', 'ACTIVE', 'CANCELLED') AND target_fingerprint IS NOT NULL
    AND target_fingerprint ~ '^[a-f0-9]{64}$' AND inspection_json IS NULL
    AND ((status = 'CANCELLED' AND cancellation_json IS NOT NULL) OR
      (status IN ('TARGET_READY', 'ACTIVE') AND cancellation_json IS NULL))));
COMMIT;
