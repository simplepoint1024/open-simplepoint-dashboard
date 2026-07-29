package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for immutable Skill versions.
 */
public interface AiSkillVersionRepository
    extends BaseRepository<AiSkillVersion, String> {

  /**
   * Finds a non-deleted immutable version by identifier.
   */
  Optional<AiSkillVersion> findActiveById(String id);

  /**
   * Finds a non-deleted version owned by one Skill.
   */
  Optional<AiSkillVersion> findActiveByIdAndSkillId(String id, String skillId);

  /**
   * Finds and locks a non-deleted version owned by one Skill.
   */
  Optional<AiSkillVersion> findActiveByIdAndSkillIdForUpdate(
      String id,
      String skillId
  );

  /**
   * Finds one semantic version owned by a Skill.
   */
  Optional<AiSkillVersion> findActiveByVersionAndSkillId(
      String version,
      String skillId
  );

  /**
   * Pages non-deleted versions owned by a Skill.
   */
  Page<AiSkillVersion> findAllActiveBySkillId(
      String skillId,
      Pageable pageable
  );

  /**
   * Counts non-deleted versions owned by a Skill.
   */
  long countActiveBySkillId(String skillId);
}
