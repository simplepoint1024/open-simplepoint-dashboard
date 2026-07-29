package org.simplepoint.plugin.ai.mcp.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpCapabilitySnapshot;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpCapabilitySnapshotRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for immutable MCP capability snapshots.
 */
@Repository
public interface JpaAiMcpCapabilitySnapshotRepository
    extends BaseRepository<AiMcpCapabilitySnapshot, String>,
    AiMcpCapabilitySnapshotRepository {

  @Override
  @Query("""
      select snapshot from AiMcpCapabilitySnapshot snapshot
      where snapshot.id = :id and snapshot.deletedAt is null
      """)
  Optional<AiMcpCapabilitySnapshot> findActiveById(@Param("id") String id);

  @Override
  @Query("""
      select snapshot from AiMcpCapabilitySnapshot snapshot
      where snapshot.serverId = :serverId and snapshot.deletedAt is null
      order by snapshot.discoveredAt desc
      """)
  Page<AiMcpCapabilitySnapshot> findAllActiveByServerId(
      @Param("serverId") String serverId,
      Pageable pageable
  );

  @Override
  @Query("""
      select snapshot from AiMcpCapabilitySnapshot snapshot
      where snapshot.id = :id
        and snapshot.serverId = :serverId
        and snapshot.deletedAt is null
      """)
  Optional<AiMcpCapabilitySnapshot> findActiveByIdAndServerId(
      @Param("id") String id,
      @Param("serverId") String serverId
  );
}
