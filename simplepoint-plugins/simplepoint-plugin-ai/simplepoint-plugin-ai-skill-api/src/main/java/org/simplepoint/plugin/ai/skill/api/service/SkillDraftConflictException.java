package org.simplepoint.plugin.ai.skill.api.service;

/** Explicit optimistic concurrency conflict for a Skill Draft. */
public class SkillDraftConflictException extends IllegalStateException {

  private final Long expectedRevision;

  private final Long currentRevision;

  /** Creates a revision conflict. */
  public SkillDraftConflictException(
      final String message,
      final Long expectedRevision,
      final Long currentRevision
  ) {
    super(message);
    this.expectedRevision = expectedRevision;
    this.currentRevision = currentRevision;
  }

  /** Returns the stale client revision. */
  public Long getExpectedRevision() {
    return expectedRevision;
  }

  /** Returns the current server revision when known. */
  public Long getCurrentRevision() {
    return currentRevision;
  }
}
