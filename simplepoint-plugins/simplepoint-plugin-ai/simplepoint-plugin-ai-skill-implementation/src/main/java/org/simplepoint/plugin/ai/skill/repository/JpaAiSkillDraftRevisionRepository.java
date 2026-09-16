package org.simplepoint.plugin.ai.skill.repository;

import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraftRevision;
import org.simplepoint.plugin.ai.skill.api.repository.AiSkillDraftRevisionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** JPA repository for immutable Skill Draft Revisions. */
@Repository
public interface JpaAiSkillDraftRevisionRepository
    extends BaseRepository<AiSkillDraftRevision, String>,
    AiSkillDraftRevisionRepository {

  @Override
  @Query("""
      select revision from AiSkillDraftRevision revision
      where revision.draftId = :draftId and revision.deletedAt is null
      order by revision.revision desc
      """)
  Page<AiSkillDraftRevision> findAllActiveByDraftId(
      @Param("draftId") String draftId,
      Pageable pageable
  );

  @Override
  @Query("""
      select revision from AiSkillDraftRevision revision
      where revision.draftId = :draftId
        and revision.revision = :revisionNumber
        and revision.deletedAt is null
      """)
  Optional<AiSkillDraftRevision> findActiveByDraftIdAndRevision(
      @Param("draftId") String draftId,
      @Param("revisionNumber") long revision
  );
}
