package org.simplepoint.plugin.workflow.directory;

import org.simplepoint.plugin.workflow.api.WorkflowException;

/** Stable diagnostics for a display projection; submitted identities are never error parameters. */
public final class WorkflowMemberLabelException extends WorkflowException {
  private final Reason reason;

  /** Keeps retryable display unavailability separate from a workflow execution failure. */
  public WorkflowMemberLabelException(Reason reason) {
    super(
        reason == Reason.NOT_READY || reason == Reason.LEASE_LOST
            ? Code.DIRECTORY_PREPARING
            : Code.DIRECTORY_UNAVAILABLE,
        "Workflow member label projection unavailable: " + reason);
    this.reason = reason;
  }

  /** Returns a stable internal diagnostic without user-supplied values. */
  public Reason reason() {
    return reason;
  }

  /** Internal causes distinguish source protocol errors from a lost worker lease. */
  public enum Reason {
    NOT_READY,
    SOURCE_UNAVAILABLE,
    INVALID_RESPONSE,
    SOURCE_REGRESSION,
    LEASE_LOST,
    BUILD_LIMIT
  }
}
