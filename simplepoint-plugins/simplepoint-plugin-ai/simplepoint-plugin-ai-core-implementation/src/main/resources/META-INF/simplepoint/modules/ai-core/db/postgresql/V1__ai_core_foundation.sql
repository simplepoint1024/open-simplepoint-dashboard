-- PostgreSQL baseline owned by ai-core. No legacy data rewrites.

CREATE TABLE simpoint_ai_api_keys (
    enabled boolean NOT NULL,
    rate_limit_per_minute integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone,
    last_used_at timestamp(6) with time zone,
    revoked_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    usage_count bigint NOT NULL,
    scope_type character varying(16) NOT NULL,
    key_prefix character varying(40) NOT NULL,
    tenant_id character varying(64),
    name character varying(128) NOT NULL,
    secret_hash character varying(128) NOT NULL,
    description character varying(512),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT simpoint_ai_api_keys_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_gateway_resources (
    created_at timestamp(6) with time zone NOT NULL,
    resource_type character varying(32) NOT NULL,
    api_key_id character varying(64) NOT NULL,
    id character varying(64) NOT NULL,
    invocation_id character varying(64),
    model_definition_id character varying(64) NOT NULL,
    provider_definition_id character varying(64) NOT NULL,
    public_model_id character varying(256) NOT NULL,
    upstream_id character varying(256) NOT NULL
);

CREATE TABLE simpoint_ai_invocations (
    billing_currency character varying(3),
    cached_input_cost numeric(24,12),
    cached_input_tokens integer,
    cached_input_unit_price numeric(19,8),
    input_cost numeric(24,12),
    input_tokens integer,
    input_unit_price numeric(19,8),
    output_cost numeric(24,12),
    output_tokens integer,
    output_unit_price numeric(19,8),
    request_cost numeric(24,12),
    request_unit_price numeric(19,8),
    stream boolean,
    total_cost numeric(24,12),
    total_tokens integer,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    duration_millis bigint,
    started_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone,
    version bigint,
    scope_type character varying(16) NOT NULL,
    billing_status character varying(32),
    operation character varying(32) NOT NULL,
    provider_type character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    context_id character varying(64),
    model_definition_id character varying(64) NOT NULL,
    provider_definition_id character varying(64) NOT NULL,
    tenant_id character varying(64),
    user_id character varying(64),
    error_code character varying(128),
    model_id character varying(256) NOT NULL,
    provider_request_id character varying(256),
    error_message character varying(1024),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_invocation_status CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))),
    CONSTRAINT simpoint_ai_invocations_billing_status_check CHECK ((billing_status IN ('UNPRICED', 'PENDING', 'CALCULATED', 'NOT_CHARGED'))),
    CONSTRAINT simpoint_ai_invocations_operation_check CHECK ((operation IN ('GENERATION', 'EMBEDDING', 'RERANK'))),
    CONSTRAINT simpoint_ai_invocations_provider_type_check CHECK ((provider_type IN ('OPENAI', 'ANTHROPIC', 'OPENAI_COMPATIBLE'))),
    CONSTRAINT simpoint_ai_invocations_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_models (
    available boolean,
    billing_currency character varying(3),
    billing_enabled boolean DEFAULT false NOT NULL,
    cached_input_token_price numeric(19,8),
    discovered boolean,
    enabled boolean,
    input_token_price numeric(19,8),
    output_token_price numeric(19,8),
    pricing_auto_detected boolean,
    request_price numeric(19,8),
    type_auto_detected boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    released_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16),
    model_type character varying(32) NOT NULL,
    provider_id character varying(64) NOT NULL,
    tenant_id character varying(64),
    display_name character varying(256),
    model_id character varying(256) NOT NULL,
    owned_by character varying(256),
    description character varying(512),
    metadata_json character varying(16000),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT simpoint_ai_models_model_type_check CHECK ((model_type IN ('LLM', 'EMBEDDING', 'RERANK', 'IMAGE', 'AUDIO', 'MODERATION', 'MULTIMODAL', 'OTHER'))),
    CONSTRAINT simpoint_ai_models_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_providers (
    allow_private_network boolean,
    auto_sync_enabled boolean,
    enabled boolean,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    last_synced_at timestamp(6) with time zone,
    last_tested_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    last_status character varying(32),
    provider_type character varying(32) NOT NULL,
    vendor character varying(32) NOT NULL,
    tenant_id character varying(64),
    code character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    description character varying(512),
    last_message character varying(1024),
    base_url character varying(2048) NOT NULL,
    model_discovery_url character varying(2048),
    credential_ciphertext character varying(4096),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT simpoint_ai_providers_provider_type_check CHECK ((provider_type IN ('OPENAI', 'ANTHROPIC', 'OPENAI_COMPATIBLE'))),
    CONSTRAINT simpoint_ai_providers_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_providers_vendor_check CHECK ((vendor IN ('OPENAI', 'ANTHROPIC', 'GOOGLE_GEMINI', 'AZURE_OPENAI', 'MISTRAL', 'GROQ', 'DEEPSEEK', 'XAI', 'OPENROUTER', 'TOGETHER_AI', 'FIREWORKS_AI', 'ALIBABA_QWEN', 'MOONSHOT', 'MINIMAX', 'STEPFUN', 'ZHIPU_AI', 'BAIDU_QIANFAN', 'TENCENT_HUNYUAN', 'VOLCENGINE_DOUBAO', 'SILICONFLOW', 'NVIDIA_NIM', 'HUGGING_FACE', 'COHERE', 'OLLAMA', 'LM_STUDIO', 'VLLM', 'CUSTOM')))
);

ALTER TABLE ONLY simpoint_ai_api_keys
    ADD CONSTRAINT simpoint_ai_api_keys_key_prefix_key UNIQUE (key_prefix);

ALTER TABLE ONLY simpoint_ai_api_keys
    ADD CONSTRAINT simpoint_ai_api_keys_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_gateway_resources
    ADD CONSTRAINT simpoint_ai_gateway_resources_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_invocations
    ADD CONSTRAINT simpoint_ai_invocations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_models
    ADD CONSTRAINT simpoint_ai_models_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_providers
    ADD CONSTRAINT simpoint_ai_providers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_models
    ADD CONSTRAINT uk_simpoint_ai_model_provider_model UNIQUE (provider_id, model_id);

CREATE INDEX idx_simpoint_ai_api_key_scope ON simpoint_ai_api_keys USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_invocation_billing ON simpoint_ai_invocations USING btree (billing_status, billing_currency);

CREATE INDEX idx_simpoint_ai_invocation_model ON simpoint_ai_invocations USING btree (model_definition_id);

CREATE INDEX idx_simpoint_ai_invocation_scope ON simpoint_ai_invocations USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_invocation_scope_started ON simpoint_ai_invocations USING btree (scope_type, tenant_id, started_at);

CREATE INDEX idx_simpoint_ai_invocation_started ON simpoint_ai_invocations USING btree (started_at);

CREATE INDEX idx_simpoint_ai_model_provider ON simpoint_ai_models USING btree (provider_id);

CREATE INDEX idx_simpoint_ai_model_scope ON simpoint_ai_models USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_provider_scope ON simpoint_ai_providers USING btree (scope_type, tenant_id);

CREATE UNIQUE INDEX uk_simpoint_ai_api_key_active_system_name ON simpoint_ai_api_keys USING btree (lower((name)::text)) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_api_key_active_tenant_name ON simpoint_ai_api_keys USING btree (tenant_id, lower((name)::text)) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_provider_active_system_code ON simpoint_ai_providers USING btree (code) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_provider_active_tenant_code ON simpoint_ai_providers USING btree (tenant_id, code) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));
