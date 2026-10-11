CREATE TABLE IF NOT EXISTS simpoint_app_definitions (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  icon VARCHAR(60),
  status VARCHAR(20) NOT NULL,
  main_page_id VARCHAR(36) NOT NULL,
  created_by VARCHAR(64),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_simpoint_app_tenant_updated
  ON simpoint_app_definitions (tenant_id, updated_at);

CREATE TABLE IF NOT EXISTS simpoint_app_pages (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  application_id VARCHAR(36) NOT NULL REFERENCES simpoint_app_definitions(id),
  kind VARCHAR(12) NOT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  sort_order INTEGER NOT NULL,
  status VARCHAR(20) NOT NULL,
  resource_id VARCHAR(64),
  resource_code VARCHAR(160),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_simpoint_app_page_kind CHECK (kind IN ('MAIN', 'ATTACHED'))
);
CREATE INDEX IF NOT EXISTS idx_simpoint_app_page_tenant_app
  ON simpoint_app_pages (tenant_id, application_id, sort_order);
