package org.simplepoint.plugin.ai.agent.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentVersion;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyOptionView;
import org.simplepoint.plugin.ai.core.api.repository.AiDependencyResolutionView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for immutable Agent versions.
 */
public interface AiAgentVersionRepository
    extends BaseRepository<AiAgentVersion, String> {

  /**
   * Finds a non-deleted version by identifier.
   */
  Optional<AiAgentVersion> findActiveById(String id);

  /**
   * Finds a non-deleted version owned by one Agent.
   */
  Optional<AiAgentVersion> findActiveByIdAndAgentId(
      String id,
      String agentId
  );

  /**
   * Finds and locks a non-deleted version owned by one Agent.
   */
  Optional<AiAgentVersion> findActiveByIdAndAgentIdForUpdate(
      String id,
      String agentId
  );

  /**
   * Finds one semantic version owned by an Agent.
   */
  Optional<AiAgentVersion> findActiveByVersionAndAgentId(
      String version,
      String agentId
  );

  /**
   * Pages non-deleted versions owned by an Agent.
   */
  Page<AiAgentVersion> findAllActiveByAgentId(
      String agentId,
      Pageable pageable
  );

  /**
   * Counts non-deleted versions owned by an Agent.
   */
  long countActiveByAgentId(String agentId);

  /**
   * Searches selectable published Agent version labels for one owner.
   */
  Page<AiDependencyOptionView> searchDependencyOptions(
      boolean tenantOwner,
      String ownerTenantId,
      String searchPattern,
      Pageable pageable
  );

  /**
   * Resolves visible published or deprecated Agent version labels.
   */
  List<AiDependencyResolutionView> resolveDependencyOptions(
      boolean tenantOwner,
      String ownerTenantId,
      Collection<String> resourceVersionIds
  );
}
