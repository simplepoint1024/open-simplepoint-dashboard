-- Immutable per-version owned-model identities. Existing forms retain NULL (no children).
ALTER TABLE simpoint_app_form_versions
  ADD COLUMN IF NOT EXISTS detail_bindings_json TEXT;
