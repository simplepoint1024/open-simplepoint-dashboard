-- Additive opt-in. Existing applications keep their original managed-resource parent.
ALTER TABLE simpoint_app_definitions
  ADD COLUMN IF NOT EXISTS navigation_group BOOLEAN NOT NULL DEFAULT FALSE;
