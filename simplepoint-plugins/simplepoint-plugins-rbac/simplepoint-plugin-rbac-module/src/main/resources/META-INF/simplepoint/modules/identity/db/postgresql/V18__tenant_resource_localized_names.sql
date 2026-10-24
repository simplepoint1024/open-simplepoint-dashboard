-- Tenant field translations belong to the resource aggregate, never the global message namespace.
-- NULL preserves legacy snapshots until their owner explicitly edits a localized name.
ALTER TABLE simpoint_saas_tenant_resources ADD COLUMN name_localization TEXT;
