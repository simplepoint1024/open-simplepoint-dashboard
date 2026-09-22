-- PostgreSQL, run once before rolling out the permission-expression release.
-- Additive migration; existing null page_policy retains legacy role-wide compatibility.
BEGIN;
ALTER TABLE simpoint_ac_resources ADD COLUMN IF NOT EXISTS permission_metadata text;
ALTER TABLE simpoint_ac_role_resource_grants ADD COLUMN IF NOT EXISTS page_policy boolean;
ALTER TABLE simpoint_ac_data_scope ADD COLUMN IF NOT EXISTS resource_code varchar(200);
ALTER TABLE simpoint_ac_data_scope ADD COLUMN IF NOT EXISTS expression text;
-- Hibernate update does not reliably widen existing enum CHECK constraints.
DO $$
DECLARE definition record;
BEGIN
  FOR definition IN
    SELECT conname FROM pg_constraint
    WHERE conrelid = 'simpoint_ac_data_scope'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) ~ '\mtype\M'
  LOOP
    EXECUTE format('ALTER TABLE simpoint_ac_data_scope DROP CONSTRAINT %I', definition.conname);
  END LOOP;
END $$;
ALTER TABLE simpoint_ac_data_scope ADD CONSTRAINT simpoint_data_scope_type_check
  CHECK (type IN ('SELF', 'CUSTOM', 'DEPT', 'DEPT_AND_BELOW', 'ALL', 'EXPRESSION'));
CREATE INDEX IF NOT EXISTS idx_data_scope_tenant_page
  ON simpoint_ac_data_scope (tenant_id, resource_code) WHERE deleted_at IS NULL;
COMMIT;
