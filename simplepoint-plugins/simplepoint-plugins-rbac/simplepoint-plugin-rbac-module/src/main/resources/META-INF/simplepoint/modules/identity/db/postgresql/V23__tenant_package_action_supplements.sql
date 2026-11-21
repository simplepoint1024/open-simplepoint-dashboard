-- Record additional actions on their existing instance without changing purchase rows or balances.
ALTER TABLE simpoint_saas_tenant_package_instance_resources
    ADD COLUMN supplemental boolean NOT NULL DEFAULT false;
