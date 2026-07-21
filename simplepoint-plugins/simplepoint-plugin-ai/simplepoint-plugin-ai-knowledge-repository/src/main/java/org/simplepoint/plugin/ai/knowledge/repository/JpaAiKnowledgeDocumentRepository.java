package org.simplepoint.plugin.ai.knowledge.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.knowledge.api.entity.AiKnowledgeDocument;
import org.simplepoint.plugin.ai.knowledge.api.repository.AiKnowledgeDocumentRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for knowledge documents.
 */
@Repository
public interface JpaAiKnowledgeDocumentRepository
    extends BaseRepository<AiKnowledgeDocument, String>, AiKnowledgeDocumentRepository {

  @Override
  @Query("select d from AiKnowledgeDocument d where d.id = :id and d.deletedAt is null")
  Optional<AiKnowledgeDocument> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select d from AiKnowledgeDocument d
      where d.knowledgeBaseId = :knowledgeBaseId and d.deletedAt is null
      order by d.createdAt desc
      """)
  List<AiKnowledgeDocument> findAllActiveByKnowledgeBaseId(
      @Param("knowledgeBaseId") String knowledgeBaseId
  );

  @Override
  @Query("""
      select count(d) from AiKnowledgeDocument d
      where d.knowledgeBaseId = :knowledgeBaseId and d.deletedAt is null
      """)
  long countActiveByKnowledgeBaseId(@Param("knowledgeBaseId") String knowledgeBaseId);
}
