ALTER TABLE simpoint_app_definitions ADD COLUMN name_localization TEXT;
ALTER TABLE simpoint_app_pages ADD COLUMN name_localization TEXT;
ALTER TABLE simpoint_app_definitions ADD COLUMN navigation_definition TEXT;
ALTER TABLE simpoint_app_definitions ADD COLUMN navigation_pending TEXT;
ALTER TABLE simpoint_app_definitions ADD COLUMN navigation_revision BIGINT NOT NULL DEFAULT 0;
