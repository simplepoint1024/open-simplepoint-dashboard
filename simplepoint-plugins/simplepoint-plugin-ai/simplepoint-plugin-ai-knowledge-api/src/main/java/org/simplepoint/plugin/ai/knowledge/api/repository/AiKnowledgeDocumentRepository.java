package org.simplepoint.plugin.ai.knowledge.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;

/**
 * Repository contract for knowledge documents.
 */
public interface AiKnowledgeDocumentRepository
    extends BaseRepository<AiKnowledgeDocument, String> {

  /** Finds an active document by id. */
  Optional<AiKnowledgeDocument> findActiveById(String id);

  /** Lists active documents in a knowledge base. */
  List<AiKnowledgeDocument> findAllActiveByKnowledgeBaseId(String knowledgeBaseId);

  /** Counts active documents in a knowledge base. */
  long countActiveByKnowledgeBaseId(String knowledgeBaseId);
}
