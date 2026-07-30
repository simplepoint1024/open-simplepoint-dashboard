package org.simplepoint.plugin.ai.skill.service.execution;

/**
 * Terminal failure raised when an immutable Skill execution budget is spent.
 */
public class SkillExecutionBudgetExceededException
    extends IllegalStateException {

  private final String errorCode;

  /**
   * Creates one sanitized budget failure.
   */
  public SkillExecutionBudgetExceededException(
      final String errorCode,
      final String message
  ) {
    super(message);
    this.errorCode = errorCode;
  }

  /**
   * Returns the stable execution error code.
   */
  public String errorCode() {
    return errorCode;
  }
}
