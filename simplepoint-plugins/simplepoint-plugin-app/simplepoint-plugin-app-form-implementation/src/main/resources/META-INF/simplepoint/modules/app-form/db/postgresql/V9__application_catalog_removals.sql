CREATE TABLE simpoint_app_catalog_removals (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  application_id VARCHAR(36) NOT NULL UNIQUE,
  application_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  navigation_revision BIGINT NOT NULL,
  requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
  catalog_requested_at TIMESTAMP WITH TIME ZONE,
  completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_app_catalog_removal_pending ON simpoint_app_catalog_removals
  (requested_at, id) WHERE catalog_requested_at IS NULL;
