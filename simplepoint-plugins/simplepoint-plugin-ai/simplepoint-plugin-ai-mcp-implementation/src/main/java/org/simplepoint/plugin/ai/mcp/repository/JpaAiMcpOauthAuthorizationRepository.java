package org.simplepoint.plugin.ai.mcp.repository;

import java.time.Instant;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpOauthAuthorization;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpOauthAuthorizationRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA repository for one-time MCP OAuth authorization transactions.
 */
@Repository
public interface JpaAiMcpOauthAuthorizationRepository
    extends BaseRepository<AiMcpOauthAuthorization, String>,
    AiMcpOauthAuthorizationRepository {

  @Override
  @Query("""
      select authorization from AiMcpOauthAuthorization authorization
      where authorization.stateHash = :stateHash
        and authorization.deletedAt is null
      """)
  Optional<AiMcpOauthAuthorization> findActiveByStateHash(
      @Param("stateHash") String stateHash
  );

  @Override
  @Modifying
  @Transactional
  @Query("""
      update AiMcpOauthAuthorization authorization
      set authorization.usedAt = :usedAt
      where authorization.stateHash = :stateHash
        and authorization.usedAt is null
        and authorization.expiresAt > :usedAt
        and authorization.deletedAt is null
      """)
  int consume(
      @Param("stateHash") String stateHash,
      @Param("usedAt") Instant usedAt
  );
}
