package org.simplepoint.plugin.ai.mcp.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpInvocation;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpInvocationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for MCP invocation metadata.
 */
@Repository
public interface JpaAiMcpInvocationRepository
    extends BaseRepository<AiMcpInvocation, String>,
    AiMcpInvocationRepository {

  @Override
  @Query("""
      select invocation from AiMcpInvocation invocation
      where invocation.scopeType = :scopeType
        and ((:tenantId is null and invocation.tenantId is null)
          or invocation.tenantId = :tenantId)
        and (:serverId is null or invocation.serverId = :serverId)
        and invocation.deletedAt is null
      order by invocation.startedAt desc
      """)
  Page<AiMcpInvocation> findAllActiveByScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("serverId") String serverId,
      Pageable pageable
  );
}
