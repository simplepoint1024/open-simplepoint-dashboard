package org.simplepoint.plugin.ai.agent.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentDefinition;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.agent.api.model.AgentUpsertRequest;
import org.simplepoint.plugin.ai.agent.api.model.AgentVersionCreateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Scope-aware registry for Agent definitions and immutable versions.
 */
public interface AiAgentService {

  /**
   * Pages Agents in the current management scope.
   */
  Page<AiAgentDefinition> findAll(Pageable pageable);

  /**
   * Finds one visible Agent.
   */
  Optional<AiAgentDefinition> find(String id);

  /**
   * Creates an Agent in the current management scope.
   */
  AiAgentDefinition create(AgentUpsertRequest request);

  /**
   * Updates mutable Agent metadata.
   */
  AiAgentDefinition update(String id, AgentUpsertRequest request);

  /**
   * Soft deletes an Agent that has no versions.
   */
  void remove(String id);

  /**
   * Pages immutable versions owned by one Agent.
   */
  Page<AiAgentVersion> findVersions(String agentId, Pageable pageable);

  /**
   * Finds one immutable Agent version.
   */
  Optional<AiAgentVersion> findVersion(String agentId, String versionId);

  /**
   * Creates and pins one immutable Agent version.
   */
  AiAgentVersion createVersion(
      String agentId,
      AgentVersionCreateRequest request
  );

  /**
   * Publishes a version and makes it active.
   */
  AiAgentVersion publishVersion(String agentId, String versionId);

  /**
   * Deprecates a published version.
   */
  AiAgentVersion deprecateVersion(String agentId, String versionId);
}
