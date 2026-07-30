package org.simplepoint.plugin.ai.agent.api.service;

import java.util.List;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentExecution;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemory;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryContext;
import org.simplepoint.plugin.ai.agent.api.vo.AiAgentMemoryWrite;

/**
 * Runtime and management boundary for Agent long-term memory.
 */
public interface AiAgentMemoryService {

  /**
   * Retrieves or restores the pinned context for one execution.
   */
  AiAgentMemoryContext context(AiAgentExecution execution);

  /**
   * Prepares a bounded memory write for a successful execution.
   */
  AiAgentMemoryWrite prepareWrite(
      AiAgentExecution execution,
      String outputJson
  );

  /**
   * Lists current-subject memories for one visible Agent.
   */
  List<AiAgentMemory> findCurrentSubjectMemories(String agentId);

  /**
   * Removes one current-subject memory for one visible Agent.
   */
  void removeCurrentSubjectMemory(String agentId, String memoryId);
}
