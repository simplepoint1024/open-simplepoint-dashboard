-- PostgreSQL baseline owned by ai-catalog. No legacy data rewrites.

CREATE TABLE simpoint_ai_catalog_entries (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    lock_version bigint NOT NULL,
    published_at timestamp(6) with time zone,
    registry_updated_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    status character varying(16) NOT NULL,
    kind character varying(32) NOT NULL,
    source character varying(32) NOT NULL,
    transport_type character varying(32),
    external_key character varying(64) NOT NULL,
    version character varying(128) NOT NULL,
    title character varying(256),
    registry_name character varying(512) NOT NULL,
    description character varying(2048),
    endpoint_url character varying(2048),
    repository_url character varying(2048),
    website_url character varying(2048),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    descriptor_json text,
    id character varying(255) NOT NULL,
    raw_json text NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_catalog_entry_status CHECK ((status IN ('ACTIVE', 'DELETED'))),
    CONSTRAINT simpoint_ai_catalog_entries_kind_check CHECK (((kind)::text = 'MCP_SERVER'::text)),
    CONSTRAINT simpoint_ai_catalog_entries_source_check CHECK ((source IN ('INTERNAL', 'OFFICIAL_MCP'))),
    CONSTRAINT simpoint_ai_catalog_entries_status_check CHECK ((status IN ('ACTIVE', 'DELETED')))
);

CREATE TABLE simpoint_ai_catalog_sync_states (
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    last_completed_at timestamp(6) with time zone,
    last_started_at timestamp(6) with time zone,
    lock_version bigint NOT NULL,
    sync_since timestamp(6) with time zone,
    synced_count bigint NOT NULL,
    updated_at timestamp(6) with time zone,
    status character varying(16) NOT NULL,
    sync_mode character varying(16) NOT NULL,
    source character varying(32) NOT NULL,
    cursor character varying(2048),
    last_error character varying(2048),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_catalog_sync_status CHECK ((status IN ('NEVER', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'))),
    CONSTRAINT simpoint_ai_catalog_sync_states_source_check CHECK ((source IN ('INTERNAL', 'OFFICIAL_MCP'))),
    CONSTRAINT simpoint_ai_catalog_sync_states_status_check CHECK ((status IN ('NEVER', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'))),
    CONSTRAINT simpoint_ai_catalog_sync_states_sync_mode_check CHECK ((sync_mode IN ('FULL', 'INCREMENTAL')))
);

ALTER TABLE ONLY simpoint_ai_catalog_entries
    ADD CONSTRAINT simpoint_ai_catalog_entries_external_key_key UNIQUE (external_key);

ALTER TABLE ONLY simpoint_ai_catalog_entries
    ADD CONSTRAINT simpoint_ai_catalog_entries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_catalog_sync_states
    ADD CONSTRAINT simpoint_ai_catalog_sync_states_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_catalog_sync_states
    ADD CONSTRAINT simpoint_ai_catalog_sync_states_source_key UNIQUE (source);

CREATE INDEX idx_simpoint_ai_catalog_name ON simpoint_ai_catalog_entries USING btree (registry_name);

CREATE INDEX idx_simpoint_ai_catalog_source ON simpoint_ai_catalog_entries USING btree (source, status);

CREATE UNIQUE INDEX uk_simpoint_ai_catalog_external_key ON simpoint_ai_catalog_entries USING btree (external_key) WHERE (deleted_at IS NULL);

CREATE UNIQUE INDEX uk_simpoint_ai_catalog_sync_source ON simpoint_ai_catalog_sync_states USING btree (source) WHERE (deleted_at IS NULL);
