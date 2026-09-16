package org.simplepoint.plugin.ai.skill.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for scope-owned Skill definitions.
 */
public interface AiSkillDefinitionRepository
    extends BaseRepository<AiSkillDefinition, String> {

  /**
   * Finds a non-deleted Skill by identifier.
   */
  Optional<AiSkillDefinition> findActiveById(String id);

  /** Finds all requested non-deleted Skills in one round trip. */
  List<AiSkillDefinition> findAllActiveByIdIn(Collection<String> ids);

  /**
   * Finds and locks a non-deleted Skill by identifier.
   */
  Optional<AiSkillDefinition> findActiveByIdForUpdate(String id);

  /**
   * Finds a Skill code in one ownership scope.
   */
  Optional<AiSkillDefinition> findActiveByCodeAndScope(
      String code,
      AiResourceScope scopeType,
      String tenantId
  );

  /**
   * Pages non-deleted Skills in one ownership scope.
   */
  Page<AiSkillDefinition> findAllActiveByScope(
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );
}
