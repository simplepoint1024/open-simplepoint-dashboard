package org.simplepoint.plugin.ai.skill.api.model;

import java.time.Instant;
import java.util.List;

/** Durable aggregate view over Mock executions sharing one test Run ID. */
public record SkillDraftMockTestRun(
    String id,
    String skillId,
    String draftId,
    long draftRevision,
    String draftContentHash,
    SkillDraftMockTestRunStatus status,
    int totalCount,
    int completedCount,
    int passedCount,
    int failedCount,
    Instant createdAt,
    Instant completedAt,
    List<SkillDraftMockTestRunCase> cases
) {

  /** Defensively copies the ordered case summary. */
  public SkillDraftMockTestRun {
    cases = cases == null ? List.of() : List.copyOf(cases);
  }
}
