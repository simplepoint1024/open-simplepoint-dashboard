-- Existing bindings remain unadopted. Never infer physical ownership from a datasource ID or URL.
ALTER TABLE simpoint_dna_physical_bindings ADD COLUMN physical_namespace_id VARCHAR(36);
