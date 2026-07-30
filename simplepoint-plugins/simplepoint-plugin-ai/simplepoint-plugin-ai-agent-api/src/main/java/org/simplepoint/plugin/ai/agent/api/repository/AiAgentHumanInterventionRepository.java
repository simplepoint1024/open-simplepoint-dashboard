package org.simplepoint.plugin.ai.agent.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentHumanIntervention;

/**
 * Repository contract for durable Agent human-intervention tasks.
 */
public interface AiAgentHumanInterventionRepository
    extends BaseRepository<AiAgentHumanIntervention, String> {

  /**
   * Finds one non-deleted intervention.
   */
  Optional<AiAgentHumanIntervention> findActiveById(String id);

  /**
   * Finds and locks one non-deleted intervention.
   */
  Optional<AiAgentHumanIntervention> findActiveByIdForUpdate(String id);

  /**
   * Lists an execution's intervention history.
   */
  List<AiAgentHumanIntervention> findAllActiveByExecutionId(
      String executionId
  );
}
