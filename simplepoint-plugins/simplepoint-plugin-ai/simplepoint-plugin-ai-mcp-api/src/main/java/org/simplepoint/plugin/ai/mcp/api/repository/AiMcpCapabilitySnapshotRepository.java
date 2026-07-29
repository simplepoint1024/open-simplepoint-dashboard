package org.simplepoint.plugin.ai.mcp.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpCapabilitySnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for immutable MCP capability snapshots.
 */
public interface AiMcpCapabilitySnapshotRepository
    extends BaseRepository<AiMcpCapabilitySnapshot, String> {

  /**
   * Finds a non-deleted capability snapshot by identifier.
   *
   * @param id snapshot identifier
   * @return active snapshot
   */
  Optional<AiMcpCapabilitySnapshot> findActiveById(String id);

  /**
   * Pages immutable snapshots owned by one MCP server.
   */
  Page<AiMcpCapabilitySnapshot> findAllActiveByServerId(
      String serverId,
      Pageable pageable
  );

  /**
   * Finds one snapshot only when it belongs to the selected MCP server.
   */
  Optional<AiMcpCapabilitySnapshot> findActiveByIdAndServerId(
      String id,
      String serverId
  );
}
