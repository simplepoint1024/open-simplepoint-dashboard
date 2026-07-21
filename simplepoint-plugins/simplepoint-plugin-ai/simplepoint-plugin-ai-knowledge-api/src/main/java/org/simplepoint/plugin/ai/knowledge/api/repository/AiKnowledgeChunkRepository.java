package org.simplepoint.plugin.ai.knowledge.api.repository;

import java.util.List;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeChunkRecord;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeSearchHit;
import org.simplepoint.plugin.ai.knowledge.api.vo.AiKnowledgeSearchSpec;

/**
 * pgvector-backed chunk persistence and retrieval contract.
 */
public interface AiKnowledgeChunkRepository {

  /** Atomically replaces all chunks for a document. */
  void replaceDocumentChunks(String documentId, List<AiKnowledgeChunkRecord> chunks);

  /** Deletes all chunks for a document. */
  void deleteByDocumentId(String documentId);

  /** Deletes all chunks for a knowledge base. */
  void deleteByKnowledgeBaseId(String knowledgeBaseId);

  /** Counts chunks in a knowledge base. */
  long countByKnowledgeBaseId(String knowledgeBaseId);

  /** Searches and ranks chunks. */
  List<AiKnowledgeSearchHit> search(AiKnowledgeSearchSpec spec);
}
