CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Hibernate creates the entity tables before this deferred script runs. Partial indexes are used
-- instead of JPA unique constraints so SYSTEM rows with a NULL tenant remain unique and soft-deleted
-- provider/knowledge-base codes can be reused safely.
ALTER TABLE simpoint_ai_providers
  DROP CONSTRAINT IF EXISTS uk_simpoint_ai_provider_scope_code;
DROP INDEX IF EXISTS uk_simpoint_ai_provider_scope_code;
UPDATE simpoint_ai_providers SET scope_type = 'SYSTEM' WHERE scope_type IS NULL;
ALTER TABLE simpoint_ai_providers ALTER COLUMN scope_type SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_provider_active_system_code
  ON simpoint_ai_providers (code)
  WHERE scope_type = 'SYSTEM' AND tenant_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_provider_active_tenant_code
  ON simpoint_ai_providers (tenant_id, code)
  WHERE scope_type = 'TENANT' AND tenant_id IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_api_key_active_system_name
  ON simpoint_ai_api_keys (lower(name))
  WHERE scope_type = 'SYSTEM' AND tenant_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_api_key_active_tenant_name
  ON simpoint_ai_api_keys (tenant_id, lower(name))
  WHERE scope_type = 'TENANT' AND tenant_id IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE simpoint_ai_knowledge_bases
  DROP CONSTRAINT IF EXISTS uk_simpoint_ai_kb_scope_code;
DROP INDEX IF EXISTS uk_simpoint_ai_kb_scope_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_kb_active_system_code
  ON simpoint_ai_knowledge_bases (code)
  WHERE scope_type = 'SYSTEM' AND tenant_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_kb_active_tenant_code
  ON simpoint_ai_knowledge_bases (tenant_id, code)
  WHERE scope_type = 'TENANT' AND tenant_id IS NOT NULL AND deleted_at IS NULL;

-- Refresh enum check constraints when an existing Hibernate-managed table predates new states.
-- Keep these as plain statements because Spring's SQL initializer splits statements on semicolons.
ALTER TABLE simpoint_ai_knowledge_documents
  DROP CONSTRAINT IF EXISTS simpoint_ai_knowledge_documents_status_check;
ALTER TABLE simpoint_ai_knowledge_documents
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_kb_document_status;
ALTER TABLE simpoint_ai_knowledge_documents
  ADD CONSTRAINT ck_simpoint_ai_kb_document_status
  CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED',
                    'REINDEXING', 'REINDEX_FAILED'));

-- The business resource scope and the storage tenant are not always the same (for example,
-- a platform knowledge base can still store its source file in the operator's personal tenant).
ALTER TABLE simpoint_ai_knowledge_documents
  ADD COLUMN IF NOT EXISTS storage_tenant_id VARCHAR(64);
UPDATE simpoint_ai_knowledge_documents document
SET storage_tenant_id = storage_object.tenant_id
FROM simpoint_storage_objects storage_object
WHERE document.storage_object_id = storage_object.id
  AND document.storage_tenant_id IS NULL;

ALTER TABLE simpoint_ai_invocations
  DROP CONSTRAINT IF EXISTS simpoint_ai_invocations_status_check;
ALTER TABLE simpoint_ai_invocations
  DROP CONSTRAINT IF EXISTS ck_simpoint_ai_invocation_status;
ALTER TABLE simpoint_ai_invocations
  ADD CONSTRAINT ck_simpoint_ai_invocation_status
  CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

CREATE TABLE IF NOT EXISTS simpoint_ai_knowledge_index_jobs (
  document_id VARCHAR(64) PRIMARY KEY,
  knowledge_base_id VARCHAR(64) NOT NULL,
  scope_type VARCHAR(16) NOT NULL,
  tenant_id VARCHAR(64),
  job_generation BIGINT NOT NULL DEFAULT 1,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  preserve_existing_index BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(16) NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  lease_owner VARCHAR(64),
  lease_until TIMESTAMP WITH TIME ZONE,
  last_error TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_index_job_available
  ON simpoint_ai_knowledge_index_jobs (status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_index_job_base
  ON simpoint_ai_knowledge_index_jobs (knowledge_base_id);

-- Resume documents left in the legacy synchronous PROCESSING state after an interrupted request.
INSERT INTO simpoint_ai_knowledge_index_jobs (
  document_id, knowledge_base_id, scope_type, tenant_id, preserve_existing_index,
  status, next_attempt_at, created_at, updated_at
)
SELECT id, knowledge_base_id, scope_type, tenant_id, FALSE,
       'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM simpoint_ai_knowledge_documents
WHERE status = 'PROCESSING' AND deleted_at IS NULL
ON CONFLICT (document_id) DO NOTHING;
UPDATE simpoint_ai_knowledge_documents
SET status = 'PENDING'
WHERE status = 'PROCESSING' AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS simpoint_ai_knowledge_chunks (
  id VARCHAR(64) PRIMARY KEY,
  knowledge_base_id VARCHAR(64) NOT NULL,
  document_id VARCHAR(64) NOT NULL,
  scope_type VARCHAR(16) NOT NULL,
  tenant_id VARCHAR(64),
  chunk_index INTEGER NOT NULL,
  content TEXT NOT NULL,
  content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
  metadata_json TEXT,
  character_count INTEGER NOT NULL,
  embedding VECTOR(2000),
  embedding_dimensions INTEGER,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_simpoint_ai_kb_chunk_document_index
  ON simpoint_ai_knowledge_chunks (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_base
  ON simpoint_ai_knowledge_chunks (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_scope
  ON simpoint_ai_knowledge_chunks (scope_type, tenant_id);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_tsv
  ON simpoint_ai_knowledge_chunks USING GIN (content_tsv);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_trgm
  ON simpoint_ai_knowledge_chunks USING GIN (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_simpoint_ai_kb_chunk_embedding
  ON simpoint_ai_knowledge_chunks USING HNSW (embedding vector_cosine_ops);
