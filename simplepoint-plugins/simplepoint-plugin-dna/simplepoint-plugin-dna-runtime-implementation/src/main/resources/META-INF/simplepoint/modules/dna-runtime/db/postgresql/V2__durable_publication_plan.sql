-- Preserve the exact trusted plan across partial DDL, process loss and acknowledgement loss.
-- Legacy rows remain unpopulated; recovery must not invent their original plan from live state.
ALTER TABLE simpoint_dna_schema_migrations ADD COLUMN plan_json text;
ALTER TABLE simpoint_dna_schema_migrations ADD COLUMN plan_checksum varchar(64);
ALTER TABLE simpoint_dna_schema_migrations ADD CONSTRAINT ck_dna_schema_plan_pair
  CHECK ((plan_json IS NULL AND plan_checksum IS NULL)
    OR (plan_json IS NOT NULL AND plan_checksum IS NOT NULL AND plan_checksum ~ '^[0-9a-f]{64}$'));
