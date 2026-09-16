package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDraft;

/** Persistence contract for mutable Skill Drafts. */
public interface AiSkillDraftRepository
    extends BaseRepository<AiSkillDraft, String> {

  /** Finds the active Draft owned by one Skill. */
  Optional<AiSkillDraft> findActiveBySkillId(String skillId);
}
