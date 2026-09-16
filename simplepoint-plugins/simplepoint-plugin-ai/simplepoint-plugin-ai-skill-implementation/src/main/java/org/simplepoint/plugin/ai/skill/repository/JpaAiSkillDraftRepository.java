package org.simplepoint.plugin.ai.skill.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraft;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for mutable Skill Drafts. */
@Repository
public interface JpaAiSkillDraftRepository
    extends BaseRepository<AiSkillDraft, String>, AiSkillDraftRepository {

  @Override
  @Query("""
      select draft from AiSkillDraft draft
      where draft.skillId = :skillId and draft.deletedAt is null
      """)
  Optional<AiSkillDraft> findActiveBySkillId(
      @Param("skillId") String skillId
  );
}
