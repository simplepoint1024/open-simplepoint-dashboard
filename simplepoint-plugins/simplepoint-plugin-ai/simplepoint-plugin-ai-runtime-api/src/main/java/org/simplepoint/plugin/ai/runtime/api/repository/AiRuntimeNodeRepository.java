package org.simplepoint.plugin.ai.runtime.api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for OCI runtime nodes.
 */
public interface AiRuntimeNodeRepository
    extends BaseRepository<AiRuntimeNode, String> {

  /**
   * Finds one non-deleted runtime node.
   */
  Optional<AiRuntimeNode> findActiveByNodeId(String nodeId);

  /**
   * Locks one active node for generation-sensitive mutation.
   */
  Optional<AiRuntimeNode> findActiveByNodeIdForUpdate(String nodeId);

  /**
   * Pages all non-deleted platform runtime nodes.
   */
  Page<AiRuntimeNode> findAllActive(Pageable pageable);

  /**
   * Finds nodes whose heartbeat lease has expired in a live status.
   */
  List<AiRuntimeNode> findExpiredNodes(
      Instant now,
      List<RuntimeNodeStatus> statuses
  );

  /**
   * Locks live READY nodes for one capacity-aware scheduling decision.
   */
  List<AiRuntimeNode> findSchedulableNodesForUpdate(Instant now);
}
