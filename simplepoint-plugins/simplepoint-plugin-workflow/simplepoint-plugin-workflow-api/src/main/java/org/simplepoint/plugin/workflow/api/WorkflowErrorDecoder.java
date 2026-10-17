package org.simplepoint.plugin.workflow.api;

import org.simplepoint.remoting.RemoteErrorDecoder;

/** Restores only the stable error vocabulary owned by the workflow contract. */
public final class WorkflowErrorDecoder implements RemoteErrorDecoder {
  @Override
  public WorkflowException decode(String code, String message) {
    if (code == null || !code.startsWith("WORKFLOW:")) {
      return null;
    }
    try {
      return new WorkflowException(
          WorkflowException.Code.valueOf(code.substring("WORKFLOW:".length())), message);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }
}
