package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;

/**
 * Repository contract for immutable Skill-to-Tool bindings.
 */
public interface AiSkillToolBindingRepository
    extends BaseRepository<AiSkillToolBinding, String> {

  /**
   * Lists bindings owned by one Skill version in declaration order.
   */
  List<AiSkillToolBinding> findAllActiveBySkillVersionId(String skillVersionId);
}
