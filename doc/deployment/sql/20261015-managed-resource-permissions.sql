-- Permission descriptors are owned by the generating service and published with its resource generation.
ALTER TABLE simpoint_saas_tenant_resources
  ADD COLUMN IF NOT EXISTS permission_metadata text;
