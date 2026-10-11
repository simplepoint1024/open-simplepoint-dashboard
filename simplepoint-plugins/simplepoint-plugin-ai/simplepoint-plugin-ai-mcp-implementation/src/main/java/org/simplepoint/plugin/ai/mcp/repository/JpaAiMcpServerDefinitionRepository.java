package org.simplepoint.plugin.ai.mcp.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpServerDefinitionRepository;
import org.simplepoint.plugin.ai.mcp.api.model.McpCatalogRegistration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for MCP server registrations.
 */
@Repository
public interface JpaAiMcpServerDefinitionRepository
    extends BaseRepository<AiMcpServerDefinition, String>,
    AiMcpServerDefinitionRepository {

  @Override
  @Query(value = """
      select new org.simplepoint.plugin.ai.mcp.api.model.McpCatalogRegistration(
        server.id, server.code, server.remoteServerVersion, server.name, server.description,
        server.status, server.scopeType, server.endpointUrl, server.updatedAt)
      from AiMcpServerDefinition server
      where server.scopeType = :scopeType
        and ((:tenantId is null and server.tenantId is null) or server.tenantId = :tenantId)
        and server.deletedAt is null
        and (:keyword = '' or lower(server.name) like :keyword escape '!'
          or lower(server.code) like :keyword escape '!'
          or lower(coalesce(server.description, '')) like :keyword escape '!')
      order by lower(server.name) asc, server.id asc
      """, countQuery = """
      select count(server) from AiMcpServerDefinition server
      where server.scopeType = :scopeType
        and ((:tenantId is null and server.tenantId is null) or server.tenantId = :tenantId)
        and server.deletedAt is null
        and (:keyword = '' or lower(server.name) like :keyword escape '!'
          or lower(server.code) like :keyword escape '!'
          or lower(coalesce(server.description, '')) like :keyword escape '!')
      """)
  Page<McpCatalogRegistration> findCatalogInScope(
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("keyword") String keyword,
      Pageable pageable
  );

  @Override
  @Query("""
      select server from AiMcpServerDefinition server
      where server.id = :id and server.deletedAt is null
      """)
  Optional<AiMcpServerDefinition> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select server from AiMcpServerDefinition server
      where server.code = :code
        and server.scopeType = :scopeType
        and ((:tenantId is null and server.tenantId is null) or server.tenantId = :tenantId)
        and server.deletedAt is null
      """)
  Optional<AiMcpServerDefinition> findActiveByCodeAndScope(
      @Param("code") String code,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId
  );
}
