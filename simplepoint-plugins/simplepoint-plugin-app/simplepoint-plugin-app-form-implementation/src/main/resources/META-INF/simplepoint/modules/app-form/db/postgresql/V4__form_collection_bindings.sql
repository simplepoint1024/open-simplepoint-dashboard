-- Frozen association schemas are separate from owned detail models.
ALTER TABLE simpoint_app_form_versions ADD COLUMN collection_bindings_json TEXT;
