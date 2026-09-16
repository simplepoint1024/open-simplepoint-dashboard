package org.simplepoint.plugin.ai.mcp.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpProviderConnection;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpProviderConnectionRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA adapter for subject-owned MCP OAuth connections. */
@Repository
public interface JpaAiMcpProviderConnectionRepository
    extends BaseRepository<AiMcpProviderConnection, String>,
    AiMcpProviderConnectionRepository {

  @Override
  @Query("""
      select connection from AiMcpProviderConnection connection
      where connection.serverId = :serverId
        and connection.userId = :userId
        and connection.deletedAt is null
      """)
  Optional<AiMcpProviderConnection> findActiveByServerAndUser(
      @Param("serverId") String serverId,
      @Param("userId") String userId
  );

  @Override
  @Query("""
      select connection from AiMcpProviderConnection connection
      where connection.serverId = :serverId
        and connection.deletedAt is null
      order by connection.createdAt asc
      """)
  List<AiMcpProviderConnection> findActiveByServer(
      @Param("serverId") String serverId
  );
}
