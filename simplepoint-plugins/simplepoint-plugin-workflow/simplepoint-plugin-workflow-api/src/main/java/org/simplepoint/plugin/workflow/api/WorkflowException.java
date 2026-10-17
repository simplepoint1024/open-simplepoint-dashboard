package org.simplepoint.plugin.workflow.api;

/** Stable workflow-domain failure exposed through the service REST adapter. */
public class WorkflowException extends org.simplepoint.remoting.RemoteBusinessException {

  private final Code code;

  /** Creates a domain exception without a transport dependency. */
  public WorkflowException(final Code code, final String message) {
    super(message);
    this.code = code;
  }

  /** Creates a domain exception while retaining its internal cause. */
  public WorkflowException(final Code code, final String message, final Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  @Override
  public String remoteErrorCode() {
    return "WORKFLOW:" + code.name();
  }

  public Code getCode() {
    return code;
  }

  /** HTTP-neutral error categories. */
  public enum Code {
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    FORBIDDEN,
    ENGINE_FAILURE
  }
}
