-- Activation receipts and resource time balances belong to identity's transaction/storage.
-- Existing tenant-package relations are adopted separately; this migration grants no new rights.
CREATE TABLE simpoint_saas_tenant_package_instances (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL,
    package_code varchar(128) NOT NULL,
    package_name varchar(128) NOT NULL,
    duration_days integer,
    state varchar(16) NOT NULL,
    assigned_at timestamp(6) with time zone NOT NULL,
    assigned_by varchar(36) NOT NULL,
    activated_at timestamp(6) with time zone,
    activated_by varchar(36),
    revoked_at timestamp(6) with time zone,
    CONSTRAINT uq_tenant_package_instance UNIQUE (tenant_id, id),
    CONSTRAINT ck_package_instance_duration CHECK (duration_days IS NULL OR duration_days > 0),
    CONSTRAINT ck_package_instance_state CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    CONSTRAINT ck_package_instance_activation CHECK (
        (activated_at IS NULL AND activated_by IS NULL) OR
        (activated_at IS NOT NULL AND activated_by IS NOT NULL)),
    CONSTRAINT ck_package_instance_pending CHECK (state <> 'PENDING' OR activated_at IS NULL),
    CONSTRAINT ck_package_instance_active CHECK (state <> 'ACTIVE' OR activated_at IS NOT NULL),
    CONSTRAINT ck_package_instance_revoked CHECK ((state = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE INDEX idx_tenant_package_instance_list
    ON simpoint_saas_tenant_package_instances (tenant_id, assigned_at DESC, id DESC);
CREATE TABLE simpoint_saas_tenant_package_instance_resources (
    instance_id varchar(36) NOT NULL REFERENCES simpoint_saas_tenant_package_instances (id),
    resource_id varchar(36) NOT NULL,
    PRIMARY KEY (instance_id, resource_id)
);
CREATE TABLE simpoint_saas_tenant_resource_entitlements (
    tenant_id varchar(36) NOT NULL,
    resource_id varchar(36) NOT NULL,
    starts_at timestamp(6) with time zone NOT NULL,
    expires_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (tenant_id, resource_id),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES simpoint_saas_tenant_resources (tenant_id, id),
    CONSTRAINT ck_resource_entitlement_window CHECK (expires_at IS NULL OR expires_at > starts_at)
);
CREATE INDEX idx_tenant_entitlement_expiry
    ON simpoint_saas_tenant_resource_entitlements (tenant_id, expires_at);
CREATE TABLE simpoint_saas_tenant_resource_credits (
    id varchar(36) PRIMARY KEY,
    tenant_id varchar(36) NOT NULL,
    instance_id varchar(36) NOT NULL,
    resource_id varchar(36) NOT NULL,
    duration_days integer,
    activated_at timestamp(6) with time zone NOT NULL,
    activated_by varchar(36) NOT NULL,
    result_expires_at timestamp(6) with time zone,
    revoked_at timestamp(6) with time zone,
    CONSTRAINT uq_tenant_resource_credit UNIQUE (tenant_id, instance_id, resource_id),
    FOREIGN KEY (tenant_id, instance_id) REFERENCES simpoint_saas_tenant_package_instances (tenant_id, id),
    FOREIGN KEY (instance_id, resource_id) REFERENCES simpoint_saas_tenant_package_instance_resources (instance_id, resource_id),
    FOREIGN KEY (tenant_id, resource_id) REFERENCES simpoint_saas_tenant_resources (tenant_id, id),
    CONSTRAINT ck_resource_credit_duration CHECK (duration_days IS NULL OR duration_days > 0),
    CONSTRAINT ck_resource_credit_result CHECK (result_expires_at IS NULL OR result_expires_at > activated_at)
);
CREATE INDEX idx_tenant_resource_credit_history
    ON simpoint_saas_tenant_resource_credits (tenant_id, resource_id, activated_at, id);
