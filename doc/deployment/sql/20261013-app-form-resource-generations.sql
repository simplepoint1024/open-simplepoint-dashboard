-- APP control database, PostgreSQL. Run after domain and release-intent migrations.
-- Pause publication writes while assigning historic generations. This does not mutate business data.
BEGIN;
LOCK TABLE simpoint_app_form_page_bindings IN ACCESS EXCLUSIVE MODE;
ALTER TABLE simpoint_app_form_page_bindings ADD COLUMN IF NOT EXISTS generation bigint;
-- Rank all historic targets consistently, including failed/preparing intents. Only unassigned rows change.
WITH ranked AS (
  SELECT id, row_number() OVER (PARTITION BY tenant_id, page_id ORDER BY created_at, id) AS ordinal
  FROM simpoint_app_form_page_bindings
)
UPDATE simpoint_app_form_page_bindings b SET generation = ranked.ordinal
FROM ranked WHERE b.id = ranked.id AND b.generation IS NULL;
ALTER TABLE simpoint_app_form_page_bindings ALTER COLUMN generation SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_page_binding_generation
  ON simpoint_app_form_page_bindings (tenant_id, page_id, generation);
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_app_page_binding_generation' AND conrelid = 'simpoint_app_form_page_bindings'::regclass) THEN
    ALTER TABLE simpoint_app_form_page_bindings ADD CONSTRAINT chk_app_page_binding_generation CHECK (generation > 0);
  END IF;
END $$;
COMMIT;
-- Keep generations and DNA projection ownership on rollback. Older unfenced publication writers cannot be re-enabled.
