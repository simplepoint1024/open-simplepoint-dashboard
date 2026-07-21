package org.simplepoint.plugin.ai.knowledge.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;

/**
 * Repository contract for knowledge bases.
 */
public interface AiKnowledgeBaseRepository extends BaseRepository<AiKnowledgeBase, String> {

  /** Finds an active knowledge base by id. */
  Optional<AiKnowledgeBase> findActiveById(String id);

  /** Finds an active knowledge base code inside one ownership scope. */
  Optional<AiKnowledgeBase> findActiveByCodeAndScope(
      String code,
      AiResourceScope scopeType,
      String tenantId
  );
}
