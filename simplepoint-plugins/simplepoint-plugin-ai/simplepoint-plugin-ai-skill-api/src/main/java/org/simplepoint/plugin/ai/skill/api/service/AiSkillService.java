package org.simplepoint.plugin.ai.skill.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillDefinition;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillVersion;
import org.simplepoint.plugin.ai.skill.api.model.SkillUpsertRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionCreateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Scope-aware registry for Skill definitions and immutable versions.
 */
public interface AiSkillService {

  /**
   * Pages Skills in the current management scope.
   */
  Page<AiSkillDefinition> findAll(Pageable pageable);

  /**
   * Finds one visible Skill.
   */
  Optional<AiSkillDefinition> find(String id);

  /**
   * Creates a Skill in the current management scope.
   */
  AiSkillDefinition create(SkillUpsertRequest request);

  /**
   * Updates mutable Skill metadata.
   */
  AiSkillDefinition update(String id, SkillUpsertRequest request);

  /**
   * Soft deletes a Skill that has no versions.
   */
  void remove(String id);

  /**
   * Pages immutable versions owned by one Skill.
   */
  Page<AiSkillVersion> findVersions(String skillId, Pageable pageable);

  /**
   * Finds one immutable Skill version.
   */
  Optional<AiSkillVersion> findVersion(String skillId, String versionId);

  /**
   * Creates and pins one immutable Skill version.
   */
  AiSkillVersion createVersion(
      String skillId,
      SkillVersionCreateRequest request
  );

  /** Creates a version for a durable managed publication worker. */
  AiSkillVersion createManagedVersion(
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      SkillVersionCreateRequest request
  );

  /**
   * Publishes a version and makes it active.
   */
  AiSkillVersion publishVersion(String skillId, String versionId);

  /** Publishes a verified version and optionally activates it for a worker. */
  AiSkillVersion publishManagedVersion(
      String skillId,
      AiResourceScope scopeType,
      String tenantId,
      String versionId,
      boolean activate
  );

  /**
   * Deprecates a published version.
   */
  AiSkillVersion deprecateVersion(String skillId, String versionId);
}
