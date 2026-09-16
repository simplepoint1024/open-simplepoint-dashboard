package org.simplepoint.plugin.ai.mcp.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpPublication;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpPublicationRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for MCP publications.
 */
@Repository
public interface JpaAiMcpPublicationRepository
    extends BaseRepository<AiMcpPublication, String>, AiMcpPublicationRepository {

  @Override
  @Query("""
      select publication from AiMcpPublication publication
      where publication.id = :id and publication.deletedAt is null
      """)
  Optional<AiMcpPublication> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select publication from AiMcpPublication publication
      where publication.code = :code and publication.deletedAt is null
      """)
  Optional<AiMcpPublication> findActiveByCode(@Param("code") String code);

  @Override
  @Query("""
      select publication from AiMcpPublication publication
      where publication.upstreamServerId = :upstreamServerId
        and publication.deletedAt is null
      """)
  List<AiMcpPublication> findActiveByUpstreamServerId(
      @Param("upstreamServerId") String upstreamServerId
  );

  @Override
  @Query("""
      select publication from AiMcpPublication publication
      where publication.code = :code
        and publication.scopeType = :scopeType
        and ((:tenantId is null and publication.tenantId is null)
          or publication.tenantId = :tenantId)
        and publication.deletedAt is null
      """)
  Optional<AiMcpPublication> findActiveByCodeAndScope(
      @Param("code") String code,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );
}
