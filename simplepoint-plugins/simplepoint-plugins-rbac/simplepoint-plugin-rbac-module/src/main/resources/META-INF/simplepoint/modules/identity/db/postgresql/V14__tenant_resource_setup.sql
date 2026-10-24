-- Existing tenants must undergo explicit entitlement adoption; newly created tenants have no setup row yet.
CREATE TABLE simpoint_saas_tenant_resource_setup (
    tenant_id varchar(36) PRIMARY KEY,
    state varchar(32) NOT NULL CHECK (state IN ('ADOPTION_REQUIRED', 'READY')),
    initialized_at timestamp(6) with time zone,
    adopted_at timestamp(6) with time zone,
    CONSTRAINT ck_tenant_resource_setup_ready CHECK ((state = 'READY') = (initialized_at IS NOT NULL))
);
INSERT INTO simpoint_saas_tenant_resource_setup (tenant_id, state)
SELECT id, 'ADOPTION_REQUIRED' FROM simpoint_saas_tenants WHERE deleted_at IS NULL;
