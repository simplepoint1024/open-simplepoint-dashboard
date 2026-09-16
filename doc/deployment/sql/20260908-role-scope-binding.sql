-- Additive PostgreSQL migration. Review and execute against the intended database only.
-- Existing policies/grants are not updated or deleted.
BEGIN;
CREATE TABLE IF NOT EXISTS simpoint_ac_role_scope_binding (
    id varchar(255) PRIMARY KEY,
    tenant_id varchar(255) NOT NULL,
    role_id varchar(255) NOT NULL,
    data_scope_id varchar(255),
    field_scope_id varchar(255),
    revision bigint,
    deleted_at timestamp(6) with time zone,
    create_org_dept_id varchar(255),
    created_by varchar(255),
    updated_by varchar(255),
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    CONSTRAINT uq_role_scope_binding UNIQUE (tenant_id, role_id)
);
COMMIT;

-- Read-only audit: legacy roles needing explicit conflict confirmation.
SELECT tenant_id, role_id,
       count(DISTINCT data_scope_id) AS data_scope_count,
       count(DISTINCT field_scope_id) AS field_scope_count
FROM simpoint_ac_role_resource_grants
WHERE deleted_at IS NULL
GROUP BY tenant_id, role_id
HAVING count(DISTINCT data_scope_id) > 1 OR count(DISTINCT field_scope_id) > 1;
