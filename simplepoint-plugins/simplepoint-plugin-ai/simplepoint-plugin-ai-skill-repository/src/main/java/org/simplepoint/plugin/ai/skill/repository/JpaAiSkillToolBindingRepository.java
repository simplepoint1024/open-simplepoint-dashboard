package org.simplepoint.plugin.ai.skill.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillToolBinding;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillToolBindingRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for immutable Skill-to-Tool bindings.
 */
@Repository
public interface JpaAiSkillToolBindingRepository
    extends BaseRepository<AiSkillToolBinding, String>,
    AiSkillToolBindingRepository {

  @Override
  @Query("""
      select binding from AiSkillToolBinding binding
      where binding.skillVersionId = :skillVersionId
        and binding.deletedAt is null
      order by binding.bindingOrder asc
      """)
  List<AiSkillToolBinding> findAllActiveBySkillVersionId(
      @Param("skillVersionId") String skillVersionId
  );
}
