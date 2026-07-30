package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.List;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillResourceBinding;

/**
 * Repository contract for immutable Skill-to-Resource bindings.
 */
public interface AiSkillResourceBindingRepository
    extends BaseRepository<AiSkillResourceBinding, String> {

  /**
   * Lists bindings owned by one Skill version in declaration order.
   */
  List<AiSkillResourceBinding> findAllActiveBySkillVersionId(
      String skillVersionId
  );
}
