-- New bootstrap tenants wait for authoritative resource declarations; legacy tenants keep explicit adoption.
ALTER TABLE simpoint_saas_tenant_resource_setup
    DROP CONSTRAINT simpoint_saas_tenant_resource_setup_state_check,
    ADD CONSTRAINT ck_tenant_resource_setup_state
        CHECK (state IN ('INITIALIZATION_REQUIRED', 'ADOPTION_REQUIRED', 'READY'));
-- The existing READY/initialized_at constraint continues to prevent premature readiness.
