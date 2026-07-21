package org.simplepoint.plugin.ai.knowledge.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeBase;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeBaseRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for knowledge bases.
 */
@Repository
public interface JpaAiKnowledgeBaseRepository
    extends BaseRepository<AiKnowledgeBase, String>, AiKnowledgeBaseRepository {

  @Override
  @Query("select k from AiKnowledgeBase k where k.id = :id and k.deletedAt is null")
  Optional<AiKnowledgeBase> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select k from AiKnowledgeBase k
      where k.code = :code and k.scopeType = :scopeType and k.deletedAt is null
        and ((:tenantId is null and k.tenantId is null) or k.tenantId = :tenantId)
      """)
  Optional<AiKnowledgeBase> findActiveByCodeAndScope(
      @Param("code") String code,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );
}
