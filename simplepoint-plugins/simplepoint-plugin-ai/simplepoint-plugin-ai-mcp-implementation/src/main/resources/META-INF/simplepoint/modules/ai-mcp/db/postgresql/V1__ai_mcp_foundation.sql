-- PostgreSQL baseline owned by ai-mcp. No legacy data rewrites.

CREATE TABLE simpoint_ai_mcp_capability_snapshots (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    discovered_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    protocol_version character varying(32) NOT NULL,
    schema_hash character varying(64) NOT NULL,
    server_id character varying(64) NOT NULL,
    tenant_id character varying(64),
    remote_server_version character varying(128) NOT NULL,
    remote_server_name character varying(256) NOT NULL,
    capabilities_json text NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    prompts_json text NOT NULL,
    resource_templates_json text NOT NULL,
    resources_json text NOT NULL,
    tools_json text NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_mcp_snapshot_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT simpoint_ai_mcp_capability_snapshots_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_mcp_oauth_authorizations (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    used_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    connection_id character varying(64),
    server_id character varying(64) NOT NULL,
    state_hash character varying(64) NOT NULL,
    tenant_id character varying(64),
    user_id character varying(64),
    redirect_uri character varying(2048) NOT NULL,
    code_verifier_ciphertext character varying(4096) NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_mcp_oauth_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT simpoint_ai_mcp_oauth_authorizations_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_mcp_provider_connections (
    access_token_expires_at timestamp(6) with time zone,
    authorized_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    token_version bigint NOT NULL,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    status character varying(32) NOT NULL,
    server_id character varying(64) NOT NULL,
    tenant_id character varying(64),
    user_id character varying(64) NOT NULL,
    last_error character varying(1024),
    granted_scopes character varying(2048),
    access_token_ciphertext character varying(8192),
    refresh_token_ciphertext character varying(8192),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_mcp_provider_connection_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT ck_simpoint_ai_mcp_provider_connection_status CHECK ((status IN ('CONFIGURED', 'AUTHORIZATION_PENDING', 'CONNECTED', 'EXPIRED', 'ERROR'))),
    CONSTRAINT simpoint_ai_mcp_provider_connections_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_mcp_provider_connections_status_check CHECK ((status IN ('NOT_CONFIGURED', 'CONFIGURED', 'AUTHORIZATION_PENDING', 'CONNECTED', 'EXPIRED', 'ERROR')))
);

CREATE TABLE simpoint_ai_mcp_publications (
    enabled boolean NOT NULL,
    rate_limit_per_minute integer NOT NULL,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    status character varying(32) NOT NULL,
    tenant_id character varying(64),
    upstream_server_id character varying(64) NOT NULL,
    code character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    description character varying(512),
    authorization_server_uri character varying(2048) NOT NULL,
    canonical_resource_uri character varying(2048) NOT NULL,
    required_scopes character varying(2048) NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_mcp_publication_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT ck_simpoint_ai_mcp_publication_status CHECK ((status IN ('DRAFT', 'PUBLISHED', 'DISABLED', 'ERROR'))),
    CONSTRAINT simpoint_ai_mcp_publications_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_mcp_publications_status_check CHECK ((status IN ('DRAFT', 'PUBLISHED', 'DISABLED', 'ERROR')))
);

CREATE TABLE simpoint_ai_mcp_servers (
    allow_private_network boolean NOT NULL,
    enabled boolean NOT NULL,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    last_discovered_at timestamp(6) with time zone,
    oauth_access_token_expires_at timestamp(6) with time zone,
    oauth_authorized_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    authentication_type character varying(32) NOT NULL,
    deployment_type character varying(32) DEFAULT 'REMOTE'::character varying NOT NULL,
    oauth_status character varying(32) NOT NULL,
    protocol_version character varying(32),
    status character varying(32) NOT NULL,
    transport_type character varying(32) NOT NULL,
    active_snapshot_id character varying(64),
    oauth_token_endpoint_auth_method character varying(64),
    tenant_id character varying(64),
    code character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    remote_server_version character varying(128),
    remote_server_name character varying(256),
    description character varying(512),
    last_error character varying(1024),
    endpoint_url character varying(2048),
    oauth_authorization_endpoint character varying(2048),
    oauth_authorization_server character varying(2048),
    oauth_client_id character varying(2048),
    oauth_redirect_uri character varying(2048),
    oauth_registration_endpoint character varying(2048),
    oauth_resource_uri character varying(2048),
    oauth_scopes character varying(2048),
    oauth_token_endpoint character varying(2048),
    credential_ciphertext character varying(4096),
    oauth_access_token_ciphertext character varying(8192),
    oauth_refresh_token_ciphertext character varying(8192),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    oauth_authorization_server_metadata text,
    oauth_protected_resource_metadata text,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_mcp_server_deployment CHECK (((((deployment_type)::text = 'REMOTE'::text) AND ((transport_type)::text = 'STREAMABLE_HTTP'::text) AND (endpoint_url IS NOT NULL)) OR (((deployment_type)::text = 'MANAGED_OCI'::text) AND ((transport_type)::text = 'STDIO'::text) AND (endpoint_url IS NULL) AND (authentication_type IN ('NONE', 'OAUTH2')) AND (allow_private_network = false)))),
    CONSTRAINT ck_simpoint_ai_mcp_server_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT simpoint_ai_mcp_servers_authentication_type_check CHECK ((authentication_type IN ('NONE', 'BEARER', 'OAUTH2'))),
    CONSTRAINT simpoint_ai_mcp_servers_deployment_type_check CHECK ((deployment_type IN ('REMOTE', 'MANAGED_OCI'))),
    CONSTRAINT simpoint_ai_mcp_servers_oauth_status_check CHECK ((oauth_status IN ('NOT_CONFIGURED', 'CONFIGURED', 'AUTHORIZATION_PENDING', 'CONNECTED', 'EXPIRED', 'ERROR'))),
    CONSTRAINT simpoint_ai_mcp_servers_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_mcp_servers_status_check CHECK ((status IN ('DRAFT', 'READY', 'ERROR', 'DISABLED'))),
    CONSTRAINT simpoint_ai_mcp_servers_transport_type_check CHECK ((transport_type IN ('STREAMABLE_HTTP', 'STDIO')))
);

CREATE TABLE simpoint_ai_mcp_tasks (
    attempt_count integer NOT NULL,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone NOT NULL,
    lease_expires_at timestamp(6) with time zone,
    lease_token bigint NOT NULL,
    lock_version bigint NOT NULL,
    next_attempt_at timestamp(6) with time zone,
    poll_interval_millis bigint NOT NULL,
    started_at timestamp(6) with time zone,
    ttl_millis bigint NOT NULL,
    updated_at timestamp(6) with time zone,
    status character varying(24) NOT NULL,
    client_hash character varying(64) NOT NULL,
    error_code character varying(64),
    operation_id character varying(64) NOT NULL,
    subject_hash character varying(64) NOT NULL,
    lease_owner character varying(128),
    publication_code character varying(128) NOT NULL,
    tool_name character varying(256) NOT NULL,
    client_id character varying(512) NOT NULL,
    source_session_id character varying(512),
    subject_id character varying(512) NOT NULL,
    error_message character varying(1024),
    status_message character varying(1024),
    arguments_json text NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    result_json text,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_mcp_task_integrity CHECK ((((subject_hash)::text ~ '^[0-9a-f]{64}$'::text) AND ((client_hash)::text ~ '^[0-9a-f]{64}$'::text) AND (ttl_millis >= 1000) AND (poll_interval_millis > 0) AND (lease_token >= 0) AND (attempt_count >= 0) AND (((lease_owner IS NULL) AND (lease_expires_at IS NULL)) OR ((lease_owner IS NOT NULL) AND (lease_expires_at IS NOT NULL))) AND ((status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')) OR (completed_at IS NOT NULL)))),
    CONSTRAINT ck_simpoint_ai_mcp_task_status CHECK ((status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))),
    CONSTRAINT simpoint_ai_mcp_tasks_status_check CHECK ((status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')))
);

CREATE TABLE simpoint_ai_mcp_tool_invocations (
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    duration_millis bigint,
    started_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    capability_type character varying(16) NOT NULL,
    scope_type character varying(16) NOT NULL,
    status character varying(32) NOT NULL,
    context_id character varying(64),
    request_hash character varying(64) NOT NULL,
    result_hash character varying(64),
    runtime_revision_id character varying(64),
    server_id character varying(64) NOT NULL,
    snapshot_id character varying(64) NOT NULL,
    tenant_id character varying(64),
    trace_id character varying(64) NOT NULL,
    user_id character varying(64),
    error_code character varying(128),
    error_message character varying(1024),
    client_id character varying(2048),
    capability_name character varying(4096) NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_mcp_invocation_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT simpoint_ai_mcp_tool_invocations_capability_type_check CHECK ((capability_type IN ('TOOL', 'RESOURCE', 'PROMPT'))),
    CONSTRAINT simpoint_ai_mcp_tool_invocations_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_mcp_tool_invocations_status_check CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED')))
);

ALTER TABLE ONLY simpoint_ai_mcp_capability_snapshots
    ADD CONSTRAINT simpoint_ai_mcp_capability_snapshots_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_mcp_oauth_authorizations
    ADD CONSTRAINT simpoint_ai_mcp_oauth_authorizations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_mcp_oauth_authorizations
    ADD CONSTRAINT simpoint_ai_mcp_oauth_authorizations_state_hash_key UNIQUE (state_hash);

ALTER TABLE ONLY simpoint_ai_mcp_provider_connections
    ADD CONSTRAINT simpoint_ai_mcp_provider_connections_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_mcp_publications
    ADD CONSTRAINT simpoint_ai_mcp_publications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_mcp_servers
    ADD CONSTRAINT simpoint_ai_mcp_servers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_mcp_tasks
    ADD CONSTRAINT simpoint_ai_mcp_tasks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_mcp_tool_invocations
    ADD CONSTRAINT simpoint_ai_mcp_tool_invocations_pkey PRIMARY KEY (id);

CREATE INDEX idx_simpoint_ai_mcp_connection_server ON simpoint_ai_mcp_provider_connections USING btree (server_id);

CREATE INDEX idx_simpoint_ai_mcp_connection_subject ON simpoint_ai_mcp_provider_connections USING btree (tenant_id, user_id);

CREATE INDEX idx_simpoint_ai_mcp_invocation_capability ON simpoint_ai_mcp_tool_invocations USING btree (capability_type, capability_name);

CREATE INDEX idx_simpoint_ai_mcp_invocation_scope ON simpoint_ai_mcp_tool_invocations USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_mcp_invocation_server ON simpoint_ai_mcp_tool_invocations USING btree (server_id);

CREATE INDEX idx_simpoint_ai_mcp_invocation_started ON simpoint_ai_mcp_tool_invocations USING btree (started_at);

CREATE INDEX idx_simpoint_ai_mcp_oauth_server ON simpoint_ai_mcp_oauth_authorizations USING btree (server_id);

CREATE INDEX idx_simpoint_ai_mcp_oauth_state ON simpoint_ai_mcp_oauth_authorizations USING btree (state_hash);

CREATE INDEX idx_simpoint_ai_mcp_publication_code ON simpoint_ai_mcp_publications USING btree (code);

CREATE INDEX idx_simpoint_ai_mcp_publication_scope ON simpoint_ai_mcp_publications USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_mcp_server_scope ON simpoint_ai_mcp_servers USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_mcp_server_status ON simpoint_ai_mcp_servers USING btree (status);

CREATE INDEX idx_simpoint_ai_mcp_snapshot_scope ON simpoint_ai_mcp_capability_snapshots USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_mcp_snapshot_server ON simpoint_ai_mcp_capability_snapshots USING btree (server_id);

CREATE INDEX idx_simpoint_ai_mcp_task_claim ON simpoint_ai_mcp_tasks USING btree (status, next_attempt_at, lease_expires_at);

CREATE INDEX idx_simpoint_ai_mcp_task_due ON simpoint_ai_mcp_tasks USING btree (status, next_attempt_at, lease_expires_at);

CREATE INDEX idx_simpoint_ai_mcp_task_expiry ON simpoint_ai_mcp_tasks USING btree (expires_at);

CREATE INDEX idx_simpoint_ai_mcp_task_owner ON simpoint_ai_mcp_tasks USING btree (publication_code, subject_hash, client_hash, created_at);

CREATE INDEX idx_simpoint_ai_mcp_task_owner_cursor ON simpoint_ai_mcp_tasks USING btree (publication_code, subject_hash, client_hash, created_at DESC, id DESC);

CREATE UNIQUE INDEX uk_simpoint_ai_mcp_oauth_state ON simpoint_ai_mcp_oauth_authorizations USING btree (state_hash) WHERE (deleted_at IS NULL);

CREATE UNIQUE INDEX uk_simpoint_ai_mcp_provider_connection_subject ON simpoint_ai_mcp_provider_connections USING btree (server_id, user_id) WHERE (deleted_at IS NULL);

CREATE UNIQUE INDEX uk_simpoint_ai_mcp_publication_active_code ON simpoint_ai_mcp_publications USING btree (code) WHERE (deleted_at IS NULL);

CREATE UNIQUE INDEX uk_simpoint_ai_mcp_server_active_system_code ON simpoint_ai_mcp_servers USING btree (code) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_mcp_server_active_tenant_code ON simpoint_ai_mcp_servers USING btree (tenant_id, code) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));
