package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraftRevision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Persistence contract for immutable Skill Draft Revisions. */
public interface AiSkillDraftRevisionRepository
    extends BaseRepository<AiSkillDraftRevision, String> {

  /** Pages immutable revisions newest first. */
  Page<AiSkillDraftRevision> findAllActiveByDraftId(
      String draftId,
      Pageable pageable
  );

  /** Finds one exact immutable revision. */
  Optional<AiSkillDraftRevision> findActiveByDraftIdAndRevision(
      String draftId,
      long revision
  );
}
