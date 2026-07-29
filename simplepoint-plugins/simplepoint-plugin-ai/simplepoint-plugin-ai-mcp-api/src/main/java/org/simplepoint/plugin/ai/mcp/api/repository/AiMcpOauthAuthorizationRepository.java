package org.simplepoint.plugin.ai.mcp.api.repository;

import java.time.Instant;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpOauthAuthorization;

/**
 * Repository for one-time MCP OAuth authorization transactions.
 */
public interface AiMcpOauthAuthorizationRepository
    extends BaseRepository<AiMcpOauthAuthorization, String> {

  /**
   * Finds an active transaction by a SHA-256 state hash.
   */
  Optional<AiMcpOauthAuthorization> findActiveByStateHash(String stateHash);

  /**
   * Atomically consumes a valid unused state.
   *
   * @return number of consumed rows
   */
  int consume(String stateHash, Instant usedAt);
}
