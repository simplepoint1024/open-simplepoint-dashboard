package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPromptBinding;

/**
 * Repository contract for immutable Skill-to-Prompt bindings.
 */
public interface AiSkillPromptBindingRepository
    extends BaseRepository<AiSkillPromptBinding, String> {

  /**
   * Lists bindings owned by one Skill version in declaration order.
   */
  List<AiSkillPromptBinding> findAllActiveBySkillVersionId(
      String skillVersionId
  );
}
