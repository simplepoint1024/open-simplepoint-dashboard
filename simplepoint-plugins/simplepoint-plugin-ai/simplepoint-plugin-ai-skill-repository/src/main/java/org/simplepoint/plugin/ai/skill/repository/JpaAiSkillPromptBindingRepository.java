package org.simplepoint.plugin.ai.skill.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPromptBinding;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillPromptBindingRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for immutable Skill-to-Prompt bindings.
 */
@Repository
public interface JpaAiSkillPromptBindingRepository
    extends BaseRepository<AiSkillPromptBinding, String>,
    AiSkillPromptBindingRepository {

  @Override
  @Query("""
      select binding from AiSkillPromptBinding binding
      where binding.skillVersionId = :skillVersionId
        and binding.deletedAt is null
      order by binding.bindingOrder asc
      """)
  List<AiSkillPromptBinding> findAllActiveBySkillVersionId(
      @Param("skillVersionId") String skillVersionId
  );
}
