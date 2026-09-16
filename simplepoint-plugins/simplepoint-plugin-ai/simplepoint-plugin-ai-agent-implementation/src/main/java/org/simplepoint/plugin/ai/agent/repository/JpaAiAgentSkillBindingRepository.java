package org.simplepoint.plugin.ai.agent.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentSkillBinding;
import org.simplepoint.plugin.ai.agent.api.repository.AiAgentSkillBindingRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for immutable Agent-to-Skill bindings.
 */
@Repository
public interface JpaAiAgentSkillBindingRepository
    extends BaseRepository<AiAgentSkillBinding, String>,
    AiAgentSkillBindingRepository {

  @Override
  @Query("""
      select binding from AiAgentSkillBinding binding
      where binding.agentVersionId = :agentVersionId
        and binding.deletedAt is null
      order by binding.bindingOrder asc
      """)
  List<AiAgentSkillBinding> findAllActiveByAgentVersionId(
      @Param("agentVersionId") String agentVersionId
  );
}
