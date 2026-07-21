CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

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
