-- PostgreSQL baseline owned by ai-knowledge. No legacy data rewrites.
-- Installer connection requires extension provisioning privileges.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE TABLE simpoint_ai_knowledge_bases (
    chunk_overlap integer NOT NULL,
    chunk_size integer NOT NULL,
    embedding_dimensions integer,
    enabled boolean,
    keyword_weight double precision NOT NULL,
    score_threshold double precision NOT NULL,
    top_k integer NOT NULL,
    vector_weight double precision NOT NULL,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    retrieval_mode character varying(16) NOT NULL,
    scope_type character varying(16) NOT NULL,
    embedding_model_id character varying(64),
    tenant_id character varying(64),
    code character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    description character varying(1024),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    updated_by character varying(255),
    CONSTRAINT simpoint_ai_knowledge_bases_retrieval_mode_check CHECK ((retrieval_mode IN ('VECTOR', 'KEYWORD', 'HYBRID'))),
    CONSTRAINT simpoint_ai_knowledge_bases_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT')))
);

CREATE TABLE simpoint_ai_knowledge_chunks (
    id character varying(64) NOT NULL,
    knowledge_base_id character varying(64) NOT NULL,
    document_id character varying(64) NOT NULL,
    scope_type character varying(16) NOT NULL,
    tenant_id character varying(64),
    chunk_index integer NOT NULL,
    content text NOT NULL,
    content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, content)) STORED,
    metadata_json text,
    character_count integer NOT NULL,
    embedding public.vector(2000),
    embedding_dimensions integer,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE simpoint_ai_knowledge_documents (
    chunk_count integer,
    created_at timestamp(6) with time zone,
    deleted_at timestamp(6) with time zone,
    file_size bigint,
    processed_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    scope_type character varying(16) NOT NULL,
    source_type character varying(16) NOT NULL,
    status character varying(16) NOT NULL,
    content_hash character varying(64),
    knowledge_base_id character varying(64) NOT NULL,
    storage_object_id character varying(64),
    storage_tenant_id character varying(64),
    tenant_id character varying(64),
    mime_type character varying(256),
    file_name character varying(512),
    name character varying(512) NOT NULL,
    error_message character varying(2048),
    create_org_dept_id character varying(255),
    created_by character varying(255),
    extracted_text text,
    id character varying(255) NOT NULL,
    metadata_json text,
    updated_by character varying(255),
    CONSTRAINT ck_simpoint_ai_kb_document_status CHECK ((status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED', 'REINDEXING', 'REINDEX_FAILED'))),
    CONSTRAINT simpoint_ai_knowledge_documents_scope_type_check CHECK ((scope_type IN ('SYSTEM', 'TENANT'))),
    CONSTRAINT simpoint_ai_knowledge_documents_source_type_check CHECK ((source_type IN ('UPLOAD', 'TEXT')))
);

CREATE TABLE simpoint_ai_knowledge_index_jobs (
    document_id character varying(64) NOT NULL,
    knowledge_base_id character varying(64) NOT NULL,
    scope_type character varying(16) NOT NULL,
    tenant_id character varying(64),
    job_generation bigint DEFAULT 1 NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    preserve_existing_index boolean DEFAULT false NOT NULL,
    status character varying(16) NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lease_owner character varying(64),
    lease_until timestamp with time zone,
    last_error text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

ALTER TABLE ONLY simpoint_ai_knowledge_bases
    ADD CONSTRAINT simpoint_ai_knowledge_bases_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_knowledge_chunks
    ADD CONSTRAINT simpoint_ai_knowledge_chunks_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_knowledge_documents
    ADD CONSTRAINT simpoint_ai_knowledge_documents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY simpoint_ai_knowledge_index_jobs
    ADD CONSTRAINT simpoint_ai_knowledge_index_jobs_pkey PRIMARY KEY (document_id);

CREATE INDEX idx_simpoint_ai_kb_chunk_base ON simpoint_ai_knowledge_chunks USING btree (knowledge_base_id);

CREATE INDEX idx_simpoint_ai_kb_chunk_embedding ON simpoint_ai_knowledge_chunks USING hnsw (embedding public.vector_cosine_ops);

CREATE INDEX idx_simpoint_ai_kb_chunk_scope ON simpoint_ai_knowledge_chunks USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_kb_chunk_trgm ON simpoint_ai_knowledge_chunks USING gin (content public.gin_trgm_ops);

CREATE INDEX idx_simpoint_ai_kb_chunk_tsv ON simpoint_ai_knowledge_chunks USING gin (content_tsv);

CREATE INDEX idx_simpoint_ai_kb_doc_base ON simpoint_ai_knowledge_documents USING btree (knowledge_base_id);

CREATE INDEX idx_simpoint_ai_kb_doc_scope ON simpoint_ai_knowledge_documents USING btree (scope_type, tenant_id);

CREATE INDEX idx_simpoint_ai_kb_doc_storage ON simpoint_ai_knowledge_documents USING btree (storage_object_id);

CREATE INDEX idx_simpoint_ai_kb_index_job_available ON simpoint_ai_knowledge_index_jobs USING btree (status, next_attempt_at);

CREATE INDEX idx_simpoint_ai_kb_index_job_base ON simpoint_ai_knowledge_index_jobs USING btree (knowledge_base_id);

CREATE INDEX idx_simpoint_ai_kb_scope ON simpoint_ai_knowledge_bases USING btree (scope_type, tenant_id);

CREATE UNIQUE INDEX uk_simpoint_ai_kb_active_system_code ON simpoint_ai_knowledge_bases USING btree (code) WHERE (((scope_type)::text = 'SYSTEM'::text) AND (tenant_id IS NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_kb_active_tenant_code ON simpoint_ai_knowledge_bases USING btree (tenant_id, code) WHERE (((scope_type)::text = 'TENANT'::text) AND (tenant_id IS NOT NULL) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uk_simpoint_ai_kb_chunk_document_index ON simpoint_ai_knowledge_chunks USING btree (document_id, chunk_index);
