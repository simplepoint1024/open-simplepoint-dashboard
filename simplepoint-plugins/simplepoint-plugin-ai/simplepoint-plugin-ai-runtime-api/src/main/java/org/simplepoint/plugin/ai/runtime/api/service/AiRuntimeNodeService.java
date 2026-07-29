package org.simplepoint.plugin.ai.runtime.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeControlResponse;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeHeartbeat;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeOfflineRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeRegistration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Runtime-node registration, heartbeat, fencing, and platform query contract.
 */
public interface AiRuntimeNodeService {

  /**
   * Registers a new runtime process generation for one stable node.
   */
  RuntimeNodeControlResponse register(
      String nodeId,
      RuntimeNodeRegistration registration
  );

  /**
   * Renews one matching runtime process generation.
   */
  RuntimeNodeControlResponse heartbeat(
      String nodeId,
      RuntimeNodeHeartbeat heartbeat
  );

  /**
   * Marks one matching runtime process generation offline.
   */
  RuntimeNodeControlResponse offline(
      String nodeId,
      RuntimeNodeOfflineRequest request
  );

  /**
   * Returns one platform runtime node.
   */
  Optional<AiRuntimeNode> findActiveByNodeId(String nodeId);

  /**
   * Pages platform runtime nodes.
   */
  Page<AiRuntimeNode> findAll(Pageable pageable);

  /**
   * Marks all expired heartbeat generations offline.
   *
   * @return number of nodes transitioned
   */
  int expireStaleNodes();
}
