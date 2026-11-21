-- Immutable server-resolved relation identities. Legacy versions retain absence.
ALTER TABLE simpoint_app_form_versions ADD COLUMN relation_bindings_json TEXT;
