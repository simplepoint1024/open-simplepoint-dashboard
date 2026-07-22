package org.simplepoint.plugin.ai.knowledge.api.model;

/**
 * Knowledge document processing status.
 */
public enum AiKnowledgeDocumentStatus {
  PENDING,
  PROCESSING,
  READY,
  FAILED,
  REINDEXING,
  REINDEX_FAILED
}
