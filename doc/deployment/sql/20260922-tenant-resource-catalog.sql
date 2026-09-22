-- Apply before starting the Common service that includes tenant catalog entities.
ALTER TABLE simpoint_saas_tenants
  ADD COLUMN IF NOT EXISTS resource_mode varchar(16) NOT NULL DEFAULT 'PLATFORM';

CREATE TABLE IF NOT EXISTS simpoint_saas_tenant_resources (
  tenant_id varchar(36) NOT NULL,
  id varchar(36) NOT NULL,
  platform_resource_id varchar(36),
  target_resource_id varchar(36),
  parent_id varchar(36),
  code varchar(120) NOT NULL,
  name varchar(100) NOT NULL,
  type varchar(32) NOT NULL,
  path varchar(200),
  component varchar(160),
  route_kind varchar(32),
  icon varchar(100),
  sort integer,
  description varchar(255),
  enabled boolean NOT NULL DEFAULT true,
  grantable boolean NOT NULL DEFAULT false,
  local_modified boolean NOT NULL DEFAULT false,
  manual_disabled boolean NOT NULL DEFAULT false,
  updated_at timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT pk_tenant_resources PRIMARY KEY (tenant_id, id),
  CONSTRAINT uq_tenant_resource_code UNIQUE (tenant_id, code),
  CONSTRAINT uq_tenant_resource_source UNIQUE (tenant_id, platform_resource_id)
);

CREATE INDEX IF NOT EXISTS idx_tenant_resources_parent
  ON simpoint_saas_tenant_resources (tenant_id, parent_id, sort, name);
CREATE INDEX IF NOT EXISTS idx_tenant_resources_target
  ON simpoint_saas_tenant_resources (tenant_id, target_resource_id);
