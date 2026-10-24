-- Historical revocations may have no recorded actor; new domain writes always record the verified administrator.
ALTER TABLE simpoint_saas_tenant_package_instances ADD COLUMN revoked_by varchar(36);
