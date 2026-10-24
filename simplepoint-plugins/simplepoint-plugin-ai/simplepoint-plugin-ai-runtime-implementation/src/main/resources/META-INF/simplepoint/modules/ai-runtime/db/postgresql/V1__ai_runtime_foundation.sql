-- PostgreSQL baseline owned by ai-runtime. No legacy data rewrites.

CREATE TABLE simpoint_ai_runtime_leases (
    acquired_at timestamp(6) with time zone NOT NULL,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone NOT NULL,
    fencing_token bigint NOT NULL,
    lock_version bigint NOT NULL,
    released_at timestamp(6) with time zone,
    renewed_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    status character varying(16) NOT NULL,
    node_id character varying(64) NOT NULL,
    workload_id character varying(64) NOT NULL,
    node_instance_id character varying(128) NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_runtime_lease_fence CHECK ((fencing_token > 0)),
    CONSTRAINT ck_simpoint_ai_runtime_lease_status CHECK ((status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'FENCED'))),
    CONSTRAINT simpoint_ai_runtime_leases_status_check CHECK ((status IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'FENCED')))
);

CREATE TABLE simpoint_ai_runtime_mcp_descriptors (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    lock_version bigint NOT NULL,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    content_hash character varying(64) NOT NULL,
    source_catalog_entry_id character varying(64),
    tenant_id character varying(64),
    server_version character varying(128) NOT NULL,
    title character varying(256),
    registry_name character varying(512) NOT NULL,
    repository_url character varying(2048),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    descriptor_json text NOT NULL,
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_runtime_descriptor_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT simpoint_ai_runtime_mcp_descriptors_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_runtime_mcp_profiles (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    lock_version bigint NOT NULL,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    status character varying(32) NOT NULL,
    active_revision_id character varying(64),
    code character varying(64) NOT NULL,
    descriptor_id character varying(64) NOT NULL,
    spec_hash character varying(64) NOT NULL,
    tenant_id character varying(64),
    name character varying(128) NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    spec_json text NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_runtime_profile_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT ck_simpoint_ai_runtime_profile_status CHECK ((status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))),
    CONSTRAINT simpoint_ai_runtime_mcp_profiles_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_runtime_mcp_profiles_status_check CHECK ((status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')))
);

CREATE TABLE simpoint_ai_runtime_mcp_revisions (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    published_at timestamp(6) with time zone NOT NULL,
    revision_number bigint NOT NULL,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    admission_report_hash character varying(64),
    descriptor_content_hash character varying(64) NOT NULL,
    descriptor_id character varying(64) NOT NULL,
    profile_hash character varying(64) NOT NULL,
    profile_id character varying(64) NOT NULL,
    tenant_id character varying(64),
    image_digest character varying(71),
    image_reference character varying(512),
    admission_report_json text,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    profile_json text NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_runtime_revision_integrity CHECK (((revision_number > 0) AND ((descriptor_content_hash)::text ~ '^[0-9a-f]{64}$'::text) AND ((profile_hash)::text ~ '^[0-9a-f]{64}$'::text) AND (((image_reference IS NULL) AND (image_digest IS NULL)) OR ((image_reference IS NOT NULL) AND ((image_digest)::text ~ '^sha256:[0-9a-f]{64}$'::text))))),
    CONSTRAINT ck_simpoint_ai_runtime_revision_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT simpoint_ai_runtime_mcp_revisions_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_runtime_nodes (
    allow_bridge_network boolean NOT NULL,
    allow_egress_network boolean DEFAULT false NOT NULL,
    apparmor_enforced boolean DEFAULT false NOT NULL,
    cpu_cores integer NOT NULL,
    max_workloads integer NOT NULL,
    require_image_digest boolean NOT NULL,
    require_mcp_labels boolean NOT NULL,
    require_supply_chain_admission boolean DEFAULT false NOT NULL,
    running_workloads integer NOT NULL,
    seccomp_enforced boolean DEFAULT false NOT NULL,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    generation bigint NOT NULL,
    heartbeat_expires_at timestamp(6) with time zone NOT NULL,
    last_heartbeat_at timestamp(6) with time zone NOT NULL,
    lock_version bigint NOT NULL,
    max_workload_memory_bytes bigint DEFAULT '2147483648'::bigint NOT NULL,
    max_workload_nano_cpus bigint DEFAULT '4000000000'::bigint NOT NULL,
    max_workload_pids_limit bigint DEFAULT 512 NOT NULL,
    memory_bytes bigint NOT NULL,
    registered_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    engine_api_version character varying(32) NOT NULL,
    engine_os_type character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    node_id character varying(64) NOT NULL,
    runtime_version character varying(64) NOT NULL,
    seccomp_profile_hash character varying(71),
    apparmor_profile character varying(128),
    display_name character varying(128) NOT NULL,
    instance_id character varying(128) NOT NULL,
    last_error character varying(1024),
    advertise_url character varying(2048) NOT NULL,
    cached_image_digests_json text DEFAULT '[]'::text NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    labels_json text NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_runtime_node_capacity CHECK (((generation > 0) AND (cpu_cores > 0) AND (memory_bytes >= 33554432) AND (max_workloads > 0) AND (running_workloads >= 0) AND (running_workloads <= max_workloads) AND (max_workload_memory_bytes >= 33554432) AND (max_workload_memory_bytes <= memory_bytes) AND (max_workload_nano_cpus > 0) AND (max_workload_nano_cpus <= (cpu_cores * (1000000000)::bigint)) AND (max_workload_pids_limit > 0) AND (max_workload_pids_limit <= 4096))),
    CONSTRAINT simpoint_ai_runtime_nodes_status_check CHECK ((status IN ('REGISTERING', 'READY', 'DRAINING', 'ERROR', 'OFFLINE')))
);

CREATE TABLE simpoint_ai_runtime_pools (
    activation_replicas integer NOT NULL,
    current_replicas integer NOT NULL,
    desired_replicas integer NOT NULL,
    max_replicas integer NOT NULL,
    min_replicas integer NOT NULL,
    prewarm_nodes integer NOT NULL,
    prewarmed_nodes integer NOT NULL,
    ready_replicas integer NOT NULL,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    idle_timeout_seconds bigint NOT NULL,
    last_activity_at timestamp(6) with time zone NOT NULL,
    last_prewarm_at timestamp(6) with time zone,
    last_reconciled_at timestamp(6) with time zone,
    lock_version bigint NOT NULL,
    next_replica_sequence bigint NOT NULL,
    replica_lifetime_seconds bigint NOT NULL,
    requested_memory_bytes bigint NOT NULL,
    requested_nano_cpus bigint NOT NULL,
    requested_pids_limit bigint NOT NULL,
    updated_at timestamp(6) with time zone,
    network_mode character varying(16) NOT NULL,
    scope_type character varying(16) NOT NULL,
    status character varying(32) NOT NULL,
    active_revision_id character varying(64),
    code character varying(64) NOT NULL,
    runtime_profile_id character varying(64),
    server_id character varying(64) NOT NULL,
    tenant_id character varying(64),
    image_digest character varying(71) NOT NULL,
    name character varying(128) NOT NULL,
    image_reference character varying(512) NOT NULL,
    last_error character varying(1024),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    egress_allowlist_json text DEFAULT '[]'::text NOT NULL,
    id character varying(255) NOT NULL,
    runtime_spec_json text,
    secret_references_json text DEFAULT '[]'::text NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_runtime_pool_network CHECK ((((network_mode IN ('egress', 'tcp-egress', 'internal-service')) AND (egress_allowlist_json <> '[]'::text)) OR ((network_mode IN ('none', 'bridge')) AND (egress_allowlist_json = '[]'::text)))),
    CONSTRAINT ck_simpoint_ai_runtime_pool_policy CHECK (((min_replicas >= 0) AND (max_replicas > 0) AND (min_replicas <= max_replicas) AND (desired_replicas >= 0) AND (desired_replicas <= max_replicas) AND (activation_replicas > 0) AND (activation_replicas <= max_replicas) AND (prewarm_nodes >= 0) AND (prewarm_nodes <= max_replicas) AND (idle_timeout_seconds >= 0) AND ((replica_lifetime_seconds >= 60) AND (replica_lifetime_seconds <= 86400)) AND (current_replicas >= 0) AND (ready_replicas >= 0) AND (prewarmed_nodes >= 0) AND (next_replica_sequence > 0))),
    CONSTRAINT ck_simpoint_ai_runtime_pool_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT ck_simpoint_ai_runtime_pool_status CHECK ((status IN ('SCALING', 'READY', 'IDLE', 'ERROR', 'DISABLED'))),
    CONSTRAINT simpoint_ai_runtime_pools_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_runtime_pools_status_check CHECK ((status IN ('SCALING', 'READY', 'IDLE', 'ERROR', 'DISABLED')))
);

CREATE TABLE simpoint_ai_runtime_secrets (
    enabled boolean NOT NULL,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    rotated_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    code character varying(64) NOT NULL,
    tenant_id character varying(64),
    name character varying(128) NOT NULL,
    description character varying(512),
    value_ciphertext character varying(131072) NOT NULL,
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_runtime_secret_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT simpoint_ai_runtime_secrets_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_runtime_workloads (
    created_at timestamp(6) with time zone,
    deadline_at timestamp(6) with time zone NOT NULL,
    deleted_at timestamp(6) with time zone,
    fencing_token bigint NOT NULL,
    finished_at timestamp(6) with time zone,
    last_observed_at timestamp(6) with time zone,
    lock_version bigint NOT NULL,
    replica_sequence bigint,
    requested_memory_bytes bigint NOT NULL,
    requested_nano_cpus bigint NOT NULL,
    requested_pids_limit bigint NOT NULL,
    started_at timestamp(6) with time zone,
    timeout_seconds bigint NOT NULL,
    updated_at timestamp(6) with time zone,
    network_mode character varying(16) NOT NULL,
    scope_type character varying(16) NOT NULL,
    status character varying(32) NOT NULL,
    assigned_node_id character varying(64),
    execution_id character varying(64) NOT NULL,
    lease_id character varying(64),
    pool_id character varying(64),
    runtime_profile_id character varying(64),
    runtime_revision_id character varying(64),
    runtime_workload_id character varying(64),
    server_id character varying(64) NOT NULL,
    tenant_id character varying(64),
    image_digest character varying(71) NOT NULL,
    container_id character varying(128),
    image_reference character varying(512) NOT NULL,
    last_error character varying(1024),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    egress_allowlist_json text DEFAULT '[]'::text NOT NULL,
    id character varying(255) NOT NULL,
    runtime_spec_json text,
    secret_references_json text DEFAULT '[]'::text NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_runtime_workload_fence CHECK ((fencing_token >= 0)),
    CONSTRAINT ck_simpoint_ai_runtime_workload_network CHECK ((((network_mode IN ('egress', 'tcp-egress', 'internal-service')) AND (egress_allowlist_json <> '[]'::text)) OR ((network_mode IN ('none', 'bridge')) AND (egress_allowlist_json = '[]'::text)))),
    CONSTRAINT ck_simpoint_ai_runtime_workload_scope CHECK (((((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL)) OR (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL)))),
    CONSTRAINT ck_simpoint_ai_runtime_workload_status CHECK ((status IN ('PENDING', 'ASSIGNED', 'STARTING', 'RUNNING', 'STOPPING', 'SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED'))),
    CONSTRAINT simpoint_ai_runtime_workloads_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_runtime_workloads_status_check CHECK ((status IN ('PENDING', 'ASSIGNED', 'STARTING', 'RUNNING', 'STOPPING', 'SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED')))
);

ALTER TABLE ONLY simpoint_ai_runtime_leases
    ADD CONSTRAINT simpoint_ai_runtime_leases_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_runtime_mcp_descriptors
    ADD CONSTRAINT simpoint_ai_runtime_mcp_descriptors_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_runtime_mcp_profiles
    ADD CONSTRAINT simpoint_ai_runtime_mcp_profiles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_runtime_mcp_revisions
    ADD CONSTRAINT simpoint_ai_runtime_mcp_revisions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_runtime_nodes
    ADD CONSTRAINT simpoint_ai_runtime_nodes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_runtime_pools
    ADD CONSTRAINT simpoint_ai_runtime_pools_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_runtime_secrets
    ADD CONSTRAINT simpoint_ai_runtime_secrets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_runtime_workloads
    ADD CONSTRAINT simpoint_ai_runtime_workloads_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_runtime_nodes
    ADD CONSTRAINT uk_simpoint_ai_runtime_node_id UNIQUE (node_id);

CREATE INDEX idx_simpoint_ai_runtime_descriptor_name ON simpoint_ai_runtime_mcp_descriptors USING btree (registry_name, server_version);

CREATE INDEX idx_simpoint_ai_runtime_descriptor_scope ON simpoint_ai_runtime_mcp_descriptors USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_runtime_lease_expiry ON simpoint_ai_runtime_leases USING btree (status, expires_at);

CREATE INDEX idx_simpoint_ai_runtime_lease_node ON simpoint_ai_runtime_leases USING btree (node_id);

CREATE INDEX idx_simpoint_ai_runtime_lease_workload ON simpoint_ai_runtime_leases USING btree (workload_id);

CREATE INDEX idx_simpoint_ai_runtime_node_heartbeat ON simpoint_ai_runtime_nodes USING btree (heartbeat_expires_at);

CREATE INDEX idx_simpoint_ai_runtime_node_status ON simpoint_ai_runtime_nodes USING btree (status);

CREATE INDEX idx_simpoint_ai_runtime_pool_profile ON simpoint_ai_runtime_pools USING btree (runtime_profile_id, active_revision_id) WHERE (deleted_at IS NULL);

CREATE INDEX idx_simpoint_ai_runtime_pool_scope ON simpoint_ai_runtime_pools USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_runtime_pool_status ON simpoint_ai_runtime_pools USING btree (status);

CREATE INDEX idx_simpoint_ai_runtime_profile_descriptor ON simpoint_ai_runtime_mcp_profiles USING btree (descriptor_id);

CREATE INDEX idx_simpoint_ai_runtime_profile_scope ON simpoint_ai_runtime_mcp_profiles USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_runtime_revision_profile ON simpoint_ai_runtime_mcp_revisions USING btree (profile_id, revision_number);

CREATE INDEX idx_simpoint_ai_runtime_revision_scope ON simpoint_ai_runtime_mcp_revisions USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_runtime_secret_enabled ON simpoint_ai_runtime_secrets USING btree (enabled);

CREATE INDEX idx_simpoint_ai_runtime_secret_scope ON simpoint_ai_runtime_secrets USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_runtime_workload_node ON simpoint_ai_runtime_workloads USING btree (assigned_node_id);

CREATE INDEX idx_simpoint_ai_runtime_workload_pool ON simpoint_ai_runtime_workloads USING btree (pool_id, status);

CREATE INDEX idx_simpoint_ai_runtime_workload_scope ON simpoint_ai_runtime_workloads USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_runtime_workload_status ON simpoint_ai_runtime_workloads USING btree (status);

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_descriptor_system ON simpoint_ai_runtime_mcp_descriptors USING btree (registry_name, server_version, content_hash) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_descriptor_tenant ON simpoint_ai_runtime_mcp_descriptors USING btree (tenant_id, registry_name, server_version, content_hash) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_lease_active_workload ON simpoint_ai_runtime_leases USING btree (workload_id) WHERE (((status)::text = 'ACTIVE'::text) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_pool_replica ON simpoint_ai_runtime_workloads USING btree (pool_id, replica_sequence) WHERE (pool_id IS NOT NULL);

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_pool_system_code ON simpoint_ai_runtime_pools USING btree (code) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_pool_system_server ON simpoint_ai_runtime_pools USING btree (server_id) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_pool_tenant_code ON simpoint_ai_runtime_pools USING btree (tenant_id, code) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_pool_tenant_server ON simpoint_ai_runtime_pools USING btree (tenant_id, server_id) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_profile_system_code ON simpoint_ai_runtime_mcp_profiles USING btree (code) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_profile_tenant_code ON simpoint_ai_runtime_mcp_profiles USING btree (tenant_id, code) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_revision_number ON simpoint_ai_runtime_mcp_revisions USING btree (profile_id, revision_number) WHERE (deleted_at IS NULL);

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_secret_system_code ON simpoint_ai_runtime_secrets USING btree (code) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_secret_tenant_code ON simpoint_ai_runtime_secrets USING btree (tenant_id, code) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_runtime_workload_execution ON simpoint_ai_runtime_workloads USING btree (execution_id) WHERE (deleted_at IS NULL);
