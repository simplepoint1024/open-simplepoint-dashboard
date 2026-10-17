-- DNA control database, PostgreSQL. Older projection writers must not run after formal offline.
ALTER TABLE simpoint_dna_resource_projections
  ADD COLUMN IF NOT EXISTS formally_offline BOOLEAN NOT NULL DEFAULT FALSE;
