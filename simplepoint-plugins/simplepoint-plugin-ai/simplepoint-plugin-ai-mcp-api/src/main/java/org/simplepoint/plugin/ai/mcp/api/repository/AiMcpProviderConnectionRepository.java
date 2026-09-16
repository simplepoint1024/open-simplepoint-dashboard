package org.simplepoint.plugin.ai.mcp.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpProviderConnection;

/** Persistence boundary for subject-owned MCP OAuth connections. */
public interface AiMcpProviderConnectionRepository
    extends BaseRepository<AiMcpProviderConnection, String> {

  /** Finds one active connection owned by the exact server and user. */
  Optional<AiMcpProviderConnection> findActiveByServerAndUser(
      String serverId,
      String userId
  );

  /** Lists subject connections for enforcing one-subject managed runtimes. */
  List<AiMcpProviderConnection> findActiveByServer(String serverId);
}
