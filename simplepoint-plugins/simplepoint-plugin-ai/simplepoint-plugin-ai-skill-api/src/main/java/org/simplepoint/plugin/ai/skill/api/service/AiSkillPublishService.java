package org.simplepoint.plugin.ai.skill.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.skill.api.entity.AiSkillPublishTask;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftPublishRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** User-facing lifecycle for durable managed Skill publications. */
public interface AiSkillPublishService {

  /** Starts or returns one idempotent publication. */
  AiSkillPublishTask start(String skillId, SkillDraftPublishRequest request);

  /** Pages visible publications owned by one Skill. */
  Page<AiSkillPublishTask> findAll(String skillId, Pageable pageable);

  /** Finds one visible publication task. */
  Optional<AiSkillPublishTask> find(String skillId, String taskId);

  /** Requeues one terminal failed task after an operator fix. */
  AiSkillPublishTask retry(String skillId, String taskId);
}
