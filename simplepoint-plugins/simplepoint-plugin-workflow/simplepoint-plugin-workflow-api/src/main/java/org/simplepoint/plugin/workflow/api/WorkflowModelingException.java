package org.simplepoint.plugin.workflow.api;

/**
 * Safe, localizable model validation failure; never includes engine internals or variable values.
 */
public final class WorkflowModelingException extends WorkflowException {
  private final String messageKey;
  private final String elementId;

  /** Describes a known validation rule and the diagram element the author can correct. */
  public WorkflowModelingException(String rule, String message, String elementId) {
    super(Code.VALIDATION, message);
    this.messageKey = "workflow.modeling." + rule;
    this.elementId = elementId;
  }

  public String getMessageKey() {
    return messageKey;
  }

  public String getElementId() {
    return elementId;
  }
}
