package org.simplepoint.plugin.ai.agent.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentHumanIntervention;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentHumanInterventionRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for Agent human-intervention tasks.
 */
@Repository
public interface JpaAiAgentHumanInterventionRepository
    extends BaseRepository<AiAgentHumanIntervention, String>,
    AiAgentHumanInterventionRepository {

  @Override
  @Query("""
      select intervention from AiAgentHumanIntervention intervention
      where intervention.id = :id and intervention.deletedAt is null
      """)
  Optional<AiAgentHumanIntervention> findActiveById(@Param("id") String id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select intervention from AiAgentHumanIntervention intervention
      where intervention.id = :id and intervention.deletedAt is null
      """)
  Optional<AiAgentHumanIntervention> findActiveByIdForUpdate(
      @Param("id") String id
  );

  @Override
  @Query("""
      select intervention from AiAgentHumanIntervention intervention
      where intervention.executionId = :executionId
        and intervention.deletedAt is null
      order by intervention.requestedAt asc, intervention.id asc
      """)
  List<AiAgentHumanIntervention> findAllActiveByExecutionId(
      @Param("executionId") String executionId
  );
}
