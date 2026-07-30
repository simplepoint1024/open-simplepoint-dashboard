package org.simplepoint.plugin.ai.agent.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.agent.api.entity.AiAgentSkillBinding;

/**
 * Repository contract for immutable Agent-to-Skill bindings.
 */
public interface AiAgentSkillBindingRepository
    extends BaseRepository<AiAgentSkillBinding, String> {

  /**
   * Lists bindings in stable manifest order.
   */
  List<AiAgentSkillBinding> findAllActiveByAgentVersionId(
      String agentVersionId
  );
}
