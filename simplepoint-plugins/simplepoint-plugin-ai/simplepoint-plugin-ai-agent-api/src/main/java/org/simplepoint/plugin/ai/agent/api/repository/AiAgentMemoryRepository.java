package org.simplepoint.plugin.ai.agent.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.ai.agent.api.model.AgentMemoryScope;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemory;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemorySearchSpec;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryWrite;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Durable, scope-exact Agent memory persistence contract.
 */
public interface AiAgentMemoryRepository {

  /**
   * Retrieves ranked memories inside one exact isolation boundary.
   */
  List<AiAgentMemory> search(AiAgentMemorySearchSpec spec);

  /**
   * Lists recent memories inside one exact isolation boundary.
   */
  List<AiAgentMemory> findRecent(
      String agentId,
      AiResourceScope scopeType,
      String tenantId,
      AgentMemoryScope memoryScope,
      String subjectId,
      int limit
  );

  /**
   * Stores one source-execution memory idempotently and prunes its boundary.
   */
  String storeAndPrune(AiAgentMemoryWrite memory);

  /**
   * Finds one memory inside one exact isolation boundary.
   */
  Optional<AiAgentMemory> findOwned(
      String memoryId,
      String agentId,
      AiResourceScope scopeType,
      String tenantId,
      AgentMemoryScope memoryScope,
      String subjectId
  );

  /**
   * Soft deletes one memory inside one exact isolation boundary.
   */
  boolean deleteOwned(
      String memoryId,
      String agentId,
      AiResourceScope scopeType,
      String tenantId,
      AgentMemoryScope memoryScope,
      String subjectId
  );
}
