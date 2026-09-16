package org.simplepoint.plugin.ai.skill.repository;

import java.util.List;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillResourceBinding;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillResourceBindingRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for immutable Skill-to-Resource bindings.
 */
@Repository
public interface JpaAiSkillResourceBindingRepository
    extends BaseRepository<AiSkillResourceBinding, String>,
    AiSkillResourceBindingRepository {

  @Override
  @Query("""
      select binding from AiSkillResourceBinding binding
      where binding.skillVersionId = :skillVersionId
        and binding.deletedAt is null
      order by binding.bindingOrder asc
      """)
  List<AiSkillResourceBinding> findAllActiveBySkillVersionId(
      @Param("skillVersionId") String skillVersionId
  );
}
